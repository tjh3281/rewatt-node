package com.rewatt.app.security

import android.graphics.Bitmap
import android.graphics.RectF

/** Motion event phases, mirroring the old PIR project's IDLE → MOTION → GRACE state machine. */
enum class EventPhase { IDLE, MOTION, GRACE }

/**
 * Verdict of a check / motion event (Lane 2 flow):
 * NO_PERSON → ignored, nothing recorded; NO_FACE → person seen, clip kept as evidence;
 * FAMILY → silent, clip deleted; STRANGER (unknown or masked face) → alert, clip kept.
 */
enum class EventResult { NO_PERSON, NO_FACE, FAMILY, STRANGER }

/** One face found in a photo, in full-image pixel coordinates. */
data class DetectedFace(
    val box: RectF,
    val keypoints: FloatArray, // 5 points: x0,y0,x1,y1,...  (eyes, nose, mouth corners)
    val score: Float,
)

/** A face compared against the FAMILY store. */
data class RecognizedFace(
    val face: DetectedFace,
    val bestSimilarity: Float,
    /** Calibrated C(s), see [MatchCalibration]. */
    val confidence: Float,
    val isFamily: Boolean,
    /** Registered person with the best match (shown only when [isFamily]). */
    val name: String? = null,
)

/** One face's best match against every registered sample. */
data class FaceMatch(
    val personId: String?,
    val name: String?,
    val similarity: Float,
    val confidence: Float,
    val isFamily: Boolean,
)

sealed interface FaceCheckResult {
    /** No usable (large enough) face in the photo. Ignored by design. */
    data object NoFace : FaceCheckResult

    data class AllFamily(val faces: List<RecognizedFace>) : FaceCheckResult {
        private val best: RecognizedFace get() = faces.maxBy { it.bestSimilarity }
        val bestScore: Float get() = best.bestSimilarity
        val confidence: Float get() = best.confidence
    }

    /** At least one face did not match FAMILY. Scores are the strangest face's best match. */
    data class Stranger(val faces: List<RecognizedFace>) : FaceCheckResult {
        private val strangest: RecognizedFace get() = faces.filterNot { it.isFamily }.minBy { it.bestSimilarity }
        val bestScore: Float get() = strangest.bestSimilarity
        val confidence: Float get() = strangest.confidence
    }
}

/** One upright frame of the live security stream (~1280×960); [meanLuma] 0–255 stands in for EXIF EV. */
class LiveFrame(
    val bitmap: Bitmap,
    val meanLuma: Float,
    val capturedAtMillis: Long,
)

/** A tracked live face for the camera overlay; box normalised (0..1) to the live frame. */
data class LiveFaceUi(
    val trackId: Int,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val status: TrackStatus,
    val name: String?,
    val confidence: Float?,
)

/** A still photo captured for the security face check (never used for headcount/illuminance). */
class SecurityFrame(
    val bitmap: Bitmap,
    val ev: Double?,
    val lightsOn: Boolean?,
    val capturedAtMillis: Long,
)

data class LastFaceCheck(
    val atMillis: Long,
    val result: EventResult,
    val score: Float?,
    val confidence: Float? = null,
)

/** Result of the manual "Test CCTV check" (one photo through the full pipeline, no alarm, not saved). */
data class TestCheckState(
    val countdown: Int = 0,
    val running: Boolean = false,
    val atMillis: Long? = null,
    val result: EventResult? = null,
    val score: Float? = null,
    val confidence: Float? = null,
    val personCount: Int = 0,
    val faceCount: Int = 0,
    val photoPath: String? = null,
    val errorRes: Int? = null,
)

data class SecurityUiState(
    val testCheck: TestCheckState = TestCheckState(),
    val armed: Boolean = false,
    val phase: EventPhase = EventPhase.IDLE,
    val activeEventId: String? = null,
    val recording: Boolean = false,
    val torchOn: Boolean = false,
    val lastCheck: LastFaceCheck? = null,
    val alarmActive: Boolean = false,
    val alarmEventId: String? = null,
    /** Stranger photo / match score of the ringing alarm (shown on the in-app alert page). */
    val alarmPhotoName: String? = null,
    val alarmScore: Float? = null,
    val statusMessage: String? = null,
    /** Live face check: true while the radar-woken stream runs, with its tracked faces and speed. */
    val liveActive: Boolean = false,
    val liveFaces: List<LiveFaceUi> = emptyList(),
    val liveFps: Float? = null,
    /** Live frame size (upright), so the overlay can map boxes onto the FILL_CENTER preview. */
    val liveFrameWidth: Int = 0,
    val liveFrameHeight: Int = 0,
)

/** Persisted summary of one motion event (events/<id>/event.json). */
data class SecurityEvent(
    val id: String,
    val startedAtMillis: Long,
    val endedAtMillis: Long?,
    val result: EventResult,
    val bestScore: Float?,
    val torchUsed: Boolean,
    val alarmDismissedAtMillis: Long?,
    val mqttSent: Boolean,
    val clipCount: Int,
    val photoCount: Int,
    val strangerPhoto: String?,
    val sizeBytes: Long,
)

/** A registered person; their face samples point at [id]. */
data class Person(
    val id: String,
    val name: String,
    val createdAtMillis: Long,
)

/** One registered face sample of a [Person]. */
data class FaceSample(
    val id: String,
    val personId: String,
    val createdAtMillis: Long,
    val embedding: FloatArray,
    val thumbnailPath: String,
)
