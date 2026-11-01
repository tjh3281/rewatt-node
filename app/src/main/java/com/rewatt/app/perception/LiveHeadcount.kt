package com.rewatt.app.perception

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Live headcount on the camera preview: YOLO runs on the frame the preview is showing (one pass, no
 * tiling) and the result goes to [PerceptionStateHolder] as [PerceptionUiState.livePeople]. Frames
 * come from the on-screen PreviewView, so this needs no extra camera stream and only runs while the
 * live view is visible. The YOLO model is the one CameraCaptureService loaded, shared with the CCTV
 * person check (its calls are serialised).
 *
 * Pace: up to [TARGET_FPS] counts a second. A phone that can't keep up slows down by itself — after
 * each count it rests at least half as long as the count took, so old phones don't run hot.
 *
 * Stability: only confident detections count ([MIN_CONFIDENCE]), and the number shown is the median
 * of the counts from the last [WINDOW_MS] (at least [MIN_SAMPLES] frames), so a single blurry frame
 * or a borderline detection can't make it jump.
 */
class LiveHeadcount(
    private val detectorProvider: () -> YoloDetector?,
    private val stateHolder: PerceptionStateHolder,
) {
    /** (time, raw count) of recent frames, oldest first. */
    private val recentCounts = ArrayDeque<Pair<Long, Int>>()

    /**
     * Count the people in [frame] (recycled afterwards), then wait until the next frame is due. Does
     * nothing but wait until YOLO has loaded. Called again in a loop by the live preview.
     */
    suspend fun analyze(frame: Bitmap) {
        val detector = detectorProvider()
        if (detector == null) {
            frame.recycle()
            delay(IDLE_POLL_MS)
            return
        }
        val startedAt = SystemClock.elapsedRealtime()
        val people = withContext(Dispatchers.Default) {
            try {
                runCatching { detector.detect(frame, gridSize = 1) }.getOrNull()
            } finally {
                frame.recycle()
            }
        }
        if (people == null) {
            delay(IDLE_POLL_MS)
            return
        }
        val inferenceMs = SystemClock.elapsedRealtime() - startedAt
        val confident = people.filter { it.confidence >= MIN_CONFIDENCE }
        val stable = smoothed(startedAt, confident.size)
        Log.d(TAG, "live count=${confident.size} stable=$stable in $inferenceMs ms")
        stateHolder.update {
            it.copy(livePeople = confident, liveStableCount = stable, liveCountAtMillis = System.currentTimeMillis())
        }
        val cycleMs = maxOf(1000L / TARGET_FPS, inferenceMs * 3 / 2)
        delay(cycleMs - inferenceMs)
    }

    /** Median of the recent raw counts (the upper middle value when there's an even number). */
    private fun smoothed(atMillis: Long, count: Int): Int {
        recentCounts.addLast(atMillis to count)
        while (recentCounts.size > MIN_SAMPLES && atMillis - recentCounts.first().first > WINDOW_MS) {
            recentCounts.removeFirst()
        }
        val sorted = recentCounts.map { it.second }.sorted()
        return sorted[sorted.size / 2]
    }

    companion object {
        private const val TAG = "LiveHeadcount"

        /** Most counts per second (when the phone is fast enough). */
        private const val TARGET_FPS = 10

        /** Wait while YOLO is still loading or a frame couldn't be read. */
        const val IDLE_POLL_MS = 500L

        /** The median covers the counts from about the last second… */
        private const val WINDOW_MS = 1_000L

        /** …but never fewer than this many frames (slow phones count less than once a second). */
        private const val MIN_SAMPLES = 3

        /** Only people YOLO is at least this sure of are counted (the model's own cut-off is 0.35). */
        private const val MIN_CONFIDENCE = 0.5f
    }
}
