package com.rewatt.app.security

import android.content.Context
import android.graphics.Bitmap
import android.os.PowerManager
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import com.rewatt.app.data.MqttSyncRepository
import com.rewatt.app.data.TelemetryRepository
import com.rewatt.app.perception.CameraCaptureController
import com.rewatt.app.perception.YoloDetector
import com.rewatt.app.telemetry.isMovingTarget
import com.rewatt.app.telemetry.toOccupancyDecision
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * CCTV event engine ("Lane 2: Security camera"), running inside the camera foreground service so it
 * works with the screen off.
 *
 * Event timing follows the old PIR project:
 *   IDLE ──2 moving radar samples──▶ MOTION ──no presence──▶ GRACE(5 s) ──5 s quiet──▶ IDLE
 *                                      ▲───────── presence again ─────┘
 *
 * During MOTION, a photo is checked every ~2 s until decided:
 *   YOLO: no person      → ignore, keep checking (nothing recorded)
 *   person, no face      → record (clip kept as evidence), keep checking
 *   face → InsightFace   → FAMILY ✓ → silent: stop + delete the clip
 *                        → unknown / masked ✗ → torch strobe + "You are being recorded" + siren + red alert
 */
class IntrusionMonitor(
    context: Context,
    private val scope: CoroutineScope,
    private val controllerProvider: () -> CameraCaptureController?,
    private val yoloProvider: () -> YoloDetector?,
    private val perceptionActive: () -> Boolean,
    private val telemetryRepository: TelemetryRepository,
    private val recognizer: FaceRecognizer,
    private val eventStore: SecurityEventStore,
    private val settings: SecuritySettings,
    private val stateHolder: SecurityStateHolder,
    private val alarm: IntrusionAlarm,
    private val notifier: IntrusionNotifier,
    private val mqtt: MqttSyncRepository,
) {
    private val appContext = context.applicationContext
    private val powerManager = appContext.getSystemService(PowerManager::class.java)
    private var armedWakeLock: PowerManager.WakeLock? = null

    private var pollJob: Job? = null
    private var eventJob: Job? = null
    private var clipJob: Job? = null

    // Event state (only touched from the poll loop / event coroutine on the service scope).
    private var phase = EventPhase.IDLE
    private var consecutiveMoving = 0
    private var lastSampleAt = 0L
    private var graceDeadline = 0L
    private var eventId: String? = null
    private var personSeen = false
    private var verdict = EventResult.NO_PERSON
    private var bestScore: Float? = null
    private var strangerPhoto: String? = null
    private var eventTorch = false
    private var deterrentSpoken = false
    private var releaseCameraAfterAlarm = false

    val isRunning: Boolean get() = pollJob?.isActive == true

    fun start() {
        if (isRunning) return
        armedWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "rewatt:cctv-armed")
            .also { runCatching { it.acquire() } }
        notifier.ensureChannels()
        scope.launch(Dispatchers.Default) { recognizer.warmUp() }
        stateHolder.update { it.copy(armed = true, phase = EventPhase.IDLE, statusMessage = null) }
        pollJob = scope.launch { pollLoop() }
    }

    suspend fun stop() {
        pollJob?.cancelAndJoin()
        pollJob = null
        if (eventId != null) endEvent()
        if (alarm.alarmActive) {
            alarm.dismiss()
            onAlarmDismissed()
        }
        armedWakeLock?.let { if (it.isHeld) runCatching { it.release() } }
        armedWakeLock = null
        phase = EventPhase.IDLE
        consecutiveMoving = 0
        stateHolder.update { it.copy(armed = false, phase = EventPhase.IDLE, activeEventId = null, recording = false, torchOn = false) }
    }

    /** Immediate, non-suspending teardown for service destruction (the camera is closed by the service). */
    fun abort() {
        pollJob?.cancel()
        eventJob?.cancel()
        clipJob?.cancel()
        pollJob = null
        eventJob = null
        clipJob = null
        armedWakeLock?.let { if (it.isHeld) runCatching { it.release() } }
        armedWakeLock = null
        eventId?.let { id ->
            eventStore.update(id) { it.copy(endedAtMillis = System.currentTimeMillis(), result = verdict) }
        }
        eventId = null
        phase = EventPhase.IDLE
    }

    /** Called after the user dismisses the alarm (red screen or notification). */
    suspend fun onAlarmDismissed() {
        stateHolder.state.value.alarmEventId?.let { id ->
            eventStore.update(id) { it.copy(alarmDismissedAtMillis = System.currentTimeMillis()) }
        }
        if (releaseCameraAfterAlarm && eventId == null) {
            releaseCameraAfterAlarm = false
            controllerProvider()?.endSecuritySession(keepImageBound = perceptionActive())
        }
    }

    // ── Radar → event state machine ──────────────────────────────────────────────────────────

    private suspend fun pollLoop() {
        while (scope.isActive) {
            val now = System.currentTimeMillis()
            val telemetry = telemetryRepository.telemetryState.value.latestTelemetry
            val fresh = telemetry != null && now - telemetry.capturedAtMillis <= STALE_SAMPLE_MS
            val newSample = fresh && telemetry!!.capturedAtMillis != lastSampleAt
            if (newSample) lastSampleAt = telemetry!!.capturedAtMillis
            val moving = fresh && telemetry!!.isMovingTarget()
            val present = fresh && telemetry!!.toOccupancyDecision().occupied

            when (phase) {
                EventPhase.IDLE -> if (newSample) {
                    consecutiveMoving = if (moving) consecutiveMoving + 1 else 0
                    if (consecutiveMoving >= START_SAMPLES) {
                        consecutiveMoving = 0
                        startEvent(now)
                    }
                }
                EventPhase.MOTION -> if (!present) {
                    // Like MOT:0 — start the grace timer (only if one isn't already running).
                    phase = EventPhase.GRACE
                    graceDeadline = now + GRACE_MS
                    stateHolder.update { it.copy(phase = EventPhase.GRACE) }
                }
                EventPhase.GRACE -> when {
                    present -> {
                        // Like MOT:1 during grace — cancel the timer, same event continues.
                        phase = EventPhase.MOTION
                        stateHolder.update { it.copy(phase = EventPhase.MOTION) }
                    }
                    now >= graceDeadline -> endEvent()
                }
            }
            delay(POLL_MS)
        }
    }

    private fun startEvent(now: Long) {
        val id = newEventId(now)
        eventId = id
        phase = EventPhase.MOTION
        personSeen = false
        verdict = EventResult.NO_PERSON
        bestScore = null
        strangerPhoto = null
        eventTorch = false
        deterrentSpoken = false
        releaseCameraAfterAlarm = false
        eventStore.create(id, now)
        stateHolder.update { it.copy(phase = EventPhase.MOTION, activeEventId = id, recording = false, torchOn = false, statusMessage = null) }
        Log.i(TAG, "Event $id started")
        eventJob = scope.launch { runEventChecks(id) }
    }

    private suspend fun endEvent() {
        val id = eventId ?: return
        eventJob?.cancelAndJoin()
        clipJob?.cancelAndJoin()
        eventJob = null
        clipJob = null
        val controller = controllerProvider()
        controller?.stopRecording()
        eventTorch = false
        if (alarm.alarmActive) {
            // Keep the camera bound so the strobe keeps flashing until Dismiss.
            releaseCameraAfterAlarm = true
        } else {
            controller?.endSecuritySession(keepImageBound = perceptionActive())
        }

        val endedAt = System.currentTimeMillis()
        when (verdict) {
            EventResult.FAMILY, EventResult.NO_PERSON -> eventStore.delete(id)
            EventResult.NO_FACE, EventResult.STRANGER -> {
                eventStore.update(id) { it.copy(endedAtMillis = endedAt, result = verdict, bestScore = bestScore, torchUsed = it.torchUsed, strangerPhoto = strangerPhoto) }
                publishEventSummary(id, endedAt)
            }
        }
        eventStore.applyRetention(activeId = null)
        Log.i(TAG, "Event $id ended: $verdict")

        eventId = null
        phase = EventPhase.IDLE
        consecutiveMoving = 0
        stateHolder.update { it.copy(phase = EventPhase.IDLE, activeEventId = null, recording = false, torchOn = false) }
    }

    // ── Per-event photo checks ───────────────────────────────────────────────────────────────

    private suspend fun runEventChecks(id: String) {
        val controller = controllerProvider() ?: return
        if (!controller.beginSecuritySession()) {
            stateHolder.update { it.copy(statusMessage = "Camera unavailable. Open ReWatt to resume CCTV.") }
            notifier.showResumeHint()
            return
        }

        var photoIndex = 0
        var darkRoom: Boolean? = null
        while (scope.isActive) {
            val started = SystemClock.elapsedRealtime()
            val frame = controller.captureForSecurity()
            if (frame != null) {
                if (darkRoom == null) darkRoom = frame.lightsOn == false
                val index = ++photoIndex
                val decided = try {
                    withContext(Dispatchers.Default) { checkFrame(id, frame, index, controller, darkRoom == true) }
                } catch (cancel: kotlinx.coroutines.CancellationException) {
                    throw cancel
                } catch (error: Throwable) {
                    Log.e(TAG, "Security check failed for photo $index", error)
                    false
                }
                frame.bitmap.recycle()
                if (decided) return
            }
            delay((CHECK_INTERVAL_MS - (SystemClock.elapsedRealtime() - started)).coerceAtLeast(MIN_GAP_MS))
        }
    }

    /** Returns true when the event is decided (FAMILY or STRANGER) and checking should stop. */
    private suspend fun checkFrame(
        id: String,
        frame: SecurityFrame,
        index: Int,
        controller: CameraCaptureController,
        dark: Boolean,
    ): Boolean {
        // Faces first (SCRFD ≈ 0.1 s): a visible face already proves a person is there, so the slower
        // YOLO person check only runs when no face is found. Same Lane 2 outcome.
        val result = runCatching { recognizer.analyze(frame.bitmap, settings.matchThreshold) }
            .onFailure { Log.e(TAG, "Face check failed", it) }
            .getOrNull() ?: return false

        if (result !is FaceCheckResult.AllFamily && !personSeen) {
            // A face (stranger) or — below — a YOLO-confirmed person: start recording evidence once per event.
            if (result is FaceCheckResult.NoFace && !personInFrame(frame.bitmap)) {
                reportCheck(EventResult.NO_PERSON, null)
                return false
            }
            personSeen = true
            verdict = EventResult.NO_FACE
            startClip(id, controller, dark)
        }

        return when (result) {
            FaceCheckResult.NoFace -> {
                eventStore.savePhoto(id, index, frame.bitmap)
                reportCheck(EventResult.NO_FACE, null)
                // Person but no visible face (back turned, fully covered, too far): deterrent voice
                // once per event, no siren. A stranger's face later still triggers the full alarm.
                if (!deterrentSpoken) {
                    deterrentSpoken = true
                    withContext(Dispatchers.Main) { alarm.announceRecording() }
                }
                false
            }
            is FaceCheckResult.AllFamily -> {
                // ✓ registered family → silent. Stop and discard the recording.
                verdict = EventResult.FAMILY
                bestScore = result.bestScore
                reportCheck(EventResult.FAMILY, result.bestScore, result.confidence)
                clipJob?.cancel()
                controller.stopRecording()
                controller.setTorch(false)
                eventTorch = false
                stateHolder.update { it.copy(recording = false, torchOn = false) }
                true
            }
            is FaceCheckResult.Stranger -> {
                // ✗ unknown or masked face → alert.
                verdict = EventResult.STRANGER
                bestScore = result.bestScore
                val annotated = recognizer.annotate(frame.bitmap, result.faces)
                val photoName = eventStore.savePhoto(id, index, annotated)
                strangerPhoto = photoName
                eventStore.update(id) { it.copy(result = EventResult.STRANGER, bestScore = result.bestScore, strangerPhoto = photoName) }
                reportCheck(EventResult.STRANGER, result.bestScore, result.confidence)
                withContext(Dispatchers.Main) {
                    alarm.trigger(
                        eventId = id,
                        roomId = mqtt.roomId,
                        photoName = photoName,
                        score = result.bestScore,
                        torch = { on -> controller.setTorch(on) },
                        torchRestore = { controller.setTorch(eventTorch) },
                    )
                }
                publishIntrusion(id, annotated, result)
                annotated.recycle()
                true
            }
        }
    }

    /** YOLO person check. If YOLO hasn't loaded yet, assume a person so a possible intruder isn't skipped. */
    private fun personInFrame(bitmap: Bitmap): Boolean {
        val yolo = yoloProvider() ?: return true
        val started = SystemClock.elapsedRealtime()
        return runCatching { PersonCheck.findPersons(yolo, bitmap).isNotEmpty() }
            .onFailure { Log.e(TAG, "YOLO person check failed", it) }
            .getOrDefault(true)
            .also { Log.i(TAG, "YOLO person=$it in ${SystemClock.elapsedRealtime() - started} ms") }
    }

    private suspend fun startClip(id: String, controller: CameraCaptureController, dark: Boolean) {
        if (!controller.startRecording(eventStore.clipFile(id, 1), withAudio = true)) return
        if (dark) {
            // Torch only when the room is dark; re-request after binding (some devices drop it).
            eventTorch = true
            eventStore.update(id) { it.copy(torchUsed = true) }
            controller.setTorch(true)
            delay(TORCH_RETRY_MS)
            controller.setTorch(true)
        }
        stateHolder.update { it.copy(recording = true, torchOn = eventTorch) }
        clipJob = scope.launch {
            var part = 1
            while (isActive) {
                delay(MAX_CLIP_MS)
                part++
                controller.startRecording(eventStore.clipFile(id, part), withAudio = true)
            }
        }
    }

    private fun reportCheck(result: EventResult, score: Float?, confidence: Float? = null) {
        stateHolder.update { it.copy(lastCheck = LastFaceCheck(System.currentTimeMillis(), result, score, confidence)) }
    }

    // ── MQTT ─────────────────────────────────────────────────────────────────────────────────

    private fun publishIntrusion(id: String, annotated: Bitmap, result: FaceCheckResult.Stranger) {
        val jpeg = ByteArrayOutputStream().also { out ->
            val small = annotated.scaledToMaxSide(MQTT_IMAGE_MAX_SIDE)
            small.compress(Bitmap.CompressFormat.JPEG, 70, out)
            if (small !== annotated) small.recycle()
        }.toByteArray()
        val payload = JSONObject().apply {
            put("eventId", id)
            put("nodeId", mqtt.nodeId)
            put("roomId", mqtt.roomId)
            put("timestamp", System.currentTimeMillis())
            put("faceCount", result.faces.size)
            put("bestScore", result.bestScore.toDouble())
            put("confidence", result.confidence.toDouble())
            put("imageFormat", "jpeg")
            put("imageBase64", Base64.encodeToString(jpeg, Base64.NO_WRAP))
        }
        if (mqtt.publishJson(mqtt.topicForRoom(mqtt.roomId, "intrusion"), payload.toString())) {
            eventStore.update(id) { it.copy(mqttSent = true) }
        }
    }

    private fun publishEventSummary(id: String, endedAt: Long) {
        val event = eventStore.events.value.firstOrNull { it.id == id } ?: return
        val payload = JSONObject().apply {
            put("eventId", id)
            put("nodeId", mqtt.nodeId)
            put("roomId", mqtt.roomId)
            put("start", event.startedAtMillis)
            put("end", endedAt)
            put("result", event.result.name)
            put("bestScore", event.bestScore?.toDouble() ?: JSONObject.NULL)
        }
        mqtt.publishJson(mqtt.topicForRoom(mqtt.roomId, "security/event"), payload.toString())
    }

    private fun newEventId(now: Long): String {
        val base = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(now))
        var id = base
        var n = 2
        while (File(eventStore.eventDir(id), "event.json").exists()) id = "${base}_${n++}"
        return id
    }

    companion object {
        private const val TAG = "IntrusionMonitor"
        private const val POLL_MS = 100L
        private const val STALE_SAMPLE_MS = 1_500L
        private const val START_SAMPLES = 2
        const val GRACE_MS = 5_000L
        private const val CHECK_INTERVAL_MS = 2_000L
        private const val MIN_GAP_MS = 300L
        private const val TORCH_RETRY_MS = 300L
        private const val MAX_CLIP_MS = 5 * 60 * 1000L
        private const val MQTT_IMAGE_MAX_SIDE = 960
    }
}
