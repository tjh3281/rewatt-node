package com.rewatt.app.energy.tapo

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/** Expected values come from a reference KLAP v2 implementation that switched a real P110. */
class KlapCipherTest {
    private val local = ByteArray(16) { it.toByte() }
    private val remote = ByteArray(16) { (it + 16).toByte() }
    private val auth = KlapAuth.authHash("user@example.com", "secret123")

    @Test
    fun authHashAndHandshakeHashes() {
        assertEquals("65b3e6b61a2e4360b05aa8709937314d6e9bfe79686e9dec1ee536b918d6921e", auth.hex())
        assertEquals(
            "f5a4b898ead2f1fa96aee32822014ea3bc5972a4ea5827f99467af29759ca0dd",
            KlapAuth.expectedServerHash(local, remote, auth).hex(),
        )
        assertEquals(
            "e0896f2b46f78402cdefc7fe059e77c7f40678723219de13592e5a2a81c3d7ee",
            KlapAuth.clientProof(local, remote, auth).hex(),
        )
    }

    @Test
    fun encryptsARequest() {
        val cipher = KlapCipher(local, remote, auth)
        assertEquals(896059896, cipher.seq)
        val (seq, body) = cipher.encrypt("""{"method":"get_device_info"}""")
        assertEquals(896059897, seq)
        assertEquals(
            "b55d0b2c6da0b495a1839e6362af64b70e7d066ed6e54e5ae1a249f756d6ae23" +
                "d9fe8597d25c931e60c81472b70670f7e7d3260e3d94a1795bedab7bf4530f83",
            body.hex(),
        )
    }

    @Test
    fun decryptsWhatItEncrypts() {
        val cipher = KlapCipher(local, remote, auth)
        val json = """{"error_code":0,"result":{"device_on":true}}"""
        val (seq, body) = cipher.encrypt(json)
        assertEquals(json, cipher.decrypt(seq, body))
        assertArrayEquals(body, KlapCipher(local, remote, auth).encrypt(json).second)
    }

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
}
