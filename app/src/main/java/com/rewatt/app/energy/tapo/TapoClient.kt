package com.rewatt.app.energy.tapo

import android.util.Base64
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import org.json.JSONObject

/** What get_device_info told us. */
data class TapoInfo(val on: Boolean, val model: String, val type: String, val nickname: String)

/** The plug answered, but refused our login (wrong email / password, or Third-Party Compatibility off). */
class TapoLoginRefused(message: String) : IOException(message)

/**
 * One Tapo plug at [ip] over local KLAP. Keeps the session (cookie + keys) between calls; when a call
 * fails it does one fresh handshake and tries again. Not thread-safe: [TapoLink] calls it under a lock.
 * [open] makes the HTTP connection (bound to the Wi-Fi network on the phone).
 */
class TapoClient(
    val ip: String,
    private val auth: ByteArray,
    private val open: (URL) -> HttpURLConnection,
) {
    private var cookie: String? = null
    private var cipher: KlapCipher? = null
    private var sessionStartedAt = 0L

    fun info(): TapoInfo {
        val r = call("""{"method":"get_device_info"}""").getJSONObject("result")
        val nick = r.optString("nickname").let { n ->
            runCatching { String(Base64.decode(n, Base64.DEFAULT)) }.getOrDefault(n)
        }
        return TapoInfo(r.optBoolean("device_on"), r.optString("model"), r.optString("type"), nick)
    }

    fun setOn(on: Boolean) {
        call("""{"method":"set_device_info","params":{"device_on":$on}}""")
    }

    /** Proves the login with handshake1 + handshake2 and starts a new session. */
    fun handshake() {
        cookie = null
        cipher = null
        val localSeed = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val (code1, body1, setCookie) = post("/app/handshake1", localSeed)
        if (code1 == 403) throw TapoLoginRefused("Plug refused local access (turn on Third-Party Compatibility)")
        if (code1 != 200 || body1.size < 48) throw IOException("handshake1 HTTP $code1")
        val remoteSeed = body1.copyOfRange(0, 16)
        if (!body1.copyOfRange(16, 48).contentEquals(KlapAuth.expectedServerHash(localSeed, remoteSeed, auth))) {
            throw TapoLoginRefused("Wrong Tapo email or password")
        }
        cookie = setCookie?.substringBefore(';')
        val (code2, _, _) = post("/app/handshake2", KlapAuth.clientProof(localSeed, remoteSeed, auth))
        if (code2 != 200) throw TapoLoginRefused("handshake2 HTTP $code2")
        cipher = KlapCipher(localSeed, remoteSeed, auth)
        sessionStartedAt = System.currentTimeMillis()
    }

    private fun call(json: String): JSONObject = try {
        callOnce(json)
    } catch (e: TapoLoginRefused) {
        throw e
    } catch (e: IOException) {
        handshake()
        callOnce(json)
    }

    private fun callOnce(json: String): JSONObject {
        // Sessions last 24 h on the plug; start a fresh one well before that.
        if (cipher == null || System.currentTimeMillis() - sessionStartedAt > SESSION_MS) handshake()
        val c = cipher ?: throw IOException("no session")
        val (seq, body) = c.encrypt(json)
        val (code, resp, _) = post("/app/request?seq=$seq", body)
        if (code != 200) {
            cipher = null
            throw IOException("request HTTP $code")
        }
        val out = JSONObject(c.decrypt(seq, resp))
        val err = out.optInt("error_code", -1)
        if (err != 0) {
            cipher = null
            throw IOException("Tapo error $err")
        }
        return out
    }

    private data class Reply(val code: Int, val body: ByteArray, val setCookie: String?)

    private fun post(path: String, data: ByteArray): Reply {
        // A fresh connection each time (a few ms on the LAN): the plug drops idle ones, and a reused
        // dead connection would cost a failed request plus a new handshake.
        val conn = open(URL("http://$ip$path"))
        conn.setRequestProperty("Connection", "close")
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout = READ_TIMEOUT_MS
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.useCaches = false
        conn.setRequestProperty("Content-Type", "application/octet-stream")
        cookie?.let { conn.setRequestProperty("Cookie", it) }
        conn.setFixedLengthStreamingMode(data.size)
        conn.outputStream.use { it.write(data) }
        val code = conn.responseCode
        val body = (if (code in 200..299) conn.inputStream else conn.errorStream)?.use { it.readBytes() } ?: ByteArray(0)
        return Reply(code, body, conn.getHeaderField("Set-Cookie"))
    }

    companion object {
        const val CONNECT_TIMEOUT_MS = 1_500
        const val READ_TIMEOUT_MS = 2_000
        private const val SESSION_MS = 6 * 60 * 60 * 1000L
    }
}
