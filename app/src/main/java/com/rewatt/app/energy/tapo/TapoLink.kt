package com.rewatt.app.energy.tapo

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import com.rewatt.app.BuildConfig
import com.rewatt.app.energy.LightsChange
import com.rewatt.app.energy.LightsController
import java.io.IOException
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

enum class TapoStatus { OFF, SEARCHING, LINKED, NOT_LINKED }

data class TapoLinkState(
    val status: TapoStatus = TapoStatus.SEARCHING,
    val ip: String? = null,
    val nickname: String? = null,
    val plugOn: Boolean? = null,
    val rttMs: Long? = null,
    /** Why it isn't linked (shown only in Settings). */
    val problem: String? = null,
) {
    val linked: Boolean get() = status == TapoStatus.LINKED
}

/** The Settings backup: login and plug address. A blank email / password means the built-in default. */
data class TapoConfig(val enabled: Boolean, val email: String, val password: String, val ip: String)

/**
 * The Room 1 lights' Tapo smart plug, controlled over local Wi-Fi (no cloud).
 *  - Every lights switch made by the app (a tap, Auto-off, Schedule) is sent to the plug at once.
 *  - A heartbeat reads the plug every [POLL_MS]; its real state goes back into [LightsController], so a
 *    switch from the Tapo app shows here too. Only [MISSES] failed reads in a row unlink it, so one
 *    Wi-Fi hiccup doesn't make the bulb icon flicker.
 *  - Not linked: it looks again every [RETRY_MS] (saved IP first, then the whole Wi-Fi subnet).
 */
class TapoLink(context: Context, private val lights: LightsController) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("rewatt_tapo_prefs", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val commands = Channel<Boolean>(Channel.CONFLATED)

    private val mutableState = MutableStateFlow(TapoLinkState())
    val state: StateFlow<TapoLinkState> = mutableState.asStateFlow()

    // Guarded by [lock].
    private var client: TapoClient? = null

    @Volatile private var resetRequested = false
    @Volatile private var lastRequestAt = 0L
    @Volatile private var lastCommandDoneAt = 0L

    @Volatile private var wifi: Network? = null
    @Volatile private var wifiLink: LinkProperties? = null

    /** When the phone last (re)joined a Wi-Fi network. */
    @Volatile private var wifiBackAt = 0L

    val config: TapoConfig
        get() = TapoConfig(
            enabled = prefs.getBoolean(KEY_ENABLED, true),
            email = prefs.getString(KEY_EMAIL, "").orEmpty().ifBlank { BuildConfig.TAPO_EMAIL },
            password = prefs.getString(KEY_PASSWORD, "").orEmpty().ifBlank { BuildConfig.TAPO_PASSWORD },
            ip = prefs.getString(KEY_IP, "").orEmpty(),
        )

    init {
        watchWifi()
        scope.launch { heartbeat() }
        scope.launch { sendCommands() }
        // The app switched the lights (not a reading from the camera or the plug): switch the plug.
        scope.launch {
            lights.state
                .map { it.lastChange }
                .distinctUntilChanged()
                .drop(1)
                .filterNotNull()
                .filter { it.reason != LightsChange.Reason.CAMERA && it.reason != LightsChange.Reason.PLUG }
                .collect { request(it.on) }
        }
    }

    /** Switch the plug (the newest request wins). */
    fun request(on: Boolean) {
        lastRequestAt = System.currentTimeMillis()
        commands.trySend(on)
    }

    /** Settings: save the backup and link again now. */
    fun save(cfg: TapoConfig) {
        prefs.edit()
            .putBoolean(KEY_ENABLED, cfg.enabled)
            .putString(KEY_EMAIL, cfg.email.trim().takeIf { it != BuildConfig.TAPO_EMAIL }.orEmpty())
            .putString(KEY_PASSWORD, cfg.password.takeIf { it != BuildConfig.TAPO_PASSWORD }.orEmpty())
            .putString(KEY_IP, cfg.ip.trim())
            .apply()
        relink()
    }

    /** Forget the current connection and find the plug again now. */
    fun relink() {
        resetRequested = true
        if (config.enabled) mutableState.update { it.copy(status = TapoStatus.SEARCHING, problem = null) }
        wake.trySend(Unit)
    }

    /** Settings test button: flip the plug. */
    fun testToggle() = request(state.value.plugOn != true)

    private suspend fun heartbeat() {
        var misses = 0
        while (true) {
            val cfg = config
            if (!cfg.enabled || cfg.email.isBlank() || cfg.password.isBlank()) {
                lock.withLock { client = null }
                misses = 0
                unlink(TapoStatus.OFF, if (cfg.enabled) "No Tapo login" else null)
                waitFor(RETRY_MS)
                continue
            }
            val started = System.currentTimeMillis()
            val result = runCatching {
                lock.withLock {
                    if (resetRequested) {
                        resetRequested = false
                        client = null
                    }
                    val c = client ?: find(cfg)
                    client = c
                    val t = System.nanoTime()
                    try {
                        c.info() to (System.nanoTime() - t) / 1_000_000
                    } catch (e: IOException) {
                        client = null
                        throw e
                    }
                }
            }
            result.onSuccess { (info, rtt) ->
                misses = 0
                val ip = lock.withLock { client?.ip }
                if (ip != null && ip != prefs.getString(KEY_SAVED_IP, null)) prefs.edit().putString(KEY_SAVED_IP, ip).apply()
                mutableState.value = TapoLinkState(TapoStatus.LINKED, ip, info.nickname, info.on, rtt, null)
                // Only a reading taken after the last switch finished, with none waiting, is the truth.
                if (lastRequestAt <= lastCommandDoneAt && started >= lastCommandDoneAt) lights.plugReading(info.on, started)
            }.onFailure { e ->
                misses++
                Log.w(TAG, "Tapo read failed ($misses): ${e.message}")
                val problem = e.message ?: e.javaClass.simpleName
                if (!state.value.linked || misses >= MISSES) {
                    unlink(TapoStatus.NOT_LINKED, problem)
                } else {
                    mutableState.update { it.copy(problem = problem) }
                }
            }
            waitFor(
                when {
                    state.value.linked -> POLL_MS
                    // Just back on Wi-Fi: the plug rejoins on its own time (10–60 s), so look often.
                    System.currentTimeMillis() - wifiBackAt < FAST_RETRY_WINDOW_MS -> FAST_RETRY_MS
                    else -> RETRY_MS
                },
            )
        }
    }

    private suspend fun sendCommands() {
        for (on in commands) {
            try {
                lock.withLock {
                    val c = client ?: return@withLock
                    val t = System.nanoTime()
                    try {
                        c.setOn(on)
                        val ms = (System.nanoTime() - t) / 1_000_000
                        Log.i(TAG, "Plug ${if (on) "on" else "off"} in $ms ms")
                        mutableState.update { it.copy(plugOn = on, rttMs = ms) }
                    } catch (e: IOException) {
                        Log.w(TAG, "Plug switch failed: ${e.message}")
                        client = null
                        wake.trySend(Unit)
                    }
                }
            } finally {
                lastCommandDoneAt = System.currentTimeMillis()
            }
        }
    }

    private fun unlink(status: TapoStatus, problem: String?) {
        mutableState.update { TapoLinkState(status, it.ip, it.nickname, null, null, problem) }
        lights.plugReading(null, System.currentTimeMillis())
    }

    private suspend fun waitFor(ms: Long) {
        withTimeoutOrNull(ms) { wake.receive() }
    }

    /**
     * The plug: first the IP typed in Settings and the last one that worked, then a scan of the Wi-Fi
     * subnet, so it is found again even when the hotspot hands it a new address.
     */
    private suspend fun find(cfg: TapoConfig): TapoClient {
        if (wifi == null) throw IOException("No Wi-Fi")
        val auth = KlapAuth.authHash(cfg.email.trim(), cfg.password)
        val guesses = listOfNotNull(cfg.ip.takeIf { it.isNotBlank() }, prefs.getString(KEY_SAVED_IP, null)).distinct()
        // The scan (~0.4 s) says who is answering; known addresses that answer are tried first, and a
        // guess that is gone costs nothing. Without a scan (no subnet yet), just try the guesses.
        val open = scanPort80()
        val candidates = if (open.isEmpty()) guesses else guesses.filter { it in open } + (open - guesses.toSet())
        var refused: IOException? = null
        for (ip in candidates) {
            try {
                return connect(ip, auth)
            } catch (e: TapoLoginRefused) {
                refused = e
            } catch (_: IOException) {
            }
        }
        throw refused ?: IOException("Plug not found on this Wi-Fi")
    }

    /** Handshake with [ip] and check it is a Tapo plug. */
    private fun connect(ip: String, auth: ByteArray): TapoClient {
        val c = TapoClient(ip, auth, ::open)
        c.handshake()
        val info = c.info()
        if (!info.type.contains("PLUG", ignoreCase = true)) throw IOException("$ip is a ${info.model}, not a plug")
        Log.i(TAG, "Linked to ${info.model} \"${info.nickname}\" at $ip")
        return c
    }

    /** Every host on the phone's Wi-Fi subnet (at most a /24) with port 80 open. */
    private suspend fun scanPort80(): List<String> = coroutineScope {
        val lp = wifiLink ?: return@coroutineScope emptyList()
        val la = lp.linkAddresses.firstOrNull { it.address is Inet4Address } ?: return@coroutineScope emptyList()
        val own = la.address.address
        val prefix = la.prefixLength.coerceIn(24, 30)
        val ownInt = own.fold(0) { acc, b -> (acc shl 8) or (b.toInt() and 0xFF) }
        val mask = -1 shl (32 - prefix)
        val base = ownInt and mask
        val hosts = (1 until (1 shl (32 - prefix)) - 1).map { base + it }.filter { it != ownInt }
        val net = wifi ?: return@coroutineScope emptyList()
        hosts.map { h ->
            async {
                val ip = "${h ushr 24 and 0xFF}.${h ushr 16 and 0xFF}.${h ushr 8 and 0xFF}.${h and 0xFF}"
                runCatching {
                    Socket().use { s ->
                        net.bindSocket(s)
                        s.connect(InetSocketAddress(ip, 80), PROBE_MS)
                    }
                    ip
                }.getOrNull()
            }
        }.awaitAll().filterNotNull()
    }

    /** HTTP over Wi-Fi only (never mobile data, where the plug can't be and a try only times out). */
    private fun open(url: URL): HttpURLConnection =
        (wifi ?: throw IOException("No Wi-Fi")).openConnection(url) as HttpURLConnection

    private fun watchWifi() {
        val cm = appContext.getSystemService(ConnectivityManager::class.java) ?: return
        val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
        runCatching {
            cm.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    if (wifi != network) {
                        Log.i(TAG, "Wi-Fi joined: finding the plug")
                        wifi = network
                        wifiLink = cm.getLinkProperties(network)
                        wifiBackAt = System.currentTimeMillis()
                        relink()
                    }
                }

                override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                    if (network != wifi) return
                    val hadIpv4 = wifiLink.hasIpv4()
                    wifiLink = linkProperties
                    // The address arrived (DHCP finished): look for the plug now.
                    if (!hadIpv4 && linkProperties.hasIpv4()) wake.trySend(Unit)
                }

                override fun onLost(network: Network) {
                    if (network == wifi) {
                        Log.i(TAG, "Wi-Fi lost: plug unlinked until it is back")
                        wifi = null
                        wifiLink = null
                        resetRequested = true
                        // No Wi-Fi, no plug: hide the lights icon now instead of after the missed reads.
                        unlink(TapoStatus.NOT_LINKED, "No Wi-Fi")
                        wake.trySend(Unit)
                    }
                }
            })
        }.onFailure { Log.w(TAG, "Can't watch Wi-Fi: ${it.message}") }
    }

    private fun LinkProperties?.hasIpv4(): Boolean = this?.linkAddresses?.any { it.address is Inet4Address } == true

    companion object {
        private const val TAG = "TapoLink"
        const val POLL_MS = 3_000L
        const val RETRY_MS = 5_000L
        private const val FAST_RETRY_MS = 2_000L
        private const val FAST_RETRY_WINDOW_MS = 60_000L
        const val MISSES = 3
        private const val PROBE_MS = 400
        private const val KEY_ENABLED = "enabled"
        private const val KEY_EMAIL = "email"
        private const val KEY_PASSWORD = "password"
        private const val KEY_IP = "ip"
        private const val KEY_SAVED_IP = "saved_ip"
    }
}
