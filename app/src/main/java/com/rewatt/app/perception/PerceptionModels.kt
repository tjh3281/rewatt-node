package com.rewatt.app.perception

import android.graphics.Bitmap

data class DetectionBox(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    fun clamped(): DetectionBox =
        DetectionBox(
            left = left.coerceIn(0f, 1f),
            top = top.coerceIn(0f, 1f),
            right = right.coerceIn(0f, 1f),
            bottom = bottom.coerceIn(0f, 1f),
        )
}

data class ObjectDetection(
    val label: String,
    val confidence: Float,
    val classId: Int,
    val box: DetectionBox,
) {
    val isPerson: Boolean = classId == 0 || label.equals("person", ignoreCase = true)
}

enum class CaptureStartSource {
    Manual,
    Auto,
}

enum class YoloModel(val displayName: String, val assetName: String) {
    Yolo26n416("YOLO26n (416)", YoloDetector.MODEL_ASSET),
}

data class ExposureReading(
    val ev: Double,
    val iso: Int,
    val exposureTimeSeconds: Double,
    val aperture: Double,
    val lightsOn: Boolean,
)

data class PerceptionUiState(
    val isRunning: Boolean = false,
    val captureStartSource: CaptureStartSource? = null,
    val cameraPermissionGranted: Boolean = false,
    val statusMessage: String = "Perception is stopped.",
    val latestFrame: Bitmap? = null,
    val latestFrameAtMillis: Long? = null,
    val yoloDetections: List<ObjectDetection> = emptyList(),
    val yoloMessage: String = "YOLO is waiting for camera frames.",
    val exposureEv: Double? = null,
    val exposureIso: Int? = null,
    val exposureTimeSeconds: Double? = null,
    val exposureAperture: Double = ExposureValueCalculator.FIXED_APERTURE,
    val lightsOn: Boolean? = null,
    val exposureMessage: String = "Exposure is waiting for camera capture.",
    val selectedYoloModel: YoloModel = YoloModel.Yolo26n416,
    /** When the camera pipeline last finished a capture (success or failure). */
    val analyzedAtMillis: Long? = null,
    /** True when that capture produced a frame AND YOLO actually ran on it (so a 0 count is real). */
    val analysisOk: Boolean = false,
    /** Live headcount: people YOLO found in the latest live-preview frame (boxes normalised to it). */
    val livePeople: List<ObjectDetection> = emptyList(),
    /** When that preview frame was analysed; null until the live headcount has run once. */
    val liveCountAtMillis: Long? = null,
    /** Smoothed live headcount (median of the last few frames), so one odd frame can't change it. */
    val liveStableCount: Int = 0,
) {
    val peopleDetections: List<ObjectDetection> = yoloDetections.filter { it.isPerson }
    val personCount: Int = peopleDetections.size

    /** People in the live preview right now (smoothed), or null when the live headcount isn't running. */
    fun liveCount(nowMillis: Long): Int? =
        liveStableCount.takeIf { liveCountAtMillis?.let { nowMillis - it <= LIVE_COUNT_STALE_MS } == true }

    companion object {
        /** A live count older than this is ignored (preview hidden, app in background, camera off). */
        const val LIVE_COUNT_STALE_MS = 10_000L
    }
}
