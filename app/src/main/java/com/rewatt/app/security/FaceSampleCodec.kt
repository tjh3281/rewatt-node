package com.rewatt.app.security

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

/**
 * `.emb` file format for one registered face sample.
 *
 * v1: int version=1, long createdAt, int size, float[size] embedding (no person — the old anonymous FAMILY).
 * v2: int version=2, UTF personId, long createdAt, int size, float[size] embedding.
 */
object FaceSampleCodec {
    const val VERSION = 2

    /** [personId] is null for v1 samples, which predate named people. */
    data class Decoded(val personId: String?, val createdAtMillis: Long, val embedding: FloatArray)

    fun write(out: DataOutputStream, personId: String, createdAtMillis: Long, embedding: FloatArray) {
        out.writeInt(VERSION)
        out.writeUTF(personId)
        out.writeLong(createdAtMillis)
        out.writeInt(embedding.size)
        embedding.forEach(out::writeFloat)
    }

    fun read(input: DataInputStream): Decoded {
        val version = input.readInt()
        val personId = when (version) {
            1 -> null
            2 -> input.readUTF()
            else -> throw IOException("Unsupported face sample version $version")
        }
        val createdAt = input.readLong()
        val size = input.readInt()
        if (size !in 1..MAX_EMBEDDING) throw IOException("Bad embedding size $size")
        return Decoded(personId, createdAt, FloatArray(size) { input.readFloat() })
    }

    private const val MAX_EMBEDDING = 4096
}

/** Best match of [embedding] over every sample; the person of the closest sample wins. */
internal fun bestSampleMatch(embedding: FloatArray, samples: List<FaceSample>): Pair<FaceSample, Float>? =
    samples.map { it to cosineSimilarity(embedding, it.embedding) }.maxByOrNull { it.second }
