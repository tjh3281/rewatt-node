package com.rewatt.app.security

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * CCTV event engine ("Lane 2: Security camera"), running inside the camera foreground service so it
 * works with the screen off.
 *
 * Event timing follows the old PIR project:
 *   IDLE ──2 moving radar samples──▶ MOTION ──no presence──▶ GRACE(5 s) ──5 s quiet──▶ IDLE
 *                                      ▲───────── presence again ─────┘
 *
 * Without the reWatt module (no radar sample for 10 s) the camera stands in for the radar: while idle
 * the live stream is face-checked every 0.3 s (a face twice in a row starts the event) with a YOLO person
 * check in the background, and during the event a face, or a YOLO person when no face is in view, counts
 * as presence.
 *
 * During an event the camera streams live ~1280×960 frames ([CameraCaptureController.beginLiveSecurity]).
 * Every frame goes through SCRFD; [FaceTracker] follows each face and asks for InsightFace recognition
 * only for new or undecided faces:
 *   face registered (FAMILY)          → silent; any clip is stopped and the event is deleted
 *   face unknown / masked for ~1 s    → torch strobe + "You are being recorded" + siren + red alert + MQTT
 *   no face for ~4 s → YOLO: person   → record (clip kept as evidence) + "You are being recorded"
 *                            no person→ keep watching (nothing recorded)
 * Checking continues for the whole event, so a stranger who follows a family member still raises the alarm.
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

    /** Event state changes run one at a time here (poll loop + results posted from the live frames). */
    private val eventDispatcher = Dispatchers.Default.limitedParallelism(1)

    private var pollJob: Job? = null
    private var eventJob: Job? = null
    private var clipJob: Job? = null

    // Event state (changed on [eventDispatcher]; read by the live-frame thread).
    private var phase = EventPhase.IDLE
    private var consecutiveMoving = 0
    private var lastSampleAt = 0L
    private var graceDeadline = 0L
    @Volatile private var eventId: String? = null
    @Volatile private var verdict = EventResult.NO_PERSON
    private var bestScore: Float? = null
    private var strangerPhoto: String? = null
    @Volatile private var eventTorch = false
    private var deterrentSpoken = false
    private var releaseCameraAfterAlarm = false

    /** The running live stream of the current event; frames from an older session are dropped. */
    @Volatile private var live: LiveSession? = null

    /**
     * No reWatt module (no radar sample for [MODULE_ABSENT_MS]): the camera itself watches while idle
     * ([CameraWatch], YOLO person check) and decides presence during an event.
     */
    @Volatile private var cameraOnly = false
    @Volatile private var watch: CameraWatch? = null
    private var watchRetryAt = 0L

    private class CameraWatch {
        var lastCheckAt = 0L
        var faceHits = 0
        var lastYoloAt = 0L
        val yoloRunning = AtomicBoolean(false)
    }

    private class LiveSession(val eventId: String, val controller: CameraCaptureController) {
        val tracker = FaceTracker()
        val startedAt = SystemClock.elapsedRealtime()
        var dark: Boolean? = null
        var faceSeen = false
        var lastFaceAt = startedAt
        var lastPersonCheckAt = startedAt
        var lastPhotoAt = 0L
        /** Camera-only mode: last time a face or a YOLO person was seen (stands in for radar presence). */
        @Volatile var lastPresenceAt = startedAt
        var lastPresenceCheckAt = startedAt
        val photoIndex = AtomicInteger(0)
        val strangerHandled = AtomicBoolean(false)
        val personCheckRunning = AtomicBoolean(false)
        /** The alarm was dismissed: from this time (elapsedRealtime) faces are judged afresh and can ring it again. 0 = none. */
        @Volatile var rearmAt = 0L

        // Speed stats, logged every [STATS_LOG_MS].
        var fps: Float? = null
        var lastFrameAt = 0L
        var statSince = startedAt
        var statFrames = 0
        var statDetectMs = 0L
        var statRecognizeMs = 0L
        var statRecognitions = 0
    }

    val isRunning: Boolean get() = pollJob?.isActive == true

    fun start() {
        if (isRunning) return
        armedWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "rewatt:cctv-armed")
            .also { runCatching { it.acquire() } }
        notifier.ensureChannels()
        scope.launch(Dispatchers.Default) { recognizer.warmUp() }
        stateHolder.update { it.copy(armed = true, phase = EventPhase.IDLE, statusMessage = null) }
        pollJob = scope.launch(eventDispatcher) { pollLoop() }
    }

    suspend fun stop() {
        pollJob?.cancelAndJoin()
        pollJob = null
        withContext(eventDispatcher) {
            stopCameraWatch()
            if (eventId != null) endEvent()
            if (alarm.alarmActive) {
                alarm.dismiss()
                onAlarmDismissed()
            }
        }
        armedWakeLock?.let { if (it.isHeld) runCatching { it.release() } }
        armedWakeLock = null
        phase = EventPhase.IDLE
        consecutiveMoving = 0
        stateHolder.update {
            it.copy(armed = false, phase = EventPhase.IDLE, activeEventId = null, recording = false, torchOn = false, liveActive = false, liveFaces = emptyList(), liveFps = null)
        }
    }

    /** Immediate, non-suspending teardown for service destruction (the camera is closed by the service). */
    fun abort() {
        live = null
        watch = null
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

    /**
     * Called after the user dismisses the alarm (red screen or notification). If the event is still
     * running, the alarm is re-armed after [REARM_AFTER_DISMISS_MS]: faces are judged afresh, so an unknown
     * face still (or again) in view rings it again — dismissing never silences the rest of the event.
     */
    suspend fun onAlarmDismissed() {
        stateHolder.state.value.alarmEventId?.let { id ->
            eventStore.update(id) { it.copy(alarmDismissedAtMillis = System.currentTimeMillis()) }
        }
        live?.let { it.rearmAt = SystemClock.elapsedRealtime() + REARM_AFTER_DISMISS_MS }
        if (releaseCameraAfterAlarm && eventId == null) {
            releaseCameraAfterAlarm = false
            controllerProvider()?.endLiveSecurity(keepImageBound = perceptionActive())
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
            val moduleAbsent = telemetry == null || now - telemetry.capturedAtMillis > MODULE_ABSENT_MS
            if (moduleAbsent != cameraOnly) {
                cameraOnly = moduleAbsent
                consecutiveMoving = 0
                Log.i(TAG, if (moduleAbsent) "No reWatt module: the camera watches for people" else "reWatt module back: radar wakes the camera")
            }
            // Someone is here: the camera saw a face or a person recently, or (with the module) the radar
            // says the room is occupied. The camera counts with the module too, so a person the radar
            // misses (standing still, out of its range) keeps the face check going.
            val cameraPresent = live?.let { SystemClock.elapsedRealtime() - it.lastPresenceAt <= CAMERA_PRESENCE_HOLD_MS } ?: false
            val present = cameraPresent || (!cameraOnly && fresh && telemetry!!.toOccupancyDecision().occupied)

            when (phase) {
                // The camera always watches while armed (face twice in a row, or a YOLO person, starts the
                // face check), module or not; with the module, radar movement can also start it.
                EventPhase.IDLE -> {
                    if (watch == null && !alarm.alarmActive && now >= watchRetryAt) startCameraWatch()
                    if (!cameraOnly && newSample) {
                        consecutiveMoving = if (moving) consecutiveMoving + 1 else 0
                        if (consecutiveMoving >= START_SAMPLES && phase == EventPhase.IDLE) {
                            consecutiveMoving = 0
                            startEvent(now)
                        }
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

    // ── Camera watch (no module) ─────────────────────────────────────────────────────────────

    /** Stream live frames while idle and YOLO-check one every [WATCH_INTERVAL_MS]; must run on [eventDispatcher]. */
    private suspend fun startCameraWatch() {
        val controller = controllerProvider() ?: return
        val w = CameraWatch()
        watch = w
        if (!controller.beginLiveSecurity { frame -> onWatchFrame(w, frame) }) {
            watch = null
            watchRetryAt = System.currentTimeMillis() + WATCH_RETRY_MS
            Log.w(TAG, "Camera watch could not start")
            return
        }
        Log.i(TAG, "Camera watch started")
    }

    private suspend fun stopCameraWatch() {
        if (watch == null) return
        watch = null
        controllerProvider()?.endLiveSecurity(keepImageBound = perceptionActive())
        Log.i(TAG, "Camera watch stopped")
    }

    /**
     * Live-analysis thread; owns [frame]'s bitmap. A face (SCRFD, fast) in [START_SAMPLES] checks in a row
     * starts an event; a YOLO person check runs in the background every [WATCH_YOLO_MS] for someone whose
     * face isn't visible (one hit starts an event).
     */
    private fun onWatchFrame(w: CameraWatch, frame: LiveFrame) {
        val bitmap = frame.bitmap
        var bitmapHandedOff = false
        try {
            val now = SystemClock.elapsedRealtime()
            if (watch !== w || now - w.lastCheckAt < WATCH_INTERVAL_MS) return
            w.lastCheckAt = now
            val faces = runCatching { recognizer.detect(bitmap) }
                .onFailure { Log.e(TAG, "Watch face check failed", it) }
                .getOrDefault(emptyList())
            w.faceHits = if (faces.isNotEmpty()) w.faceHits + 1 else 0
            if (w.faceHits >= START_SAMPLES) {
                w.faceHits = 0
                Log.i(TAG, "Camera watch: face in view")
                startEventFromWatch(w)
                return
            }
            // Unlike the in-event check, an unloaded YOLO counts as "nobody" here, so no false events.
            if (faces.isEmpty() && yoloProvider() != null && now - w.lastYoloAt >= WATCH_YOLO_MS &&
                w.yoloRunning.compareAndSet(false, true)
            ) {
                w.lastYoloAt = now
                bitmapHandedOff = true
                scope.launch(Dispatchers.Default) {
                    try {
                        if (watch === w && personInFrame(bitmap)) startEventFromWatch(w)
                    } finally {
                        bitmap.recycle()
                        w.yoloRunning.set(false)
                    }
                }
            }
        } finally {
            if (!bitmapHandedOff) bitmap.recycle()
        }
    }

    private fun startEventFromWatch(w: CameraWatch) {
        scope.launch(eventDispatcher) {
            if (watch === w && phase == EventPhase.IDLE) startEvent(System.currentTimeMillis())
        }
    }

    private fun startEvent(now: Long) {
        // The camera watch hands its live stream straight to the event (same analysis, new analyser).
        watch = null
        val id = newEventId(now)
        eventId = id
        phase = EventPhase.MOTION
        verdict = EventResult.NO_PERSON
        bestScore = null
        strangerPhoto = null
        eventTorch = false
        deterrentSpoken = false
        releaseCameraAfterAlarm = false
        eventStore.create(id, now)
        stateHolder.update { it.copy(phase = EventPhase.MOTION, activeEventId = id, recording = false, torchOn = false, statusMessage = null) }
        Log.i(TAG, "Event $id started")
        // A person is confirmed (second detection in a row): the "You are being recorded" pop-up comes first.
        alarm.showRecordingNotice()
        eventJob = scope.launch(eventDispatcher) { startLiveSession(id) }
    }

    private suspend fun endEvent() {
        val id = eventId ?: return
        val session = live
        live = null
        eventJob?.cancelAndJoin()
        clipJob?.cancelAndJoin()
        eventJob = null
        clipJob = null
        val controller = controllerProvider()
        controller?.stopRecording()
        eventTorch = false
        if (alarm.alarmActive) {
            // Keep the camera bound so the strobe keeps flashing until Dismiss; just stop the frames.
            controller?.pauseLiveFrames()
            releaseCameraAfterAlarm = true
        } else {
            controller?.endLiveSecurity(keepImageBound = perceptionActive())
        }
        session?.let { logStats(it, final = true) }

        // A face that came and went before it could be decided is kept as "person, no face" evidence.
        if (verdict == EventResult.NO_PERSON && session?.faceSeen == true) verdict = EventResult.NO_FACE

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
        stateHolder.update {
            it.copy(phase = EventPhase.IDLE, activeEventId = null, recording = false, torchOn = false, liveActive = false, liveFaces = emptyList(), liveFps = null)
        }
    }

    // ── Live face check ──────────────────────────────────────────────────────────────────────

    private suspend fun startLiveSession(id: String) {
        val controller = controllerProvider() ?: return
        val session = LiveSession(id, controller)
        live = session
        if (!controller.beginLiveSecurity { frame -> onLiveFrame(session, frame) }) {
            live = null
            stateHolder.update { it.copy(statusMessage = "Camera unavailable. Open ReWatt to resume CCTV.") }
            notifier.showResumeHint()
            return
        }
        stateHolder.update { it.copy(liveActive = true) }
    }

    /** Runs on the camera's live-analysis thread, one frame at a time; owns [frame]'s bitmap. */
    private fun onLiveFrame(session: LiveSession, frame: LiveFrame) {
        var bitmapHandedOff = false
        try {
            if (live !== session) return
            val bitmap = frame.bitmap
            if (session.dark == null) onFirstFrame(session, frame)
            // Re-arm after a dismissal (here, on the analysis thread that owns the tracker).
            val rearmAt = session.rearmAt
            if (rearmAt != 0L && SystemClock.elapsedRealtime() >= rearmAt) {
                session.rearmAt = 0L
                session.tracker.reset()
                session.strangerHandled.set(false)
                Log.i(TAG, "Alarm dismissed: watching for an unknown face again")
            }

            val started = SystemClock.elapsedRealtime()
            val faces = recognizer.detect(bitmap)
            val detected = SystemClock.elapsedRealtime()
            val threshold = settings.matchThreshold
            val step = session.tracker.step(faces.map { it.box.toFaceBox() }, detected) { i ->
                runCatching { recognizer.recognize(bitmap, faces[i], threshold) }
                    .onFailure { Log.e(TAG, "Live recognition failed", it) }
                    .getOrNull()
                    ?.let { TrackCheck(it.isFamily, it.similarity, it.confidence, it.name) }
            }
            val now = SystemClock.elapsedRealtime()
            recordStats(session, started, detected, now, step.recognitions)
            publishLive(session, step, bitmap.width, bitmap.height)

            if (step.faces.isNotEmpty()) {
                session.lastFaceAt = now
                session.lastPresenceAt = now
                if (!session.faceSeen) {
                    session.faceSeen = true
                    saveEvidencePhoto(session, bitmap, step.faces, now)
                }
            }
            if (step.newlyFamily.isNotEmpty()) {
                val family = step.newlyFamily.maxBy { it.similarity ?: 0f }
                scope.launch(eventDispatcher) { onFamily(session, family) }
            }
            if (step.newlyUnknown.isNotEmpty() && session.strangerHandled.compareAndSet(false, true)) {
                // The intruder's facial embedding (from this clean frame, before labels are drawn on it).
                val unknown = step.newlyUnknown.minBy { it.similarity ?: 0f }
                val embedding = faces
                    .minByOrNull { centreDistance(it.box.toFaceBox(), unknown.box) }
                    ?.let { face -> runCatching { recognizer.embedding(bitmap, face) }.getOrNull() }
                val annotated = recognizer.annotateLabels(bitmap, step.faces.map { it.toLabel() })
                scope.launch(eventDispatcher) { onStranger(session, annotated, step, embedding) }
            } else if (verdict == EventResult.NO_FACE || verdict == EventResult.STRANGER) {
                if (now - session.lastPhotoAt >= PHOTO_INTERVAL_MS) saveEvidencePhoto(session, bitmap, step.faces, now)
            }

            // Radar (or the camera) says someone is here but no face has shown for a while: is it a person at all?
            val personCheck = verdict == EventResult.NO_PERSON &&
                step.faces.isEmpty() &&
                now - session.lastFaceAt >= NO_FACE_PERSON_CHECK_MS &&
                now - session.lastPersonCheckAt >= NO_FACE_PERSON_CHECK_MS
            // With no face in view, YOLO checks whether someone is still there (with or without the module).
            val presenceCheck = step.faces.isEmpty() &&
                now - session.lastPresenceCheckAt >= CAMERA_PRESENCE_CHECK_MS
            if ((personCheck || presenceCheck) && session.personCheckRunning.compareAndSet(false, true)) {
                if (personCheck) session.lastPersonCheckAt = now
                session.lastPresenceCheckAt = now
                bitmapHandedOff = true
                scope.launch(Dispatchers.Default) {
                    try {
                        val person = live === session && personInFrame(bitmap)
                        // Seen when the frame was taken, not when the (slow) check finished.
                        if (person) session.lastPresenceAt = maxOf(session.lastPresenceAt, now)
                        if (person && personCheck) {
                            withContext(eventDispatcher) { onPersonWithoutFace(session, bitmap) }
                        }
                    } finally {
                        bitmap.recycle()
                        session.personCheckRunning.set(false)
                    }
                }
            }
        } catch (error: Throwable) {
            Log.e(TAG, "Live face check failed", error)
        } finally {
            if (!bitmapHandedOff) frame.bitmap.recycle()
        }
    }

    /** Dark room (by frame brightness, there is no EXIF EV for live frames): torch on so faces can be seen. */
    private fun onFirstFrame(session: LiveSession, frame: LiveFrame) {
        val dark = frame.meanLuma < DARK_LUMA
        session.dark = dark
        Log.i(TAG, "Live check started: ${frame.bitmap.width}×${frame.bitmap.height}, mean luma %.0f, dark=$dark".format(frame.meanLuma))
        if (!dark) return
        eventTorch = true
        session.controller.setTorch(true)
        eventStore.update(session.eventId) { it.copy(torchUsed = true) }
        stateHolder.update { it.copy(torchOn = true) }
    }

    /** ✓ a registered person: silent. A "person, no face" clip so far is stopped (deleted at event end). */
    private suspend fun onFamily(session: LiveSession, face: TrackedFace) {
        if (live !== session) return
        if (verdict != EventResult.NO_PERSON && verdict != EventResult.NO_FACE) return
        verdict = EventResult.FAMILY
        bestScore = face.similarity
        reportCheck(EventResult.FAMILY, face.similarity, face.confidence)
        Log.i(TAG, "Live check: ${face.name ?: "family"} recognised (track ${face.trackId})")
        clipJob?.cancel()
        clipJob = null
        session.controller.stopRecording()
        stateHolder.update { it.copy(recording = false) }
    }

    /** ✗ a face stayed unknown (or masked) for ~1 s: evidence, recording, alarm, MQTT. */
    private suspend fun onStranger(session: LiveSession, annotated: Bitmap, step: TrackStep, embedding: FloatArray?) {
        try {
            if (live !== session) return
            val unknown = step.newlyUnknown.minBy { it.similarity ?: 0f }
            val id = session.eventId
            verdict = EventResult.STRANGER
            bestScore = unknown.similarity
            // Kept with the event's evidence (photo, clip) for a police report.
            embedding?.let {
                eventStore.saveFaceEmbedding(id, it)
                Log.i(TAG, "Intruder facial embedding stored (${it.size} values)")
            }
            val photoName = eventStore.savePhoto(id, session.photoIndex.incrementAndGet(), annotated)
            session.lastPhotoAt = SystemClock.elapsedRealtime()
            strangerPhoto = photoName
            eventStore.update(id) { it.copy(result = EventResult.STRANGER, bestScore = unknown.similarity, strangerPhoto = photoName) }
            reportCheck(EventResult.STRANGER, unknown.similarity, unknown.confidence)
            Log.i(TAG, "Live check: unknown face (track ${unknown.trackId}, cosine ${unknown.similarity})")
            if (!session.controller.isRecording) startClip(id, session.controller)
            withContext(Dispatchers.Main) {
                alarm.trigger(
                    eventId = id,
                    roomId = mqtt.roomId,
                    photoName = photoName,
                    score = unknown.similarity,
                    torch = { on -> session.controller.setTorch(on) },
                    torchRestore = { session.controller.setTorch(eventTorch) },
                )
            }
            publishIntrusion(id, annotated, step.faces, unknown)
        } finally {
            annotated.recycle()
        }
    }

    /** Person but no visible face (back turned, fully covered, too far): record + deterrent voice, no siren. */
    private suspend fun onPersonWithoutFace(session: LiveSession, bitmap: Bitmap) {
        if (live !== session || verdict != EventResult.NO_PERSON) return
        verdict = EventResult.NO_FACE
        reportCheck(EventResult.NO_FACE, null)
        saveEvidencePhoto(session, bitmap, emptyList(), SystemClock.elapsedRealtime())
        startClip(session.eventId, session.controller)
        if (!deterrentSpoken) {
            deterrentSpoken = true
            withContext(Dispatchers.Main) { alarm.announceRecording() }
        }
    }

    private fun saveEvidencePhoto(session: LiveSession, bitmap: Bitmap, faces: List<TrackedFace>, now: Long) {
        session.lastPhotoAt = now
        val index = session.photoIndex.incrementAndGet()
        if (faces.isEmpty()) {
            eventStore.savePhoto(session.eventId, index, bitmap)
        } else {
            val annotated = recognizer.annotateLabels(bitmap, faces.map { it.toLabel() })
            eventStore.savePhoto(session.eventId, index, annotated)
            annotated.recycle()
        }
    }

    private fun publishLive(session: LiveSession, step: TrackStep, width: Int, height: Int) {
        val w = width.toFloat()
        val h = height.toFloat()
        val faces = step.faces.map {
            LiveFaceUi(it.trackId, it.box.left / w, it.box.top / h, it.box.right / w, it.box.bottom / h, it.status, it.name, it.confidence)
        }
        stateHolder.update { it.copy(liveActive = true, liveFaces = faces, liveFps = session.fps, liveFrameWidth = width, liveFrameHeight = height) }
    }

    private fun recordStats(session: LiveSession, started: Long, detected: Long, done: Long, recognitions: Int) {
        if (session.lastFrameAt > 0L) {
            val instant = 1000f / (started - session.lastFrameAt).coerceAtLeast(1L)
            session.fps = session.fps?.let { it + FPS_SMOOTHING * (instant - it) } ?: instant
        }
        session.lastFrameAt = started
        session.statFrames++
        session.statDetectMs += detected - started
        session.statRecognizeMs += done - detected
        session.statRecognitions += recognitions
        if (done - session.statSince >= STATS_LOG_MS) logStats(session, final = false)
    }

    private fun logStats(session: LiveSession, final: Boolean) {
        val elapsed = (SystemClock.elapsedRealtime() - session.statSince).coerceAtLeast(1L)
        val frames = session.statFrames.coerceAtLeast(1)
        Log.i(
            TAG,
            "Live${if (final) " (end)" else ""}: %.1f fps, detect %d ms/frame, recognise %d ms/frame (%d runs), %d face(s) tracked".format(
                session.statFrames * 1000f / elapsed,
                session.statDetectMs / frames,
                session.statRecognizeMs / frames,
                session.statRecognitions,
                session.tracker.activeCount,
            ),
        )
        session.statSince = SystemClock.elapsedRealtime()
        session.statFrames = 0
        session.statDetectMs = 0
        session.statRecognizeMs = 0
        session.statRecognitions = 0
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

    private suspend fun startClip(id: String, controller: CameraCaptureController) {
        if (!controller.startRecording(eventStore.clipFile(id, 1), withAudio = true)) {
            Log.w(TAG, "No clip for event $id: video could not start beside the live check (photos only)")
            return
        }
        if (eventTorch) {
            // Some devices drop the torch when video binds; ask again.
            controller.setTorch(true)
            delay(TORCH_RETRY_MS)
            controller.setTorch(true)
        }
        stateHolder.update { it.copy(recording = true, torchOn = eventTorch) }
        clipJob = scope.launch(eventDispatcher) {
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

    private fun publishIntrusion(id: String, annotated: Bitmap, faces: List<TrackedFace>, unknown: TrackedFace) {
        val jpeg = ByteArrayOutputStream().also { out ->
            val small = annotated.scaledToMaxSide(MQTT_IMAGE_MAX_SIDE)
            small.compress(Bitmap.CompressFormat.JPEG, 70, out)
            if (small !== annotated) small.recycle()
        }.toByteArray()
        val faceList = JSONArray()
        faces.forEach { face ->
            faceList.put(
                JSONObject().apply {
                    put("status", face.status.name)
                    put("name", if (face.status == TrackStatus.FAMILY) face.name ?: JSONObject.NULL else JSONObject.NULL)
                    put("score", face.similarity?.toDouble() ?: JSONObject.NULL)
                },
            )
        }
        val payload = JSONObject().apply {
            put("eventId", id)
            put("nodeId", mqtt.nodeId)
            put("roomId", mqtt.roomId)
            put("timestamp", System.currentTimeMillis())
            put("faceCount", faces.size)
            put("bestScore", unknown.similarity?.toDouble() ?: JSONObject.NULL)
            put("confidence", unknown.confidence?.toDouble() ?: JSONObject.NULL)
            put("faces", faceList)
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

    private fun RectF.toFaceBox() = FaceBox(left, top, right, bottom)

    private fun centreDistance(a: FaceBox, b: FaceBox): Float {
        val dx = (a.left + a.right) / 2f - (b.left + b.right) / 2f
        val dy = (a.top + a.bottom) / 2f - (b.top + b.bottom) / 2f
        return dx * dx + dy * dy
    }

    private fun TrackedFace.toLabel(): FaceRecognizer.FaceLabel {
        val who = when (status) {
            TrackStatus.FAMILY -> name ?: FamilyFaceStore.FAMILY_LABEL
            TrackStatus.UNKNOWN -> "UNKNOWN"
            TrackStatus.PENDING -> "CHECKING"
        }
        val score = similarity?.let { " %.2f".format(it) }.orEmpty()
        return FaceRecognizer.FaceLabel(RectF(box.left, box.top, box.right, box.bottom), status == TrackStatus.FAMILY, who + score)
    }

    companion object {
        private const val TAG = "IntrusionMonitor"
        private const val POLL_MS = 100L
        private const val STALE_SAMPLE_MS = 1_500L

        /** No radar sample for this long → no reWatt module: the camera watches by itself. */
        private const val MODULE_ABSENT_MS = 10_000L

        /** Camera watch: face check this often while idle ([START_SAMPLES] hits in a row start an event). */
        private const val WATCH_INTERVAL_MS = 300L

        /** Camera watch: background YOLO person check (no face in view) at most this often. */
        private const val WATCH_YOLO_MS = 2_000L
        private const val WATCH_RETRY_MS = 5_000L

        /** Camera-only event: YOLO presence check this often while no face is in view. */
        private const val CAMERA_PRESENCE_CHECK_MS = 2_000L

        /**
         * Camera-only event: nobody seen for this long counts as "no presence" (then the 5 s grace). Long
         * enough to cover a slow YOLO check on an older or busy phone.
         */
        private const val CAMERA_PRESENCE_HOLD_MS = 10_000L
        private const val START_SAMPLES = 2
        const val GRACE_MS = 5_000L

        /** After DISMISS, a short pause before an unknown face can ring the alarm again. */
        private const val REARM_AFTER_DISMISS_MS = 3_000L
        private const val TORCH_RETRY_MS = 300L
        private const val MAX_CLIP_MS = 5 * 60 * 1000L
        private const val MQTT_IMAGE_MAX_SIDE = 960

        /** While evidence is kept (person / stranger), a photo is saved this often. */
        private const val PHOTO_INTERVAL_MS = 2_000L

        /** No face for this long while the radar sees someone → one YOLO person check (repeats at this pace). */
        private const val NO_FACE_PERSON_CHECK_MS = 4_000L

        /** Mean frame luma (0–255) below which the room counts as dark and the torch comes on. */
        private const val DARK_LUMA = 40f
        private const val FPS_SMOOTHING = 0.2f
        private const val STATS_LOG_MS = 5_000L
    }
}
