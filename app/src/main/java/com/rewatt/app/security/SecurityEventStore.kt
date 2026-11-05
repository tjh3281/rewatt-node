package com.rewatt.app.security

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * Motion events on disk: filesDir/security/events/<eventId>/ holding clip_<n>.mp4 parts,
 * photo_<n>.jpg face-check photos and event.json. Retention keeps the newest events within
 * [MAX_TOTAL_BYTES] and [MAX_AGE_MILLIS]; the active event is never deleted.
 */
class SecurityEventStore(context: Context) {
    private val root = File(context.applicationContext.filesDir, "security/events").apply { mkdirs() }
    private val mutableEvents = MutableStateFlow(loadAll())
    val events: StateFlow<List<SecurityEvent>> = mutableEvents.asStateFlow()

    fun eventDir(id: String): File = File(root, id)

    fun clipFile(id: String, part: Int): File = File(eventDir(id), "clip_$part.mp4")

    fun photoFile(id: String, name: String): File = File(eventDir(id), name)

    fun clipFiles(id: String): List<File> =
        eventDir(id).listFiles { f -> f.name.startsWith("clip_") && f.extension == "mp4" }.orEmpty()
            .sortedBy { it.nameWithoutExtension.substringAfter('_').toIntOrNull() ?: 0 }

    fun photoFiles(id: String): List<File> =
        eventDir(id).listFiles { f -> f.name.startsWith("photo_") && f.extension == "jpg" }.orEmpty()
            .sortedBy { it.nameWithoutExtension.substringAfter('_').toIntOrNull() ?: 0 }

    @Synchronized
    fun create(id: String, startedAtMillis: Long): SecurityEvent {
        eventDir(id).mkdirs()
        val event = SecurityEvent(
            id = id,
            startedAtMillis = startedAtMillis,
            endedAtMillis = null,
            result = EventResult.NO_PERSON,
            bestScore = null,
            torchUsed = false,
            alarmDismissedAtMillis = null,
            mqttSent = false,
            clipCount = 0,
            photoCount = 0,
            strangerPhoto = null,
            sizeBytes = 0,
        )
        write(event)
        return event
    }

    /** Saves a face-check photo (downscaled to [maxSide]) and returns its file name. */
    fun savePhoto(id: String, index: Int, bitmap: Bitmap, maxSide: Int = 1600): String {
        val name = "photo_$index.jpg"
        val scaled = bitmap.scaledToMaxSide(maxSide)
        File(eventDir(id).apply { mkdirs() }, name).outputStream().use {
            scaled.compress(Bitmap.CompressFormat.JPEG, 85, it)
        }
        if (scaled !== bitmap) scaled.recycle()
        return name
    }

    @Synchronized
    fun update(id: String, transform: (SecurityEvent) -> SecurityEvent): SecurityEvent? {
        val current = read(eventDir(id)) ?: return null
        val updated = transform(current).copy(
            clipCount = clipFiles(id).size,
            photoCount = photoFiles(id).size,
            sizeBytes = dirSize(eventDir(id)),
        )
        write(updated)
        return updated
    }

    /** Deletes the event folder (used for FAMILY and NO_PERSON events, which are not kept). */
    @Synchronized
    fun delete(id: String) {
        eventDir(id).deleteRecursively()
        refresh()
    }

    @Synchronized
    fun applyRetention(activeId: String?, nowMillis: Long = System.currentTimeMillis()) {
        val events = loadAll().sortedByDescending { it.startedAtMillis }
        var total = 0L
        for (event in events) {
            if (event.id == activeId) {
                total += event.sizeBytes
                continue
            }
            val tooOld = nowMillis - event.startedAtMillis > MAX_AGE_MILLIS
            // Cut off before it ended (app killed or reinstalled) with nothing worth keeping: like at a normal end.
            val unfinishedEmpty = event.endedAtMillis == null &&
                (event.result == EventResult.NO_PERSON || event.result == EventResult.FAMILY)
            if (tooOld || unfinishedEmpty || total + event.sizeBytes > MAX_TOTAL_BYTES) {
                Log.i(TAG, "Retention: deleting event ${event.id}")
                eventDir(event.id).deleteRecursively()
            } else {
                total += event.sizeBytes
            }
        }
        refresh()
    }

    fun totalBytes(): Long = mutableEvents.value.sumOf { it.sizeBytes }

    @Synchronized
    fun refresh() {
        mutableEvents.value = loadAll()
    }

    private fun loadAll(): List<SecurityEvent> =
        root.listFiles { f -> f.isDirectory }.orEmpty()
            .mapNotNull { read(it) }
            .sortedByDescending { it.startedAtMillis }

    private fun write(event: SecurityEvent) {
        val json = JSONObject().apply {
            put("id", event.id)
            put("startedAtMillis", event.startedAtMillis)
            put("endedAtMillis", event.endedAtMillis ?: JSONObject.NULL)
            put("result", event.result.name)
            put("bestScore", event.bestScore?.toDouble() ?: JSONObject.NULL)
            put("torchUsed", event.torchUsed)
            put("alarmDismissedAtMillis", event.alarmDismissedAtMillis ?: JSONObject.NULL)
            put("mqttSent", event.mqttSent)
            put("clipCount", event.clipCount)
            put("photoCount", event.photoCount)
            put("strangerPhoto", event.strangerPhoto ?: JSONObject.NULL)
            put("sizeBytes", event.sizeBytes)
        }
        File(eventDir(event.id).apply { mkdirs() }, EVENT_JSON).writeText(json.toString())
        mutableEvents.value = (mutableEvents.value.filterNot { it.id == event.id } + event)
            .sortedByDescending { it.startedAtMillis }
    }

    private fun read(dir: File): SecurityEvent? = runCatching {
        val json = JSONObject(File(dir, EVENT_JSON).readText())
        SecurityEvent(
            id = json.getString("id"),
            startedAtMillis = json.getLong("startedAtMillis"),
            endedAtMillis = json.optLongOrNull("endedAtMillis"),
            result = runCatching { EventResult.valueOf(json.getString("result")) }.getOrDefault(EventResult.NO_FACE),
            bestScore = if (json.isNull("bestScore")) null else json.getDouble("bestScore").toFloat(),
            torchUsed = json.optBoolean("torchUsed"),
            alarmDismissedAtMillis = json.optLongOrNull("alarmDismissedAtMillis"),
            mqttSent = json.optBoolean("mqttSent"),
            clipCount = json.optInt("clipCount"),
            photoCount = json.optInt("photoCount"),
            strangerPhoto = if (json.isNull("strangerPhoto")) null else json.optString("strangerPhoto"),
            sizeBytes = dirSize(dir),
        )
    }.getOrNull()

    private fun dirSize(dir: File): Long = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    private fun JSONObject.optLongOrNull(key: String): Long? = if (isNull(key)) null else optLong(key)

    companion object {
        private const val TAG = "SecurityEventStore"
        private const val EVENT_JSON = "event.json"
        const val MAX_TOTAL_BYTES = 2L * 1024 * 1024 * 1024
        const val MAX_AGE_MILLIS = 7L * 24 * 60 * 60 * 1000
    }
}

internal fun Bitmap.scaledToMaxSide(maxSide: Int): Bitmap {
    val longest = maxOf(width, height)
    if (longest <= maxSide) return this
    val scale = maxSide.toFloat() / longest
    return Bitmap.createScaledBitmap(this, (width * scale).toInt().coerceAtLeast(1), (height * scale).toInt().coerceAtLeast(1), true)
}
