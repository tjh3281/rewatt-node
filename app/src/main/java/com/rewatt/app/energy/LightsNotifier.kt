package com.rewatt.app.energy

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.rewatt.app.MainActivity
import com.rewatt.app.R
import com.rewatt.app.security.localizedContext

/**
 * "Lights ON / OFF" notification each time the lights icon changes, saying why (camera, Auto-off,
 * schedule, a tap). Silent heads-up; one notification that is replaced by the next change, so a
 * flickering reading can't pile them up, and it clears itself after [TIMEOUT_MS].
 */
class LightsNotifier(context: Context) {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(NotificationManager::class.java)

    fun notify(change: LightsChange, roomId: String) {
        val text = localizedContext(appContext)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, text.getString(R.string.lights_channel), NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null)
                enableVibration(false)
            },
        )
        val reason = when (change.reason) {
            LightsChange.Reason.CAMERA -> if (change.on) R.string.lights_reason_camera_on else R.string.lights_reason_camera_off
            LightsChange.Reason.AUTO_OFF -> R.string.lights_reason_auto_off
            LightsChange.Reason.SCHEDULE -> if (change.on) R.string.lights_reason_schedule_on else R.string.lights_reason_schedule_off
            LightsChange.Reason.MANUAL -> if (change.on) R.string.lights_reason_manual_on else R.string.lights_reason_manual_off
        }
        val open = PendingIntent.getActivity(
            appContext,
            REQUEST_OPEN,
            Intent(appContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(appContext, CHANNEL)
            .setSmallIcon(R.drawable.ic_rewatt)
            .setContentTitle(text.getString(if (change.on) R.string.lights_on_title else R.string.lights_off_title, roomId))
            .setContentText(text.getString(reason))
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setTimeoutAfter(TIMEOUT_MS)
            .build()
        runCatching { NotificationManagerCompat.from(appContext).notify(NOTIFICATION_ID, notification) }
    }

    private companion object {
        const val CHANNEL = "rewatt_lights"
        const val NOTIFICATION_ID = 4710
        const val REQUEST_OPEN = 4711
        const val TIMEOUT_MS = 60_000L
    }
}
