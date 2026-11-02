package com.rewatt.app.security

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import java.io.Closeable
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * InsightFace buffalo_s face detector (SCRFD det_500m, ONNX).
 *
 * Pre-processing matches insightface's `SCRFD.detect`: the image is resized to fit [inputSize]
 * keeping aspect ratio, placed top-left on a black canvas, RGB, `(x - 127.5) / 128`.
 * The 9 outputs (score / bbox / 5 keypoints for strides 8, 16, 32 with 2 anchors each) are
 * identified by their shapes rather than their order, so export differences don't matter.
 */
class ScrfdFaceDetector(
    private val env: OrtEnvironment,
    modelBytes: ByteArray,
    private val inputSize: Int = DEFAULT_INPUT_SIZE,
    threads: Int = 2,
) : Closeable {
    private val session: OrtSession = env.createSession(
        modelBytes,
        OrtSession.SessionOptions().apply { setIntraOpNumThreads(threads) },
    )
    private val inputName: String = session.inputNames.first()
    private var loggedShapes = false

    // Reused between calls (the live check runs ~10×/s; callers serialise detect()).
    private val data = FloatArray(3 * inputSize * inputSize)
    private var pixels = IntArray(0)

    init {
        require(inputSize % 32 == 0) { "SCRFD input size must be a multiple of 32" }
    }

    fun detect(bitmap: Bitmap, scoreThreshold: Float = SCORE_THRESHOLD): List<DetectedFace> {
        val scale = min(inputSize.toFloat() / bitmap.width, inputSize.toFloat() / bitmap.height)
        val newW = (bitmap.width * scale).roundToInt().coerceIn(1, inputSize)
        val newH = (bitmap.height * scale).roundToInt().coerceIn(1, inputSize)
        val resized = Bitmap.createScaledBitmap(bitmap, newW, newH, true)
        if (pixels.size < newW * newH) pixels = IntArray(newW * newH)
        resized.getPixels(pixels, 0, newW, 0, 0, newW, newH)
        if (resized !== bitmap) resized.recycle()

        val area = inputSize * inputSize
        data.fill(PAD_VALUE)
        for (y in 0 until newH) {
            for (x in 0 until newW) {
                val p = pixels[y * newW + x]
                val idx = y * inputSize + x
                data[idx] = (((p shr 16) and 0xFF) - MEAN) / STD
                data[area + idx] = (((p shr 8) and 0xFF) - MEAN) / STD
                data[2 * area + idx] = ((p and 0xFF) - MEAN) / STD
            }
        }

        val scores = HashMap<Int, FloatArray>()
        val boxes = HashMap<Int, FloatArray>()
        val kps = HashMap<Int, FloatArray>()
        OnnxTensor.createTensor(env, FloatBuffer.wrap(data), longArrayOf(1, 3, inputSize.toLong(), inputSize.toLong())).use { input ->
            session.run(mapOf(inputName to input)).use { outputs ->
                for (entry in outputs) {
                    val tensor = entry.value as? OnnxTensor ?: continue
                    val shape = tensor.info.shape
                    val cols = shape.last().toInt()
                    val buffer = tensor.floatBuffer
                    val values = FloatArray(buffer.remaining()).also { buffer.get(it) }
                    if (!loggedShapes) Log.i(TAG, "SCRFD output ${entry.key} shape=${shape.contentToString()}")
                    val stride = strideForRows(values.size / max(cols, 1)) ?: continue
                    when (cols) {
                        1 -> scores[stride] = values
                        4 -> boxes[stride] = values
                        10 -> kps[stride] = values
                    }
                }
            }
        }
        loggedShapes = true

        val candidates = ArrayList<DetectedFace>()
        for (stride in STRIDES) {
            val s = scores[stride] ?: continue
            val b = boxes[stride] ?: continue
            val k = kps[stride]
            val gridW = inputSize / stride
            for (i in s.indices) {
                val score = s[i]
                if (score < scoreThreshold) continue
                val cell = i / NUM_ANCHORS
                val cx = (cell % gridW) * stride.toFloat()
                val cy = (cell / gridW) * stride.toFloat()
                val box = RectF(
                    (cx - b[i * 4] * stride) / scale,
                    (cy - b[i * 4 + 1] * stride) / scale,
                    (cx + b[i * 4 + 2] * stride) / scale,
                    (cy + b[i * 4 + 3] * stride) / scale,
                )
                val points = FloatArray(10)
                if (k != null) {
                    for (j in 0 until 5) {
                        points[j * 2] = (cx + k[i * 10 + j * 2] * stride) / scale
                        points[j * 2 + 1] = (cy + k[i * 10 + j * 2 + 1] * stride) / scale
                    }
                }
                box.intersect(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat())
                candidates += DetectedFace(box = box, keypoints = points, score = score)
            }
        }
        return nms(candidates)
    }

    private fun strideForRows(rows: Int): Int? =
        STRIDES.firstOrNull { stride -> (inputSize / stride).let { it * it * NUM_ANCHORS } == rows }

    private fun nms(faces: List<DetectedFace>): List<DetectedFace> {
        val sorted = faces.sortedByDescending { it.score }
        val kept = ArrayList<DetectedFace>()
        for (face in sorted) {
            if (kept.none { iou(it.box, face.box) > NMS_IOU }) kept += face
        }
        return kept
    }

    private fun iou(a: RectF, b: RectF): Float {
        val left = max(a.left, b.left)
        val top = max(a.top, b.top)
        val right = min(a.right, b.right)
        val bottom = min(a.bottom, b.bottom)
        val inter = max(0f, right - left) * max(0f, bottom - top)
        val union = a.width() * a.height() + b.width() * b.height() - inter
        return if (union <= 0f) 0f else inter / union
    }

    override fun close() {
        session.close()
    }

    companion object {
        private const val TAG = "ScrfdFaceDetector"
        // buffalo_s det_500m declares a dynamic input but its outputs are fixed at 12800/3200/800
        // rows (internal reshapes), i.e. it only runs at 640×640 — any other size throws.
        const val DEFAULT_INPUT_SIZE = 640
        const val SCORE_THRESHOLD = 0.5f
        private const val NMS_IOU = 0.4f
        private const val NUM_ANCHORS = 2
        private val STRIDES = intArrayOf(8, 16, 32)
        private const val MEAN = 127.5f
        private const val STD = 128f
        private const val PAD_VALUE = -MEAN / STD
    }
}
