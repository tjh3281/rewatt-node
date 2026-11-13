package com.rewatt.app.ui.security

import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.camera.view.PreviewView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.rewatt.app.R
import com.rewatt.app.security.FaceRecognizer
import com.rewatt.app.security.Person
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

private const val SHOTS_PER_PERSON = 5

/**
 * Registers a named person: first their name (skipped when adding photos to [existing]), then 5 guided
 * front-camera shots and/or up to 5 photos from the gallery. Every photo must contain exactly one face.
 * A person created here who ends up with no photos is removed again on close.
 */
@Composable
fun FaceRegistrationDialog(
    viewModel: SecurityViewModel,
    onClose: () -> Unit,
    existing: Person? = null,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val imageCapture = remember { ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build() }
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    var person by remember { mutableStateOf(existing) }
    var createdHere by remember { mutableStateOf(false) }
    var nameInput by remember { mutableStateOf("") }
    var shot by remember { mutableIntStateOf(1) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<Int?>(null) }
    /** Last gallery import: photos added / photos picked. */
    var galleryResult by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var messageIsError by remember { mutableStateOf(false) }
    val finished = shot > SHOTS_PER_PERSON

    val close = {
        val created = person
        if (createdHere && created != null && viewModel.familyFaces.value.none { it.personId == created.id }) {
            viewModel.deletePerson(created.id)
        }
        onClose()
    }

    val pickPhotos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(SHOTS_PER_PERSON)) { uris ->
        val target = person
        if (target != null && uris.isNotEmpty()) {
            busy = true
            message = R.string.reg_checking
            galleryResult = null
            messageIsError = false
            scope.launch {
                val results = viewModel.registerFromGallery(target.id, uris)
                val added = results.count { it is FaceRecognizer.RegistrationResult.Ok }
                message = null
                galleryResult = added to uris.size
                messageIsError = added == 0
                busy = false
            }
        }
    }

    DisposableEffect(Unit) {
        viewModel.beginRegistration()
        var provider: ProcessCameraProvider? = null
        val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
        val job = scope.launch {
            delay(CAMERA_HANDOFF_MS) // let the background service release the back camera
            // Slower phones take longer to hand the camera over: keep trying for a few seconds, the
            // front camera first (selfie), the back one if the front can't be opened.
            val p = runCatching { ProcessCameraProvider.awaitInstance(context) }
                .onFailure { Log.w(TAG, "Camera provider unavailable", it) }
                .getOrNull()
            provider = p
            var bound = false
            if (p != null) {
                attempts@ for (attempt in 1..BIND_ATTEMPTS) {
                    for (selector in listOf(CameraSelector.DEFAULT_FRONT_CAMERA, CameraSelector.DEFAULT_BACK_CAMERA)) {
                        if (!runCatching { p.hasCamera(selector) }.getOrDefault(false)) continue
                        bound = runCatching {
                            p.unbind(preview, imageCapture)
                            p.bindToLifecycle(lifecycleOwner, selector, preview, imageCapture)
                        }.onFailure { Log.w(TAG, "Registration camera could not open (attempt $attempt)", it) }.isSuccess
                        if (bound) break@attempts
                    }
                    delay(BIND_RETRY_MS)
                }
            }
            if (!bound) {
                message = R.string.reg_camera_error
                messageIsError = true
            }
        }
        onDispose {
            job.cancel()
            runCatching { provider?.unbind(preview, imageCapture) }
            viewModel.endRegistration()
        }
    }

    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF09090B))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    person?.let { stringResource(R.string.reg_title_person, it.name) } ?: stringResource(R.string.reg_title),
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = close) {
                    Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.sec_close), tint = Color.White)
                }
            }

            if (person == null) {
                NameStep(
                    name = nameInput,
                    onNameChange = { nameInput = it.take(MAX_NAME_LENGTH) },
                    onSubmit = {
                        val name = nameInput.trim()
                        if (name.isNotEmpty()) {
                            person = viewModel.addPerson(name)
                            createdHere = true
                        }
                    },
                )
                return@Column
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(24.dp)),
            ) {
                AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
            }

            Text(
                text = if (finished) stringResource(R.string.reg_all_done) else stringResource(R.string.reg_step, shot, SHOTS_PER_PERSON),
                color = Color(0xFF9CA3AF),
                fontSize = 13.sp,
            )
            if (!finished) {
                Text(
                    text = stringResource(promptFor(shot)),
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
            }
            val galleryText = galleryResult?.let { (added, picked) ->
                val summary = stringResource(R.string.reg_gallery_result, added, picked)
                if (added < picked) summary + "\n" + stringResource(R.string.reg_gallery_skipped) else summary
            }
            (message?.let { stringResource(it) } ?: galleryText)?.let {
                Text(
                    it,
                    color = if (messageIsError) Color(0xFFFCA5A5) else Color(0xFF86EFAC),
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                )
            }

            if (finished) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = {
                            // Next person: back to the name step.
                            person = null
                            createdHere = false
                            nameInput = ""
                            shot = 1
                            message = null
                            galleryResult = null
                        },
                        modifier = Modifier.weight(1f).height(52.dp),
                    ) { Text(stringResource(R.string.reg_another), color = Color.White) }
                    Button(
                        onClick = close,
                        modifier = Modifier.weight(1f).height(52.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color(0xFF09090B)),
                    ) { Text(stringResource(R.string.reg_done), fontWeight = FontWeight.Bold) }
                }
            } else {
                Button(
                    onClick = {
                        busy = true
                        message = R.string.reg_checking
                        messageIsError = false
                        galleryResult = null
                        val target = person ?: return@Button
                        scope.launch {
                            val bitmap = imageCapture.captureBitmap(context)
                            val result = bitmap?.let { viewModel.registerFace(target.id, it) }
                            when (result) {
                                is FaceRecognizer.RegistrationResult.Ok -> {
                                    message = R.string.reg_saved
                                    messageIsError = false
                                    shot++
                                }
                                FaceRecognizer.RegistrationResult.NoFace -> { message = R.string.reg_no_face; messageIsError = true }
                                FaceRecognizer.RegistrationResult.MultipleFaces -> { message = R.string.reg_multi; messageIsError = true }
                                FaceRecognizer.RegistrationResult.TooSmall -> { message = R.string.reg_small; messageIsError = true }
                                null -> { message = R.string.reg_error; messageIsError = true }
                            }
                            bitmap?.recycle()
                            busy = false
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color(0xFF09090B)),
                ) {
                    Text(stringResource(R.string.reg_capture), fontSize = 17.sp, fontWeight = FontWeight.Bold)
                }
            }
            OutlinedButton(
                onClick = { pickPhotos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(16.dp),
            ) { Text(stringResource(R.string.reg_gallery), color = Color.White) }
        }
    }
}

/** Step 1: the person's name. */
@Composable
private fun NameStep(name: String, onNameChange: (String) -> Unit, onSubmit: () -> Unit) {
    Text(stringResource(R.string.reg_name_prompt), color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
    OutlinedTextField(
        value = name,
        onValueChange = onNameChange,
        singleLine = true,
        label = { Text(stringResource(R.string.reg_name_hint)) },
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onSubmit() }),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Color.White,
            unfocusedTextColor = Color.White,
            focusedLabelColor = Color.White,
            unfocusedLabelColor = Color(0xFF9CA3AF),
            focusedBorderColor = Color.White,
            unfocusedBorderColor = Color(0xFF6B7280),
            cursorColor = Color.White,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
    Button(
        onClick = onSubmit,
        enabled = name.isNotBlank(),
        modifier = Modifier.fillMaxWidth().height(56.dp),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color(0xFF09090B)),
    ) { Text(stringResource(R.string.reg_next), fontSize = 17.sp, fontWeight = FontWeight.Bold) }
}

private fun promptFor(shot: Int): Int = when (shot) {
    1 -> R.string.reg_prompt_1
    2 -> R.string.reg_prompt_2
    3 -> R.string.reg_prompt_3
    4 -> R.string.reg_prompt_4
    else -> R.string.reg_prompt_5
}

private suspend fun ImageCapture.captureBitmap(context: android.content.Context): Bitmap? =
    suspendCancellableCoroutine { continuation ->
        takePicture(
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val bitmap = runCatching {
                        val raw = image.toBitmap()
                        val degrees = image.imageInfo.rotationDegrees
                        if (degrees == 0) {
                            raw
                        } else {
                            Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, Matrix().apply { postRotate(degrees.toFloat()) }, true)
                                .also { if (it !== raw) raw.recycle() }
                        }
                    }.getOrNull()
                    image.close()
                    continuation.resume(bitmap)
                }

                override fun onError(exception: ImageCaptureException) {
                    continuation.resume(null)
                }
            },
        )
    }

private const val CAMERA_HANDOFF_MS = 600L
private const val BIND_ATTEMPTS = 8
private const val BIND_RETRY_MS = 700L
private const val TAG = "FaceRegistration"
private const val MAX_NAME_LENGTH = 40
