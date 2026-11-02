package com.rewatt.app.security

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FaceSampleCodecTest {
    private val embedding = floatArrayOf(0.6f, -0.8f, 0f)

    private fun decode(bytes: ByteArray) = DataInputStream(ByteArrayInputStream(bytes)).use { FaceSampleCodec.read(it) }

    @Test
    fun read_roundTripsV2WithPerson() {
        val bytes = ByteArrayOutputStream().also { out ->
            DataOutputStream(out).use { FaceSampleCodec.write(it, "p1", 1234L, embedding) }
        }.toByteArray()

        val decoded = decode(bytes)

        assertEquals("p1", decoded.personId)
        assertEquals(1234L, decoded.createdAtMillis)
        assertArrayEquals(embedding, decoded.embedding, 0f)
    }

    @Test
    fun read_v1FileHasNoPerson() {
        // Format written by the app before named people.
        val bytes = ByteArrayOutputStream().also { out ->
            DataOutputStream(out).use {
                it.writeInt(1)
                it.writeLong(99L)
                it.writeInt(embedding.size)
                embedding.forEach(it::writeFloat)
            }
        }.toByteArray()

        val decoded = decode(bytes)

        assertNull(decoded.personId)
        assertEquals(99L, decoded.createdAtMillis)
        assertArrayEquals(embedding, decoded.embedding, 0f)
    }

    @Test(expected = IOException::class)
    fun read_rejectsUnknownVersion() {
        val bytes = ByteArrayOutputStream().also { out -> DataOutputStream(out).use { it.writeInt(7) } }.toByteArray()
        decode(bytes)
    }

    @Test
    fun bestSampleMatch_picksPersonOfClosestSample() {
        val samples = listOf(
            FaceSample("a1", "alice", 0L, floatArrayOf(1f, 0f), ""),
            FaceSample("b1", "bob", 0L, floatArrayOf(0f, 1f), ""),
            FaceSample("b2", "bob", 0L, floatArrayOf(0.6f, 0.8f), ""),
        )

        val best = bestSampleMatch(floatArrayOf(0.5f, 0.866f), samples)!!

        assertEquals("bob", best.first.personId)
        assertEquals(0.5f * 0.6f + 0.866f * 0.8f, best.second, 1e-5f)
    }

    @Test
    fun bestSampleMatch_noSamplesIsNull() {
        assertNull(bestSampleMatch(floatArrayOf(1f, 0f), emptyList()))
    }
}
