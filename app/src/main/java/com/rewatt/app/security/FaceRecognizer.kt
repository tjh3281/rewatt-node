package com.rewatt.app.security

import ai.onnxruntime.OrtEnvironment
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.util.Log
import com.rewatt.app.perception.AssetModelLoader

/**
 * InsightFace buffalo_sc pipeline: SCRFD detect → 5-point align → MobileFaceNet embed → best cosine
 * similarity against every registered face sample (the closest sample's person is the match).
 * Models load lazily on first use and are shared by the CCTV service and the registration screen.
 *
 * The live check calls [detect] on every frame and [recognize] only for faces [FaceTracker] asks
 * about; [analyze] does both for a single still photo.
 */
class FaceRecognizer(
    private val context: Context,
    private val familyStore: FamilyFaceStore,
) {
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private var detector: ScrfdFaceDetector? = null
    private var embedder: MobileFaceNetEmbedder? = null

    sealed interface RegistrationResult {
        class Ok(val embedding: FloatArray, val thumbnail: Bitmap) : RegistrationResult
        data object NoFace : RegistrationResult
        data object MultipleFaces : RegistrationResult
        data object TooSmall : RegistrationResult
    }

    /** One face drawn on an evidence photo; [isFamily] picks green, otherwise red. */
    data class FaceLabel(val box: RectF, val isFamily: Boolean, val text: String)

    @Synchronized
    fun warmUp(): Boolean = runCatching { ensureLoaded() }
        .onFailure { Log.e(TAG, "Face models failed to load", it) }
        .isSuccess

    /** Usable faces in [bitmap]; faces smaller than [MIN_FACE_PX] are ignored (too small to recognise). */
    @Synchronized
    fun detect(bitmap: Bitmap): List<DetectedFace> {
        ensureLoaded()
        return detector!!.detect(bitmap).filter { it.isUsable() }
    }

    /** Aligns, embeds and matches one [face] of [bitmap] against every registered sample. */
    @Synchronized
    fun recognize(bitmap: Bitmap, face: DetectedFace, threshold: Float): FaceMatch {
        ensureLoaded()
        val embedding = embedFace(bitmap, face)
        val best = bestSampleMatch(embedding, familyStore.samples.value)
        val similarity = best?.second ?: 0f
        val confidence = MatchCalibration.confidence(similarity, threshold)
        val personId = best?.first?.personId
        return FaceMatch(
            personId = personId,
            name = personId?.let { familyStore.nameOf(it) },
            similarity = similarity,
            confidence = confidence,
            isFamily = best != null && confidence >= MatchCalibration.PASS_CONFIDENCE,
        )
    }

    /** Face check for a CCTV photo. */
    @Synchronized
    fun analyze(bitmap: Bitmap, threshold: Float): FaceCheckResult {
        val started = SystemClock.elapsedRealtime()
        val faces = detect(bitmap)
        if (faces.isEmpty()) return FaceCheckResult.NoFace

        val recognized = faces.map { face ->
            val match = recognize(bitmap, face, threshold)
            RecognizedFace(
                face = face,
                bestSimilarity = match.similarity,
                confidence = match.confidence,
                isFamily = match.isFamily,
                name = match.name,
            )
        }
        Log.i(
            TAG,
            "Face check: ${recognized.size} face(s), " +
                "cosine/C(s)=${recognized.map { "%.2f/%.0f%%".format(it.bestSimilarity, it.confidence * 100) }}, " +
                "s0=%.3f k=%.2f, ${SystemClock.elapsedRealtime() - started} ms".format(MatchCalibration.s0For(threshold), MatchCalibration.K),
        )
        return if (recognized.all { it.isFamily }) {
            FaceCheckResult.AllFamily(recognized)
        } else {
            FaceCheckResult.Stranger(recognized)
        }
    }

    /** Registration: the photo must contain exactly one usable face. */
    @Synchronized
    fun extractForRegistration(bitmap: Bitmap): RegistrationResult {
        ensureLoaded()
        val all = detector!!.detect(bitmap)
        if (all.isEmpty()) return RegistrationResult.NoFace
        if (all.size > 1) return RegistrationResult.MultipleFaces
        val face = all.first()
        if (!face.isUsable()) return RegistrationResult.TooSmall
        val aligned = FaceAligner.align(bitmap, face.keypoints)
        return RegistrationResult.Ok(embedder!!.embed(aligned), aligned)
    }

    /** Draws face boxes (green = registered, red = unknown/masked) on a copy scaled to [maxSide]. */
    fun annotate(bitmap: Bitmap, faces: List<RecognizedFace>, maxSide: Int = 1600): Bitmap =
        annotateLabels(
            bitmap,
            faces.map { face ->
                val who = if (face.isFamily) face.name ?: FamilyFaceStore.FAMILY_LABEL else "UNKNOWN"
                FaceLabel(face.face.box, face.isFamily, "$who %.2f · %.0f%%".format(face.bestSimilarity, face.confidence * 100))
            },
            maxSide,
        )

    fun annotateLabels(bitmap: Bitmap, faces: List<FaceLabel>, maxSide: Int = 1600): Bitmap {
        val scaled = bitmap.scaledToMaxSide(maxSide)
        val out = if (scaled === bitmap) bitmap.copy(Bitmap.Config.ARGB_8888, true) else scaled
        val k = out.width.toFloat() / bitmap.width
        val canvas = Canvas(out)
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = (out.width / 250f).coerceAtLeast(3f)
        }
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = (out.width / 40f).coerceAtLeast(18f)
            isFakeBoldText = true
        }
        for (face in faces) {
            val color = if (face.isFamily) Color.rgb(22, 163, 74) else Color.rgb(220, 38, 38)
            stroke.color = color
            text.color = color
            val r = RectF(face.box.left * k, face.box.top * k, face.box.right * k, face.box.bottom * k)
            canvas.drawRect(r, stroke)
            canvas.drawText(face.text, r.left, (r.top - text.textSize / 3f).coerceAtLeast(text.textSize), text)
        }
        return out
    }

    /** The face's embedding (512 numbers, L2-normalised), e.g. to keep an intruder's for a police report. */
    fun embedding(bitmap: Bitmap, face: DetectedFace): FloatArray {
        ensureLoaded()
        return embedFace(bitmap, face)
    }

    private fun embedFace(bitmap: Bitmap, face: DetectedFace): FloatArray {
        val aligned = FaceAligner.align(bitmap, face.keypoints)
        return embedder!!.embed(aligned).also { aligned.recycle() }
    }

    private fun DetectedFace.isUsable(): Boolean =
        box.width() >= MIN_FACE_PX && box.height() >= MIN_FACE_PX

    private fun ensureLoaded() {
        if (detector != null && embedder != null) return
        val started = SystemClock.elapsedRealtime()
        val detectorThreads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
        detector = ScrfdFaceDetector(env, AssetModelLoader.loadBytes(context, DETECTOR_ASSET), threads = detectorThreads)
        embedder = MobileFaceNetEmbedder(env, AssetModelLoader.loadBytes(context, EMBEDDER_ASSET))
        Log.i(TAG, "buffalo_sc loaded in ${SystemClock.elapsedRealtime() - started} ms (detector threads=$detectorThreads)")
    }

    companion object {
        private const val TAG = "FaceRecognizer"
        const val DETECTOR_ASSET = "face/det_500m.onnx"
        const val EMBEDDER_ASSET = "face/w600k_mbf.onnx"

        /**
         * Faces narrower than this are too small to recognise reliably. Applies to the ~2000 px still
         * and the ~1280 px live frame alike (a 48 px face is the same face size either way).
         */
        const val MIN_FACE_PX = 48f
    }
}
