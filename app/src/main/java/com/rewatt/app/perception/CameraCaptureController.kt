package com.rewatt.app.perception

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.OrientationEventListener
import android.view.Surface
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import androidx.core.graphics.get
import androidx.lifecycle.LifecycleOwner
import com.rewatt.app.security.LiveFrame
import com.rewatt.app.security.SecurityFrame
import java.io.Closeable
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Owns the back camera for two independent purposes that share one CameraX session:
 *
 * 1. **Occupancy** ([capture]) — the 10-minute illuminance + headcount photo. Behaviour is unchanged:
 *    EXIF EV → [onFramePreview], then YOLO → [onFrameAnalyzed]; the ImageCapture stays bound (warm)
 *    afterwards exactly as before. If the CCTV torch is on, it is switched off and the exposure is
 *    allowed to settle before this photo so the EV reading is never lit by the torch.
 * 2. **Security** ([captureForSecurity], [startRecording], [setTorch]) — CCTV photos and video. These
 *    frames go only to the caller and never touch occupancy state.
 * 3. **Live security** ([beginLiveSecurity]) — a continuous ~1280×960 ImageAnalysis stream for the live
 *    face check. While it runs, ImageCapture is unbound (Preview + ImageAnalysis + VideoCapture is the
 *    most many phones can bind at once), so occupancy photos due in that time are skipped.
 *
 * Camera operations are serialised by [cameraMutex] so neither purpose drops the other's request.
 */
class CameraCaptureController(
    private val context: Context,
    private val yoloDetectorProvider: () -> YoloDetector?,
    private val onFramePreview: (Bitmap, ExposureReading?, String) -> Unit,
    private val onFrameAnalyzed: (Bitmap?, List<ObjectDetection>, String, ExposureReading?, String) -> Unit,
    private val onError: (String) -> Unit,
    /** Live view: a Preview use case is bound while it holds a surface; also the pinch (camera) zoom. */
    private val liveView: LivePreviewHub? = null,
) : Closeable {
    private val analysisExecutor: ExecutorService = Executors.newFixedThreadPool(2)
    /** Live face-check frames: one thread, so the analyzer itself paces the stream (latest frame only). */
    private val liveExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val cameraMutex = Mutex()
    private var lifecycleOwner: LifecycleOwner? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var activeImageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var preview: Preview? = null
    private var liveAnalysis: ImageAnalysis? = null
    private var recording: Recording? = null

    /** True while the live face check streams frames (ImageCapture stays unbound). */
    @Volatile
    private var liveSessionActive = false

    @Volatile
    private var closed = false

    @Volatile
    private var captureInFlight = false

    /** True while a CCTV motion event holds the camera (keeps video bound, allows torch). */
    @Volatile
    private var securitySessionActive = false

    /** True while another screen (face registration) owns the camera; occupancy captures are skipped. */
    @Volatile
    private var paused = false

    @Volatile
    private var desiredTorch = false

    @Volatile
    private var torchSuppressed = false

    @Volatile
    private var alarmTorch = false

    @Volatile
    private var deviceRotation: Int = Surface.ROTATION_0

    private val orientationListener = object : OrientationEventListener(context) {
        override fun onOrientationChanged(orientation: Int) {
            if (orientation == ORIENTATION_UNKNOWN) return
            deviceRotation = when (orientation) {
                in 45 until 135 -> Surface.ROTATION_270
                in 135 until 225 -> Surface.ROTATION_180
                in 225 until 315 -> Surface.ROTATION_90
                else -> Surface.ROTATION_0
            }
            // Keep live frames upright so faces reach the detector the right way up.
            liveAnalysis?.targetRotation = deviceRotation
        }
    }

    fun attach(owner: LifecycleOwner) {
        lifecycleOwner = owner
        ContextCompat.getMainExecutor(context).execute {
            runCatching { if (orientationListener.canDetectOrientation()) orientationListener.enable() }
        }
        liveView?.let { hub ->
            scope.launch {
                hub.surfaceProvider.collectLatest { surface ->
                    // Switching tabs (ENERGY ⇄ SECURITY) briefly has no surface: keep the Preview
                    // bound for a moment so the next screen just takes it over (no camera restart).
                    if (surface == null) delay(PREVIEW_RELEASE_DELAY_MS)
                    cameraMutex.withLock { applyPreview(surface) }
                }
            }
            scope.launch { hub.zoomRatio.collect { withContext(Dispatchers.Main.immediate) { applyZoom() } } }
        }
    }

    /** Apply the pinch zoom to the bound camera and publish its zoom limits; call after every bind. */
    private fun applyZoom() {
        val hub = liveView ?: return
        val bound = camera ?: return
        bound.cameraInfo.zoomState.value?.let { hub.setZoomRange(it.minZoomRatio, it.maxZoomRatio) }
        runCatching { bound.cameraControl.setZoomRatio(hub.zoomRatio.value) }
    }

    // ── Live view (ENERGY tab) ───────────────────────────────────────────────────────────────

    /** Bind a Preview to [surface], or unbind it when null / paused; must hold [cameraMutex]. */
    private suspend fun applyPreview(surface: Preview.SurfaceProvider?) = withContext(Dispatchers.Main.immediate) {
        val owner = lifecycleOwner ?: return@withContext
        if (closed) return@withContext
        if (surface == null || paused) {
            preview?.let { p ->
                cameraProvider?.let { runCatching { it.unbind(p) } }
                p.setSurfaceProvider(null)
            }
            preview = null
            if (activeImageCapture == null && videoCapture == null && liveAnalysis == null) camera = null
            return@withContext
        }
        runCatching {
            val provider = cameraProvider ?: ProcessCameraProvider.awaitInstance(context).also { cameraProvider = it }
            val p = preview ?: buildPreview().also { preview = it }
            p.setSurfaceProvider(surface)
            if (!provider.isBound(p)) {
                camera = provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, p)
            }
            applyTorch()
            applyZoom()
        }.onFailure { Log.w(TAG, "Live preview could not bind", it) }
    }

    private fun buildPreview(): Preview =
        Preview.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .build(),
            )
            .build()

    // ── Occupancy (illuminance + headcount) ──────────────────────────────────────────────────

    /**
     * Capture the occupancy frame. Reuses the warm bound ImageCapture for fast repeated captures.
     */
    fun capture() {
        if (closed || captureInFlight || paused) return
        if (lifecycleOwner == null) return
        if (liveSessionActive) {
            Log.i(TAG, "Occupancy capture skipped: the live security check holds the camera")
            return
        }
        captureInFlight = true

        scope.launch {
            val photoFile = try {
                cameraMutex.withLock {
                    val imageCapture = ensureBound(wantVideo = recording != null)
                    // Protect the illuminance reading: never take this photo with the CCTV torch lit.
                    val torchWasLit = (securitySessionActive && desiredTorch) || alarmTorch
                    if (torchWasLit) {
                        torchSuppressed = true
                        applyTorch()
                        delay(TORCH_SETTLE_MS)
                    }
                    try {
                        takePictureToFile(imageCapture, "rewatt-exposure-")
                    } finally {
                        if (torchSuppressed) {
                            torchSuppressed = false
                            applyTorch()
                        }
                    }
                }
            } catch (error: Throwable) {
                captureInFlight = false
                if (error is CancellationException) throw error
                val reason = error.message ?: error::class.java.simpleName
                if (error is ImageCaptureException) {
                    notifyCaptureFailure("Camera image capture failed: $reason")
                } else {
                    onError("Camera failed to start: $reason")
                }
                return@launch
            }

            try {
                processSavedImageAsync(photoFile)
            } catch (error: Throwable) {
                captureInFlight = false
                photoFile.delete()
                notifyCaptureFailure("Camera analysis failed: ${error.message ?: error::class.java.simpleName}")
            }
        }
    }

    fun captureNow() = capture()

    // ── Security (CCTV) ──────────────────────────────────────────────────────────────────────

    /** Start a CCTV session: binds the camera (if not already warm) and allows the torch. */
    suspend fun beginSecuritySession(): Boolean {
        if (closed) return false
        securitySessionActive = true
        return runCatching { cameraMutex.withLock { ensureBound(wantVideo = false) } }
            .onFailure { Log.w(TAG, "Security session could not bind the camera", it) }
            .isSuccess
    }

    /** One CCTV still: decoded at ~[SECURITY_MAX_SIDE] px, rotated, with its EXIF EV. Never used for occupancy. */
    suspend fun captureForSecurity(): SecurityFrame? {
        if (closed || paused || liveSessionActive) return null
        val file = runCatching {
            cameraMutex.withLock {
                takePictureToFile(ensureBound(wantVideo = recording != null), "rewatt-cctv-")
            }
        }.onFailure { if (it is CancellationException) throw it; Log.w(TAG, "Security capture failed", it) }
            .getOrNull() ?: return null

        return withContext(Dispatchers.IO) {
            try {
                val exposure = runCatching { ExposureValueCalculator.fromExif(file.absolutePath) }.getOrNull()
                val bitmap = decodeSampled(file, SECURITY_MAX_SIDE)?.rotateFromExif(file.absolutePath)
                bitmap?.let {
                    SecurityFrame(it, exposure?.ev, exposure?.lightsOn, System.currentTimeMillis())
                }
            } finally {
                file.delete()
            }
        }
    }

    /**
     * Start the live security stream: unbinds ImageCapture and binds a ~1280×960 ImageAnalysis next to
     * the live Preview. Each upright frame goes to [onFrame] on a single analysis thread, which owns
     * (and must recycle) the bitmap; frames that arrive while it is still busy are dropped, so the
     * analysis speed sets the frame rate. Also allows the torch, like [beginSecuritySession].
     */
    suspend fun beginLiveSecurity(onFrame: (LiveFrame) -> Unit): Boolean {
        if (closed || paused) return false
        securitySessionActive = true
        liveSessionActive = true
        val bound = runCatching {
            cameraMutex.withLock {
                withContext(Dispatchers.Main.immediate) {
                    val owner = lifecycleOwner ?: error("Camera controller is not attached")
                    val provider = cameraProvider ?: ProcessCameraProvider.awaitInstance(context).also { cameraProvider = it }
                    activeImageCapture?.let { runCatching { provider.unbind(it) } }
                    activeImageCapture = null
                    val analysis = liveAnalysis ?: buildLiveAnalysis().also { liveAnalysis = it }
                    analysis.setAnalyzer(liveExecutor) { image ->
                        val frame = runCatching { image.toLiveFrame() }
                            .onFailure { Log.w(TAG, "Live frame conversion failed", it) }
                            .getOrNull()
                        image.close()
                        if (frame == null) return@setAnalyzer
                        if (liveSessionActive && !closed) onFrame(frame) else frame.bitmap.recycle()
                    }
                    if (!provider.isBound(analysis)) {
                        camera = provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, analysis)
                    }
                    applyTorch()
                    applyZoom()
                }
            }
        }.onFailure { if (it is CancellationException) throw it; Log.w(TAG, "Live security stream could not bind", it) }
            .isSuccess
        if (!bound) {
            liveSessionActive = false
            securitySessionActive = false
        }
        return bound
    }

    /** Stop delivering live frames but keep the camera (and torch strobe) bound, e.g. while an alarm rings. */
    fun pauseLiveFrames() {
        liveAnalysis?.clearAnalyzer()
    }

    /** End the live stream and the security session; see [endSecuritySession] for [keepImageBound]. */
    suspend fun endLiveSecurity(keepImageBound: Boolean) {
        liveSessionActive = false
        cameraMutex.withLock {
            withContext(Dispatchers.Main.immediate) {
                liveAnalysis?.let { analysis ->
                    analysis.clearAnalyzer()
                    cameraProvider?.let { runCatching { it.unbind(analysis) } }
                }
                liveAnalysis = null
            }
        }
        endSecuritySession(keepImageBound)
    }

    /** Bind video (FHD, fixed 30 fps) if needed and start recording into [file]. */
    @SuppressLint("MissingPermission")
    suspend fun startRecording(file: File, withAudio: Boolean): Boolean {
        if (closed || !securitySessionActive) return false
        return runCatching {
            cameraMutex.withLock {
                if (liveSessionActive) {
                    check(ensureLiveVideo()) { "No video stream fits beside the live check" }
                } else {
                    ensureBound(wantVideo = true)
                }
                withContext(Dispatchers.Main.immediate) {
                    recording?.stop()
                    val audioAllowed = withAudio && ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.RECORD_AUDIO,
                    ) == PackageManager.PERMISSION_GRANTED
                    val pending = videoCapture!!.output
                        .prepareRecording(context, FileOutputOptions.Builder(file).build())
                        .let { if (audioAllowed) it.withAudioEnabled() else it }
                    recording = pending.start(ContextCompat.getMainExecutor(context)) { event ->
                        if (event is VideoRecordEvent.Finalize && event.hasError()) {
                            Log.w(TAG, "Clip ${file.name} finalised with error ${event.error}: ${event.cause?.message}")
                        }
                    }
                }
            }
        }.onFailure { if (it is CancellationException) throw it; Log.w(TAG, "Recording failed to start", it) }
            .isSuccess
    }

    suspend fun stopRecording() {
        withContext(Dispatchers.Main.immediate) {
            recording?.stop()
            recording = null
        }
    }

    val isRecording: Boolean get() = recording != null

    /** Request the torch on/off. Ignored outside a security session or while the occupancy photo is taken. */
    fun setTorch(on: Boolean) {
        desiredTorch = on
        scope.launch(Dispatchers.Main.immediate) { applyTorch() }
    }

    /** Alarm strobe (e.g. fire) while this controller holds the camera; still off during occupancy photos. */
    fun setAlarmTorch(on: Boolean) {
        alarmTorch = on
        scope.launch(Dispatchers.Main.immediate) { applyTorch() }
    }

    val isCameraBound: Boolean get() = camera != null

    /**
     * End the CCTV session: stop the clip, torch off, unbind video. The ImageCapture stays bound
     * only when [keepImageBound] (the occupancy capture is running and expects it warm).
     */
    suspend fun endSecuritySession(keepImageBound: Boolean) {
        stopRecording()
        securitySessionActive = false
        desiredTorch = false
        cameraMutex.withLock {
            withContext(Dispatchers.Main.immediate) {
                applyTorch()
                val provider = cameraProvider ?: return@withContext
                videoCapture?.let { runCatching { provider.unbind(it) } }
                videoCapture = null
                if (!keepImageBound) {
                    activeImageCapture?.let { runCatching { provider.unbind(it) } }
                    activeImageCapture = null
                }
                // The live view (or a warm ImageCapture) keeps the camera open.
                if (preview == null && activeImageCapture == null && liveAnalysis == null) camera = null
            }
        }
    }

    /** Hand the camera to another screen (face registration uses the front camera). */
    suspend fun pauseForExternalCamera() {
        paused = true
        cameraMutex.withLock {
            withContext(Dispatchers.Main.immediate) {
                recording?.stop()
                recording = null
                val provider = cameraProvider ?: return@withContext
                liveAnalysis?.clearAnalyzer()
                listOfNotNull(activeImageCapture, videoCapture, preview, liveAnalysis).forEach { runCatching { provider.unbind(it) } }
                preview?.setSurfaceProvider(null)
                preview = null
                activeImageCapture = null
                videoCapture = null
                liveAnalysis = null
                liveSessionActive = false
                camera = null
            }
        }
    }

    fun resumeAfterExternalCamera() {
        paused = false
        liveView?.let { hub -> scope.launch { cameraMutex.withLock { applyPreview(hub.surfaceProvider.value) } } }
    }

    // ── Shared camera plumbing ───────────────────────────────────────────────────────────────

    /** Bind ImageCapture (+ VideoCapture when [wantVideo]) to the back camera; must hold [cameraMutex]. */
    private suspend fun ensureBound(wantVideo: Boolean): ImageCapture = withContext(Dispatchers.Main.immediate) {
        val owner = lifecycleOwner ?: error("Camera controller is not attached")
        val provider = cameraProvider ?: ProcessCameraProvider.awaitInstance(context).also { cameraProvider = it }
        val image = activeImageCapture?.takeIf { provider.isBound(it) }
        val video = videoCapture?.takeIf { provider.isBound(it) }
        if (image != null && (!wantVideo || video != null)) return@withContext image

        val newImage = image ?: buildImageCapture()
        val newVideo = if (wantVideo) video ?: buildVideoCapture() else null
        if (image == null) {
            // Cold start, as before: clear any stale binding (e.g. a closed preview screen). The
            // ENERGY live view (if showing) is re-bound together with the capture use cases.
            provider.unbindAll()
            camera = provider.bindToLifecycle(
                owner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                *listOfNotNull(preview, newImage, newVideo).toTypedArray(),
            )
        } else if (newVideo != null) {
            camera = provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, newVideo)
        }
        activeImageCapture = newImage
        videoCapture = newVideo ?: video
        applyTorch()
        applyZoom()
        newImage
    }

    /**
     * Bind VideoCapture beside the live Preview + ImageAnalysis; must hold [cameraMutex]. Not every phone
     * can stream all three at FHD, so lower qualities are tried before giving up (photos only).
     */
    private suspend fun ensureLiveVideo(): Boolean = withContext(Dispatchers.Main.immediate) {
        val owner = lifecycleOwner ?: return@withContext false
        val provider = cameraProvider ?: return@withContext false
        if (videoCapture?.let { provider.isBound(it) } == true) return@withContext true
        for (quality in LIVE_VIDEO_QUALITIES) {
            val candidate = buildVideoCapture(QualitySelector.from(quality, FallbackStrategy.lowerQualityThan(quality)))
            val bound = runCatching {
                camera = provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, candidate)
            }.onFailure { Log.w(TAG, "Live video at $quality could not bind beside the live check", it) }.isSuccess
            if (bound) {
                videoCapture = candidate
                applyTorch()
                applyZoom()
                return@withContext true
            }
        }
        false
    }

    private fun applyTorch() {
        val on = ((desiredTorch && securitySessionActive) || alarmTorch) && !torchSuppressed
        runCatching { camera?.cameraControl?.enableTorch(on) }
    }

    private suspend fun takePictureToFile(capture: ImageCapture, prefix: String): File {
        val photoFile = withContext(Dispatchers.IO) { File.createTempFile(prefix, ".jpg", context.cacheDir) }
        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()
        return suspendCancellableCoroutine { continuation ->
            runCatching {
                capture.takePicture(
                    outputOptions,
                    analysisExecutor,
                    object : ImageCapture.OnImageSavedCallback {
                        override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                            continuation.resume(photoFile)
                        }

                        override fun onError(exception: ImageCaptureException) {
                            photoFile.delete()
                            continuation.resumeWithException(exception)
                        }
                    },
                )
            }.onFailure { error ->
                photoFile.delete()
                continuation.resumeWithException(error)
            }
        }
    }

    private fun buildImageCapture(): ImageCapture =
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .build(),
            )
            .build()
            .also {
                it.flashMode = ImageCapture.FLASH_MODE_OFF
                it.targetRotation = deviceRotation
            }

    private fun buildLiveAnalysis(): ImageAnalysis =
        ImageAnalysis.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(LIVE_WIDTH, LIVE_HEIGHT),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
                        ),
                    )
                    .build(),
            )
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()
            .also { it.targetRotation = deviceRotation }

    private fun buildVideoCapture(
        qualitySelector: QualitySelector = QualitySelector.from(Quality.FHD, FallbackStrategy.lowerQualityOrHigherThan(Quality.HD)),
    ): VideoCapture<Recorder> {
        val recorder = Recorder.Builder()
            .setQualitySelector(qualitySelector)
            .build()
        return VideoCapture.Builder(recorder)
            .setTargetFrameRate(Range(VIDEO_FPS, VIDEO_FPS))
            .build()
            .also { it.targetRotation = deviceRotation }
    }

    override fun close() {
        closed = true
        captureInFlight = false
        lifecycleOwner = null
        runCatching { orientationListener.disable() }
        ContextCompat.getMainExecutor(context).execute {
            runCatching {
                recording?.stop()
                recording = null
                camera?.cameraControl?.enableTorch(false)
                cameraProvider?.unbindAll()
                cameraProvider = null
                preview?.setSurfaceProvider(null)
                preview = null
                activeImageCapture = null
                videoCapture = null
                liveAnalysis?.clearAnalyzer()
                liveAnalysis = null
                camera = null
            }
        }
        scope.cancel()
        analysisExecutor.shutdownNow()
        liveExecutor.shutdownNow()
    }

    private fun processSavedImageAsync(photoFile: File) {
        if (closed) {
            photoFile.delete()
            captureInFlight = false
            return
        }

        val exposureReading = runCatching {
            ExposureValueCalculator.fromExif(photoFile.absolutePath)
        }.getOrNull()

        val bitmap = BitmapFactory.decodeFile(photoFile.absolutePath)
            ?.rotateFromExif(photoFile.absolutePath)

        photoFile.delete()

        if (bitmap == null) {
            captureInFlight = false
            onFrameAnalyzed(
                null,
                emptyList(),
                "Camera frame could not be decoded.",
                exposureReading,
                exposureReading.toExposureMessage(),
            )
            return
        }

        // 1. Publish preview frame to UI immediately (~30ms)!
        onFramePreview(bitmap, exposureReading, exposureReading.toExposureMessage())

        // 2. UNLOCK captureInFlight IMMEDIATELY so user can tap Test Capture again right away!
        captureInFlight = false

        // 3. Run slow YOLO detection asynchronously in background without blocking capture lock!
        analysisExecutor.execute {
            if (closed) return@execute
            val yoloDetector = yoloDetectorProvider()
            val detections = yoloDetector?.detect(bitmap).orEmpty()
            val personCount = detections.count { it.isPerson }
            val message = if (yoloDetector == null) {
                "Captured frame; YOLO is still loading."
            } else if (detections.isEmpty()) {
                "No people detected above the confidence threshold."
            } else {
                "People detected: $personCount."
            }

            onFrameAnalyzed(
                bitmap,
                detections,
                message,
                exposureReading,
                exposureReading.toExposureMessage(),
            )
        }
    }

    private fun notifyCaptureFailure(message: String) {
        if (!closed) {
            onFrameAnalyzed(null, emptyList(), message, null, "Exposure data unavailable.")
        }
    }

    companion object {
        private const val TAG = "CameraCaptureController"

        /** Time for auto-exposure to settle after the torch is switched off for an occupancy photo. */
        private const val TORCH_SETTLE_MS = 800L

        /** How long the live Preview stays bound after its screen goes away (tab switches). */
        private const val PREVIEW_RELEASE_DELAY_MS = 1_500L
        private const val VIDEO_FPS = 30

        /** Live face-check frames (4:3, like the Preview, so overlay boxes line up with the live view). */
        private const val LIVE_WIDTH = 1280
        private const val LIVE_HEIGHT = 960
        private val LIVE_VIDEO_QUALITIES = listOf(Quality.FHD, Quality.HD, Quality.SD)

        /** CCTV stills are decoded at about this size: plenty for faces, light on memory. */
        const val SECURITY_MAX_SIDE = 2000
    }
}

private fun ExposureReading?.toExposureMessage(): String =
    this?.let {
        "EV %.1f; lights %s.".format(it.ev, if (it.lightsOn) "on" else "off")
    } ?: "Exposure data unavailable."

/** Upright RGBA frame for the live face check, with a coarse mean luma (0–255) sampled on a grid. */
private fun ImageProxy.toLiveFrame(): LiveFrame {
    val raw = toBitmap()
    val upright = raw.rotate(imageInfo.rotationDegrees)
    if (upright !== raw) raw.recycle()
    var sum = 0L
    var n = 0
    val stepX = (upright.width / LUMA_GRID).coerceAtLeast(1)
    val stepY = (upright.height / LUMA_GRID).coerceAtLeast(1)
    var y = stepY / 2
    while (y < upright.height) {
        var x = stepX / 2
        while (x < upright.width) {
            val p = upright[x, y]
            sum += (((p shr 16) and 0xFF) * 299 + ((p shr 8) and 0xFF) * 587 + (p and 0xFF) * 114) / 1000
            n++
            x += stepX
        }
        y += stepY
    }
    return LiveFrame(upright, meanLuma = if (n == 0) 0f else sum.toFloat() / n, capturedAtMillis = System.currentTimeMillis())
}

private const val LUMA_GRID = 32
