package com.rewatt.app.perception

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Thin UI-facing controller for perception. The actual capture pipeline now runs inside
 * [CameraCaptureService] (a foreground service) so it keeps firing with the screen off; this view
 * model just starts/stops that service and exposes the shared [PerceptionStateHolder] state to the
 * dashboard. The debug "Upload image" path still runs in-process here since it is a one-shot,
 * UI-driven analysis with no background requirement.
 */
class PerceptionViewModel(
    private val appContext: Context,
    private val stateHolder: PerceptionStateHolder,
) : ViewModel() {
    val uiState: StateFlow<PerceptionUiState> = stateHolder.state

    private var uploadDetector: YoloDetector? = null

    fun setPermissionState(cameraGranted: Boolean) {
        stateHolder.update {
            it.copy(cameraPermissionGranted = cameraGranted)
        }
    }

    fun startPerception(source: CaptureStartSource = CaptureStartSource.Manual) {
        stateHolder.update { it.copy(captureStartSource = source) }
        ContextCompat.startForegroundService(appContext, CameraCaptureService.startIntent(appContext))
    }

    fun stopPerception() {
        // Stops only the 10-minute occupancy capture; CCTV keeps running if it is Armed.
        runCatching { appContext.startService(CameraCaptureService.stopPerceptionIntent(appContext)) }
    }

    /** Debug bypass: ask the running service to capture immediately, ignoring the 10-minute mark. */
    fun captureNow() {
        ContextCompat.startForegroundService(appContext, CameraCaptureService.captureNowIntent(appContext))
    }

    fun selectYoloModel(model: YoloModel) {
        // Only the yolo26n-416 model ships now; the selection is purely cosmetic for the debug UI.
        stateHolder.update { it.copy(selectedYoloModel = model) }
    }

    fun analyzeUploadedYoloImage(uri: Uri) {
        stateHolder.update {
            it.copy(
                yoloMessage = "Loading uploaded image for YOLO.",
                exposureEv = null,
                exposureIso = null,
                exposureTimeSeconds = null,
                exposureAperture = ExposureValueCalculator.FIXED_APERTURE,
                lightsOn = null,
                exposureMessage = "Exposure is unavailable for uploaded debug images.",
            )
        }

        viewModelScope.launch(Dispatchers.Default) {
            val bitmap = runCatching {
                withContext(Dispatchers.IO) { decodeUploadedBitmap(uri).limitLongestSide() }
            }.onFailure { error ->
                updateYoloMessage("Uploaded image failed to load: ${error.message ?: error::class.java.simpleName}")
            }.getOrNull() ?: return@launch

            val detector = loadUploadDetector() ?: return@launch
            val detections = runCatching { detector.detect(bitmap) }
                .onFailure { error ->
                    updateUploadedYoloResult(
                        bitmap = bitmap,
                        detections = emptyList(),
                        message = "Uploaded image YOLO failed: ${error.message ?: error::class.java.simpleName}",
                    )
                }
                .getOrNull() ?: return@launch

            updateUploadedYoloResult(
                bitmap = bitmap,
                detections = detections,
                message = "Uploaded image analyzed. People detected: ${detections.count { it.isPerson }}.",
            )
        }
    }

    private fun loadUploadDetector(): YoloDetector? {
        uploadDetector?.let { return it }
        return runCatching { YoloDetector.create(appContext) }
            .onFailure { error ->
                updateYoloMessage("YOLO model failed to load: ${error.message ?: error::class.java.simpleName}")
            }
            .getOrNull()
            ?.also { uploadDetector = it }
    }

    override fun onCleared() {
        uploadDetector?.close()
        uploadDetector = null
        super.onCleared()
    }

    private fun updateYoloMessage(message: String) {
        stateHolder.update { it.copy(yoloMessage = message) }
    }

    private fun updateUploadedYoloResult(
        bitmap: Bitmap,
        detections: List<ObjectDetection>,
        message: String,
    ) {
        stateHolder.update {
            it.copy(
                latestFrame = bitmap,
                latestFrameAtMillis = System.currentTimeMillis(),
                yoloDetections = detections,
                yoloMessage = message,
                exposureEv = null,
                exposureIso = null,
                exposureTimeSeconds = null,
                exposureAperture = ExposureValueCalculator.FIXED_APERTURE,
                lightsOn = null,
                exposureMessage = "Exposure is unavailable for uploaded debug images.",
                statusMessage = if (it.isRunning) {
                    "Perception is running."
                } else {
                    "Uploaded image analyzed for YOLO."
                },
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun decodeUploadedBitmap(uri: Uri): Bitmap =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(appContext.contentResolver, uri)
            ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            MediaStore.Images.Media.getBitmap(appContext.contentResolver, uri)
        }

    // Higher cap so tiling has enough pixels per cell to resolve distant people.
    private fun Bitmap.limitLongestSide(maxSide: Int = 4096): Bitmap {
        val longestSide = maxOf(width, height)
        if (longestSide <= maxSide) return this

        val scale = maxSide.toFloat() / longestSide
        val scaledWidth = (width * scale).roundToInt().coerceAtLeast(1)
        val scaledHeight = (height * scale).roundToInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(this, scaledWidth, scaledHeight, true)
    }
}

class PerceptionViewModelFactory(
    private val appContext: Context,
    private val stateHolder: PerceptionStateHolder,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(PerceptionViewModel::class.java)) {
            return PerceptionViewModel(
                appContext = appContext.applicationContext,
                stateHolder = stateHolder,
            ) as T
        }
        error("Unknown ViewModel class: ${modelClass.name}")
    }
}
