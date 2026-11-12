package com.rewatt.app.energy.tapo

import java.nio.ByteBuffer
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The maths of Tapo's local "KLAP" protocol (v2), with no I/O:
 *  1. handshake1: we send 16 random bytes ([localSeed]); the plug answers its own 16 bytes ([remoteSeed])
 *     plus a hash that proves it knows our login ([expectedServerHash]).
 *  2. handshake2: we send [clientProof] to prove we know it too.
 *  3. Requests are AES-128-CBC with a key / IV / signature derived from both seeds, and a sequence
 *     number that grows with every request ([encrypt] / [decrypt]).
 */
object KlapAuth {
    /** sha256(sha1(email) + sha1(password)): what both sides prove they know. */
    fun authHash(email: String, password: String): ByteArray =
        sha256(sha1(email.toByteArray()) + sha1(password.toByteArray()))

    fun expectedServerHash(localSeed: ByteArray, remoteSeed: ByteArray, auth: ByteArray): ByteArray =
        sha256(localSeed + remoteSeed + auth)

    fun clientProof(localSeed: ByteArray, remoteSeed: ByteArray, auth: ByteArray): ByteArray =
        sha256(remoteSeed + localSeed + auth)

    internal fun sha1(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-1").digest(b)
    internal fun sha256(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(b)
}

/** One KLAP session's keys. Not thread-safe: the caller sends one request at a time. */
class KlapCipher(localSeed: ByteArray, remoteSeed: ByteArray, auth: ByteArray) {
    private val key: ByteArray
    private val ivBase: ByteArray
    private val sig: ByteArray

    /** The last sequence number used; [encrypt] increments it first. */
    var seq: Int
        private set

    init {
        val seeds = localSeed + remoteSeed + auth
        key = KlapAuth.sha256("lsk".toByteArray() + seeds).copyOf(16)
        val iv = KlapAuth.sha256("iv".toByteArray() + seeds)
        ivBase = iv.copyOf(12)
        seq = ByteBuffer.wrap(iv, 28, 4).int
        sig = KlapAuth.sha256("ldk".toByteArray() + seeds).copyOf(28)
    }

    /** Encrypts [json] for the next request: returns its sequence number and the body (signature + ciphertext). */
    fun encrypt(json: String): Pair<Int, ByteArray> {
        seq += 1
        val seqBytes = ByteBuffer.allocate(4).putInt(seq).array()
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(ivBase + seqBytes))
        val ct = cipher.doFinal(json.toByteArray())
        return seq to (KlapAuth.sha256(sig + seqBytes + ct) + ct)
    }

    /** Decrypts the plug's answer to request [seq] (its first 32 bytes are a signature). */
    fun decrypt(seq: Int, body: ByteArray): String {
        val seqBytes = ByteBuffer.allocate(4).putInt(seq).array()
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(ivBase + seqBytes))
        return String(cipher.doFinal(body, 32, body.size - 32))
    }
}
