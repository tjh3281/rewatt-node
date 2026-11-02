package com.rewatt.app.perception

import androidx.camera.core.Preview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Hand-off between the on-screen PreviewViews (ENERGY / SECURITY tabs) and the camera owned by
 * [CameraCaptureService]. The UI publishes its surface while visible (null when hidden or the app is
 * in the background); [CameraCaptureController] binds/unbinds a Preview use case to follow it.
 *
 * Also carries the pinch zoom: a REAL camera zoom ratio, so every photo / clip the camera takes
 * (occupancy, YOLO, CCTV) uses the same zoom as the preview.
 */
class LivePreviewHub {
    private val surface = MutableStateFlow<Preview.SurfaceProvider?>(null)
    val surfaceProvider: StateFlow<Preview.SurfaceProvider?> = surface.asStateFlow()

    private val mutableZoom = MutableStateFlow(1f)
    val zoomRatio: StateFlow<Float> = mutableZoom.asStateFlow()

    /** Zoom limits of the bound camera (published by the controller once the camera reports them). */
    @Volatile
    private var zoomRange: ClosedFloatingPointRange<Float> = FALLBACK_MIN..FALLBACK_MAX

    fun attach(provider: Preview.SurfaceProvider) {
        surface.value = provider
    }

    /** Detach only if [provider] is still the current one (a newer screen may already own it). */
    fun detach(provider: Preview.SurfaceProvider) {
        surface.compareAndSet(provider, null)
    }

    /** Pinch: multiply the zoom by [factor], kept within the camera's range. */
    fun zoomBy(factor: Float) {
        mutableZoom.value = (mutableZoom.value * factor).coerceIn(zoomRange.start, zoomRange.endInclusive)
    }

    // TEMP (testing): tap the LIVE chip to use the front camera instead of the back one.
    private val mutableFrontCamera = MutableStateFlow(false)
    val frontCamera: StateFlow<Boolean> = mutableFrontCamera.asStateFlow()

    fun toggleCamera() {
        mutableFrontCamera.value = !mutableFrontCamera.value
    }

    fun setZoomRange(min: Float, max: Float) {
        if (max < min) return
        zoomRange = min..max
        mutableZoom.value = mutableZoom.value.coerceIn(min, max)
    }

    private companion object {
        // Loose limits until the camera reports its own (the controller clamps to the real range).
        const val FALLBACK_MIN = 0.5f
        const val FALLBACK_MAX = 10f
    }
}
