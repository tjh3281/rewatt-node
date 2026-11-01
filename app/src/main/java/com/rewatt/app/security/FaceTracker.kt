package com.rewatt.app.security

import kotlin.math.max
import kotlin.math.min

/** Status of a tracked live face. */
enum class TrackStatus { PENDING, FAMILY, UNKNOWN }

/** Face box in frame pixels. Plain floats (not RectF) so the tracker runs in JVM unit tests. */
data class FaceBox(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val area: Float get() = max(0f, width) * max(0f, height)

    fun iou(other: FaceBox): Float {
        val inter = max(0f, min(right, other.right) - max(left, other.left)) *
            max(0f, min(bottom, other.bottom) - max(top, other.top))
        val union = area + other.area - inter
        return if (union <= 0f) 0f else inter / union
    }
}

/** One recognition of a tracked face (see [FaceRecognizer.recognize]). */
data class TrackCheck(val isFamily: Boolean, val similarity: Float, val confidence: Float, val name: String?)

data class TrackedFace(
    val trackId: Int,
    val box: FaceBox,
    val status: TrackStatus,
    val name: String?,
    val similarity: Float?,
    val confidence: Float?,
)

/** Result of one frame: every live track, the ones that just became UNKNOWN / FAMILY, and how many were recognised. */
data class TrackStep(
    val faces: List<TrackedFace>,
    val newlyUnknown: List<TrackedFace>,
    val newlyFamily: List<TrackedFace>,
    val recognitions: Int,
)

/**
 * Follows faces across live frames so the (comparatively slow) recogniser only runs when it has to.
 *
 * The detector runs on every frame. Each detected box is matched to last frame's tracks by overlap
 * (IoU ≥ [MATCH_IOU]); at ~10 fps a face moves very little between frames, so a strong overlap means
 * "the same face". Recognition then runs only for:
 *  - a box with no matching track (a new face),
 *  - a PENDING track every [PENDING_RECHECK_MS] until it is decided,
 *  - a decided track every [FAMILY_RECHECK_MS] / [UNKNOWN_RECHECK_MS],
 *  - a track whose box suddenly changes size (two faces may have crossed and swapped tracks).
 *
 * A track becomes FAMILY on the first passing check. It becomes UNKNOWN only after at least
 * [UNKNOWN_MIN_CHECKS] failed checks spanning [UNKNOWN_MIN_MS], so one blurry or side-on frame never
 * sounds the alarm. A FAMILY track stays FAMILY on a failed periodic re-check; only a failed check after
 * a size jump sends it back to PENDING. Tracks unseen for [LOST_MS] are dropped.
 */
class FaceTracker {
    private class Track(
        val id: Int,
        var box: FaceBox,
        var lastSeenAt: Long,
    ) {
        var status = TrackStatus.PENDING
        var name: String? = null
        var similarity: Float? = null
        var confidence: Float? = null
        var lastCheckAt: Long? = null
        var checkedBox: FaceBox? = null
        var failedChecks = 0
        var firstFailAt: Long? = null

        fun view() = TrackedFace(id, box, status, name, similarity, confidence)
    }

    private val tracks = ArrayList<Track>()
    private var nextId = 1

    val activeCount: Int get() = tracks.size

    fun reset() {
        tracks.clear()
    }

    /**
     * Matches this frame's [boxes] to tracks and runs [recognize] (with the box index) for the faces that
     * need it. [now] is in milliseconds.
     */
    fun step(boxes: List<FaceBox>, now: Long, recognize: (Int) -> TrackCheck?): TrackStep {
        val matchedTrack = arrayOfNulls<Track>(boxes.size)
        val taken = HashSet<Track>()
        val pairs = ArrayList<Triple<Int, Track, Float>>()
        for ((i, box) in boxes.withIndex()) {
            for (track in tracks) {
                val iou = box.iou(track.box)
                if (iou >= MATCH_IOU) pairs += Triple(i, track, iou)
            }
        }
        for ((i, track, _) in pairs.sortedByDescending { it.third }) {
            if (matchedTrack[i] != null || track in taken) continue
            matchedTrack[i] = track
            taken += track
        }

        val newlyUnknown = ArrayList<TrackedFace>()
        val newlyFamily = ArrayList<TrackedFace>()
        var recognitions = 0
        for ((i, box) in boxes.withIndex()) {
            val track = matchedTrack[i] ?: Track(nextId++, box, now).also { tracks += it }
            track.box = box
            track.lastSeenAt = now
            val sizeJump = track.checkedBox?.let { sizeJumped(it, box) } == true
            if (!needsCheck(track, now, sizeJump)) continue

            val check = recognize(i) ?: continue
            recognitions++
            val before = track.status
            apply(track, check, now, sizeJump)
            if (track.status != before) {
                when (track.status) {
                    TrackStatus.UNKNOWN -> newlyUnknown += track.view()
                    TrackStatus.FAMILY -> newlyFamily += track.view()
                    TrackStatus.PENDING -> Unit
                }
            }
        }

        tracks.removeAll { now - it.lastSeenAt > LOST_MS }
        return TrackStep(tracks.map { it.view() }, newlyUnknown, newlyFamily, recognitions)
    }

    private fun needsCheck(track: Track, now: Long, sizeJump: Boolean): Boolean {
        val last = track.lastCheckAt ?: return true
        if (sizeJump) return true
        val interval = when (track.status) {
            TrackStatus.PENDING -> PENDING_RECHECK_MS
            TrackStatus.FAMILY -> FAMILY_RECHECK_MS
            TrackStatus.UNKNOWN -> UNKNOWN_RECHECK_MS
        }
        return now - last >= interval
    }

    private fun apply(track: Track, check: TrackCheck, now: Long, sizeJump: Boolean) {
        track.lastCheckAt = now
        track.checkedBox = track.box
        if (check.isFamily) {
            track.status = TrackStatus.FAMILY
            track.name = check.name
            track.similarity = check.similarity
            track.confidence = check.confidence
            track.failedChecks = 0
            track.firstFailAt = null
            return
        }
        if (track.status == TrackStatus.FAMILY && !sizeJump) return // locked: one bad angle is not a stranger
        if (track.status == TrackStatus.FAMILY) {
            track.status = TrackStatus.PENDING
            track.name = null
            track.failedChecks = 0
            track.firstFailAt = null
        }
        track.similarity = check.similarity
        track.confidence = check.confidence
        track.failedChecks++
        val firstFail = track.firstFailAt ?: now.also { track.firstFailAt = it }
        if (track.status == TrackStatus.PENDING &&
            track.failedChecks >= UNKNOWN_MIN_CHECKS &&
            now - firstFail >= UNKNOWN_MIN_MS
        ) {
            track.status = TrackStatus.UNKNOWN
        }
    }

    private fun sizeJumped(previous: FaceBox, current: FaceBox): Boolean {
        if (previous.area <= 0f || current.area <= 0f) return false
        val ratio = current.area / previous.area
        return ratio > SIZE_JUMP_RATIO || ratio < 1f / SIZE_JUMP_RATIO
    }

    companion object {
        const val MATCH_IOU = 0.3f
        const val LOST_MS = 1_000L
        // At ~10 fps this re-checks every other frame: checks at 0, 200 … 1000 ms → UNKNOWN at ~1 s.
        const val PENDING_RECHECK_MS = 200L
        const val FAMILY_RECHECK_MS = 3_000L
        const val UNKNOWN_RECHECK_MS = 1_000L
        const val UNKNOWN_MIN_CHECKS = 3
        const val UNKNOWN_MIN_MS = 1_000L

        /** Box area change (either way) since the last check that forces a re-check. */
        const val SIZE_JUMP_RATIO = 2.25f
    }
}
