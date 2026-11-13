package com.rewatt.app.ui.sensor

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.util.Log
import android.view.Surface
import android.view.TextureView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.rewatt.app.ReWattApplication
import kotlinx.coroutines.delay

/**
 * Debug demo: plays [asset] (from the app's assets, muted, looping) in the camera box in place of the
 * camera, cropped to fill like the live view. The frame on screen goes to the same pipeline as camera
 * frames: the live security check (face detection, recognition, YOLO person, alarm) through
 * [com.rewatt.app.perception.LivePreviewHub.offerDemoFrame], and the live headcount through [onLiveFrame].
 */
@Composable
internal fun DemoVideoView(
    asset: String,
    modifier: Modifier = Modifier,
    onLiveFrame: (suspend (Bitmap) -> Unit)? = null,
) {
    val context = LocalContext.current
    val hub = (context.applicationContext as ReWattApplication).appGraph.livePreviewHub
    val textureView = remember { TextureView(context) }
    var playing by remember { mutableStateOf(false) }

    DisposableEffect(asset) {
        val player = MediaPlayer()
        var surface: Surface? = null
        fun crop() = applyCenterCrop(textureView, player.videoWidth, player.videoHeight)
        fun start(texture: SurfaceTexture) {
            runCatching {
                surface = Surface(texture)
                player.setSurface(surface)
                context.assets.openFd(asset).use { player.setDataSource(it.fileDescriptor, it.startOffset, it.length) }
                player.isLooping = true
                player.setVolume(0f, 0f)
                player.setOnVideoSizeChangedListener { _, _, _ -> crop() }
                player.setOnPreparedListener {
                    crop()
                    it.start()
                    playing = true
                }
                player.prepareAsync()
            }.onFailure { Log.e(TAG, "Demo video $asset could not play", it) }
        }
        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) = start(texture)
            override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = crop()
            override fun onSurfaceTextureDestroyed(texture: SurfaceTexture) = true
            override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
        }
        textureView.surfaceTexture?.takeIf { textureView.isAvailable }?.let { start(it) }
        onDispose {
            playing = false
            runCatching { player.stop() }
            player.release()
            surface?.release()
        }
    }

    // Security check: the frame on screen, about 6 times a second (the check drops what it can't keep up with).
    LaunchedEffect(textureView) {
        while (true) {
            if (playing) textureView.frame(SECURITY_MAX_SIDE)?.let(hub::offerDemoFrame)
            delay(SECURITY_INTERVAL_MS)
        }
    }
    // Headcount: like the camera preview, the next frame as soon as the previous count is done.
    val currentOnLiveFrame by rememberUpdatedState(onLiveFrame)
    LaunchedEffect(textureView, onLiveFrame != null) {
        if (onLiveFrame == null) return@LaunchedEffect
        while (true) {
            val analyze = currentOnLiveFrame
            val frame = if (playing) textureView.frame(HEADCOUNT_MAX_SIDE) else null
            if (frame != null && analyze != null) analyze(frame) else delay(200)
        }
    }

    AndroidView(factory = { textureView }, modifier = modifier)
}

/** What the view shows (cropped), at most [maxSide] px on its long side; null before it has a size. */
private fun TextureView.frame(maxSide: Int): Bitmap? {
    if (!isAvailable || width <= 0 || height <= 0) return null
    val scale = minOf(1f, maxSide.toFloat() / maxOf(width, height))
    return getBitmap((width * scale).toInt().coerceAtLeast(1), (height * scale).toInt().coerceAtLeast(1))
}

/** Scale the video to fill the view, cropping the overflow (like the live view's FILL_CENTER). */
private fun applyCenterCrop(view: TextureView, videoWidth: Int, videoHeight: Int) {
    val w = view.width.toFloat()
    val h = view.height.toFloat()
    if (w <= 0f || h <= 0f || videoWidth <= 0 || videoHeight <= 0) return
    val scale = maxOf(w / videoWidth, h / videoHeight)
    view.setTransform(Matrix().apply { setScale(videoWidth * scale / w, videoHeight * scale / h, w / 2f, h / 2f) })
}

private const val TAG = "DemoVideo"
private const val SECURITY_MAX_SIDE = 1280
private const val SECURITY_INTERVAL_MS = 160L
private const val HEADCOUNT_MAX_SIDE = 960
