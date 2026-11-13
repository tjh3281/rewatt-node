package com.rewatt.app.usb

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import com.rewatt.app.R
import com.rewatt.app.ReWattApplication
import com.rewatt.app.data.TelemetryRepository
import java.io.IOException
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class UsbSentinelService : Service(), SerialInputOutputManager.Listener {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lineBuffer = StringBuilder()

    private lateinit var usbManager: UsbManager
    private lateinit var repository: TelemetryRepository

    private var serialPort: UsbSerialPort? = null
    private var ioManager: SerialInputOutputManager? = null
    private var receiverRegistered = false

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> handleUsbPermissionResult(intent)
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> scanAndConnect()
                UsbManager.ACTION_USB_DEVICE_DETACHED -> disconnect(
                    status = UsbConnectionStatus.Disconnected,
                    message = "USB device detached.",
                )
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        usbManager = getSystemService(UsbManager::class.java)
        repository = (application as ReWattApplication).appGraph.telemetryRepository
        registerUsbReceiver()
        startAsForegroundService()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        scanAndConnect()
        return START_STICKY
    }

    override fun onDestroy() {
        disconnect(
            status = UsbConnectionStatus.Disconnected,
            message = "USB service stopped.",
        )
        if (receiverRegistered) {
            unregisterReceiver(usbReceiver)
            receiverRegistered = false
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onNewData(data: ByteArray) {
        val chunk = String(data, StandardCharsets.UTF_8)
        for (char in chunk) {
            when (char) {
                '\n' -> flushLineBuffer()
                '\r' -> Unit
                else -> appendToLineBuffer(char)
            }
        }
    }

    override fun onRunError(e: Exception) {
        disconnect(
            status = UsbConnectionStatus.Error,
            message = "Serial reader stopped: ${e.message ?: e::class.java.simpleName}",
        )
    }

    private fun registerUsbReceiver() {
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }

        ContextCompat.registerReceiver(
            this,
            usbReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        receiverRegistered = true
    }

    private fun startAsForegroundService() {
        createNotificationChannel()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            initialForegroundServiceType(),
        )
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_rewatt)
            .setContentTitle(getString(R.string.usb_notification_title))
            .setContentText(getString(R.string.usb_notification_text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.usb_notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun scanAndConnect() {
        repository.setConnectionStatus(
            status = UsbConnectionStatus.Scanning,
            message = "Scanning for ESP32-C6 USB serial devices.",
        )

        val driver = UsbSerialProber.getDefaultProber()
            .findAllDrivers(usbManager)
            .firstOrNull()

        if (driver == null) {
            repository.setConnectionStatus(
                status = UsbConnectionStatus.Disconnected,
                message = "No compatible USB serial device found.",
            )
            return
        }

        if (!usbManager.hasPermission(driver.device)) {
            repository.setConnectionStatus(
                status = UsbConnectionStatus.PermissionRequired,
                message = "USB permission requested for ${driver.device.productName ?: "ESP32-C6"}.",
            )
            usbManager.requestPermission(driver.device, usbPermissionIntent())
            return
        }

        openDriver(driver)
    }

    private fun handleUsbPermissionResult(intent: Intent) {
        val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
        val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }

        // Some phones don't attach the result to the callback: ask Android whether access was granted
        // (the device itself, or any matching serial device when the callback carries no device).
        val driver = UsbSerialProber.getDefaultProber()
            .findAllDrivers(usbManager)
            .firstOrNull { device == null || it.device.deviceId == device.deviceId }

        if (!granted && (driver == null || !usbManager.hasPermission(driver.device))) {
            repository.setConnectionStatus(
                status = UsbConnectionStatus.PermissionDenied,
                message = "USB permission denied.",
            )
            return
        }

        if (driver == null) {
            repository.setConnectionStatus(
                status = UsbConnectionStatus.DriverMissing,
                message = "USB permission granted, but no serial driver matched this device.",
            )
            return
        }

        openDriver(driver)
    }

    private fun openDriver(driver: UsbSerialDriver) {
        disconnect(
            status = UsbConnectionStatus.Scanning,
            message = "Opening USB serial connection.",
        )

        val connection = usbManager.openDevice(driver.device)
        if (connection == null) {
            repository.setConnectionStatus(
                status = UsbConnectionStatus.Error,
                message = "Unable to open USB device connection.",
            )
            return
        }

        val port = driver.ports.firstOrNull()
        if (port == null) {
            connection.close()
            repository.setConnectionStatus(
                status = UsbConnectionStatus.DriverMissing,
                message = "USB serial driver has no available ports.",
            )
            return
        }

        try {
            port.open(connection)
            port.setParameters(
                BAUD_RATE,
                DATA_BITS,
                UsbSerialPort.STOPBITS_1,
                UsbSerialPort.PARITY_NONE,
            )
            serialPort = port
            val manager = SerialInputOutputManager(port, this)
            ioManager = manager
            manager.start()
            promoteToConnectedDeviceForegroundService()
            repository.setConnectionStatus(
                status = UsbConnectionStatus.Connected,
                message = "Connected at $BAUD_RATE baud.",
            )
        } catch (exception: IOException) {
            connection.close()
            repository.setConnectionStatus(
                status = UsbConnectionStatus.Error,
                message = "Failed to configure serial port: ${exception.message ?: "unknown error"}",
            )
        }
    }

    private fun disconnect(status: UsbConnectionStatus, message: String) {
        ioManager?.stop()
        ioManager = null

        try {
            serialPort?.close()
        } catch (_: IOException) {
        } finally {
            serialPort = null
            lineBuffer.clear()
        }

        repository.setConnectionStatus(status, message)
    }

    private fun appendToLineBuffer(char: Char) {
        if (lineBuffer.length >= MAX_LINE_LENGTH) {
            lineBuffer.clear()
            serviceScope.launch {
                repository.acceptRawPacket("<discarded oversized serial line>")
            }
            return
        }
        lineBuffer.append(char)
    }

    private fun flushLineBuffer() {
        val line = lineBuffer.toString()
        lineBuffer.clear()
        if (line.isBlank()) return

        serviceScope.launch {
            repository.acceptRawPacket(line)
        }
    }

    /**
     * Android fills in the answer (EXTRA_PERMISSION_GRANTED / EXTRA_DEVICE) on this callback, so it must
     * be MUTABLE: an immutable one arrives without the answer and reads as "denied". It names this app's
     * package, so only ReWatt receives it.
     */
    private fun usbPermissionIntent(): PendingIntent =
        PendingIntent.getBroadcast(
            this,
            USB_PERMISSION_REQUEST_CODE,
            Intent(ACTION_USB_PERMISSION).setPackage(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0),
        )

    private fun promoteToConnectedDeviceForegroundService() {
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(),
                connectedForegroundServiceType(),
            )
        } catch (exception: SecurityException) {
            repository.setConnectionStatus(
                status = UsbConnectionStatus.Error,
                message = "Connected, but Android rejected connected-device foreground mode: ${exception.message}",
            )
        }
    }

    companion object {
        private const val ACTION_USB_PERMISSION = "com.rewatt.app.action.USB_PERMISSION"
        private const val NOTIFICATION_CHANNEL_ID = "rewatt_usb_link"
        private const val NOTIFICATION_ID = 42
        private const val USB_PERMISSION_REQUEST_CODE = 10
        private const val BAUD_RATE = 115_200
        private const val DATA_BITS = 8
        private const val MAX_LINE_LENGTH = 512

        fun createStartIntent(context: Context): Intent =
            Intent(context, UsbSentinelService::class.java)
    }
}

private fun initialForegroundServiceType(): Int =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
    } else {
        0
    }

private fun connectedForegroundServiceType(): Int =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
    } else {
        0
    }
