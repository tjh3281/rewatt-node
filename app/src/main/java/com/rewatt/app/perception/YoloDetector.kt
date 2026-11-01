package com.rewatt.app.perception

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import java.io.Closeable
import java.nio.FloatBuffer

/**
 * YOLO person detector on ONNX Runtime (the same runtime as the face models). The model is the stock
 * Ultralytics YOLO26n (COCO) exported end-to-end (NMS-free) at 416 px:
 *  - input  `[1, 3, 416, 416]`: RGB, 0..1, channels first;
 *  - output `[1, 300, 6]`: x1, y1, x2, y2 (input pixels), confidence, class — see [YoloPostProcessor].
 */
class YoloDetector private constructor(
    private val env: OrtEnvironment,
    private val session: OrtSession,
    private val labels: List<String>,
) : Closeable {
    private val inputName: String = session.inputNames.first()

    // The model's square input size (e.g. 640), read from its input tensor [1, 3, H, W].
    val inputSize: Int =
        (session.inputInfo.getValue(inputName).info as? TensorInfo)?.shape?.getOrNull(2)?.toInt()
            ?.takeIf { it > 0 } ?: YoloPostProcessor.INPUT_SIZE

    // Synchronized: shared by the live headcount, the ENERGY photo and the CCTV person check. Calls are
    // only serialised; results are unchanged.
    @Synchronized
    fun detect(bitmap: Bitmap, gridSize: Int = DEFAULT_GRID): List<ObjectDetection> {
        val fullWidth = bitmap.width
        val fullHeight = bitmap.height

        // Full frame + grid cells (SAHI-style), each letterboxed to the model's square input.
        val merged = regionsFor(fullWidth, fullHeight, inputSize, gridSize).flatMap { region ->
            val crop = if (region.isFullFrame && region.width == fullWidth && region.height == fullHeight) {
                bitmap
            } else {
                Bitmap.createBitmap(bitmap, region.left, region.top, region.width, region.height)
            }
            val letterboxed = letterbox(crop, inputSize)
            try {
                runOnSquare(letterboxed.bitmap).map { detection ->
                    detection.copy(
                        box = remapToFullImage(detection.box, letterboxed, region, fullWidth, fullHeight, inputSize),
                    )
                }
            } finally {
                letterboxed.bitmap.recycle()
                if (crop !== bitmap) crop.recycle()
            }
        }

        // De-duplicate across the full frame + overlapping cells before the label filter.
        return LectureHallPerceptionFilter.filterYoloDetections(nms(merged))
    }

    /** Run the model on an already-square (inputSize) bitmap; boxes are normalized to it. */
    private fun runOnSquare(square: Bitmap): List<ObjectDetection> {
        val shape = longArrayOf(1, 3, inputSize.toLong(), inputSize.toLong())
        OnnxTensor.createTensor(env, FloatBuffer.wrap(square.toChwFloats(inputSize)), shape).use { input ->
            session.run(mapOf(inputName to input)).use { outputs ->
                @Suppress("UNCHECKED_CAST")
                val rows = ((outputs.get(0) as OnnxTensor).value as Array<Array<FloatArray>>)[0]
                return YoloPostProcessor.process(rows, labels, inputSize = inputSize)
            }
        }
    }

    @Synchronized
    override fun close() {
        session.close()
    }

    companion object {
        private const val TAG = "YoloDetector"

        /** Stock Ultralytics YOLO26n (COCO), end-to-end ONNX at 416 px: fast enough for live counting. */
        const val MODEL_ASSET = "yolo26n-416.onnx"

        /** CPU threads for inference (most phones, even old ones, have at least 4 cores). */
        private const val NUM_THREADS = 4

        fun create(context: Context, assetName: String = MODEL_ASSET): YoloDetector {
            val env = OrtEnvironment.getEnvironment()
            val modelBytes = AssetModelLoader.loadBytes(context, assetName)
            val session = createSession(env, modelBytes)
            val labels = labelsFromMetadata(session).ifEmpty { fallbackCocoLabels }
            return YoloDetector(env, session, labels)
        }

        /** XNNPACK (fast ARM kernels) when this ONNX Runtime build supports it, else the plain CPU engine. */
        private fun createSession(env: OrtEnvironment, modelBytes: ByteArray): OrtSession =
            runCatching {
                env.createSession(
                    modelBytes,
                    OrtSession.SessionOptions().apply {
                        // XNNPACK does the threading itself; the session's own pool then stays at 1.
                        addXnnpack(mapOf("intra_op_num_threads" to NUM_THREADS.toString()))
                        setIntraOpNumThreads(1)
                    },
                )
            }.onSuccess { Log.i(TAG, "YOLO on ONNX Runtime + XNNPACK") }
                .getOrElse { error ->
                    Log.w(TAG, "XNNPACK unavailable (${error.message}); using the CPU engine")
                    env.createSession(modelBytes, OrtSession.SessionOptions().apply { setIntraOpNumThreads(NUM_THREADS) })
                }

        /** Class names from the Ultralytics export metadata, e.g. `{0: 'person', 1: 'bicycle', …}`. */
        private fun labelsFromMetadata(session: OrtSession): List<String> =
            runCatching {
                val names = session.metadata.customMetadata["names"].orEmpty()
                Regex("""(\d+):\s*'([^']*)'""").findAll(names)
                    .map { it.groupValues[1].toInt() to it.groupValues[2] }
                    .sortedBy { it.first }
                    .map { it.second }
                    .toList()
            }.getOrDefault(emptyList())
    }
}

// Encodes an already-square [size] bitmap (produced by letterbox) as planar RGB float32 0..1 (C, H, W).
private fun Bitmap.toChwFloats(size: Int): FloatArray {
    val square = if (width == size && height == size) this else Bitmap.createScaledBitmap(this, size, size, true)
    val pixels = IntArray(size * size)
    square.getPixels(pixels, 0, size, 0, 0, size, size)
    if (square !== this) square.recycle()

    val plane = size * size
    val out = FloatArray(3 * plane)
    for (i in 0 until plane) {
        val pixel = pixels[i]
        out[i] = ((pixel shr 16) and 0xFF) / 255f
        out[plane + i] = ((pixel shr 8) and 0xFF) / 255f
        out[2 * plane + i] = (pixel and 0xFF) / 255f
    }
    return out
}

private val fallbackCocoLabels = listOf(
    "person", "bicycle", "car", "motorcycle", "airplane", "bus", "train", "truck",
    "boat", "traffic light", "fire hydrant", "stop sign", "parking meter", "bench",
    "bird", "cat", "dog", "horse", "sheep", "cow", "elephant", "bear", "zebra",
    "giraffe", "backpack", "umbrella", "handbag", "tie", "suitcase", "frisbee",
    "skis", "snowboard", "sports ball", "kite", "baseball bat", "baseball glove",
    "skateboard", "surfboard", "tennis racket", "bottle", "wine glass", "cup",
    "fork", "knife", "spoon", "bowl", "banana", "apple", "sandwich", "orange",
    "broccoli", "carrot", "hot dog", "pizza", "donut", "cake", "chair", "couch",
    "potted plant", "bed", "dining table", "toilet", "tv", "laptop", "mouse",
    "remote", "keyboard", "cell phone", "microwave", "oven", "toaster", "sink",
    "refrigerator", "book", "clock", "vase", "scissors", "teddy bear",
    "hair drier", "toothbrush",
)
