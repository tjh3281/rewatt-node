package com.rewatt.app.security

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rewatt.app.R
import com.rewatt.app.ReWattApplication
import com.rewatt.app.perception.CameraCaptureService

/** Red full-screen alarm ([AlarmFullScreen]) shown over the lock screen with one big DISMISS button. */
class IntrusionAlertActivity : ComponentActivity() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showOverLockScreen()

        val graph = (application as ReWattApplication).appGraph
        val roomId = intent.getStringExtra(EXTRA_ROOM_ID).orEmpty()

        if (intent.getStringExtra(EXTRA_KIND) == KIND_FIRE) {
            val voc = intent.getIntExtra(EXTRA_VOC, -1).takeIf { it >= 0 }
            val nox = intent.getIntExtra(EXTRA_NOX, -1).takeIf { it >= 0 }
            val test = intent.getBooleanExtra(EXTRA_TEST, false)
            setContent {
                val fire by graph.fireMonitor.alarm.state.collectAsStateWithLifecycle()
                LaunchedEffect(fire.active) {
                    if (!fire.active) finish()
                }
                AlarmFullScreen(
                    kind = AlarmKind.FIRE,
                    heading = stringResource(R.string.fire_alert_heading),
                    message = stringResource(R.string.fire_alert_text, roomId),
                    photoPath = null,
                    startedAtMillis = fire.atMillis,
                    detail = fireAlarmDetail(test, voc, nox),
                    onDismiss = {
                        graph.fireMonitor.dismiss()
                        finish()
                    },
                )
            }
            return
        }

        val eventId = intent.getStringExtra(EXTRA_EVENT_ID).orEmpty()
        val photoName = intent.getStringExtra(EXTRA_PHOTO)
        val score = intent.getFloatExtra(EXTRA_SCORE, Float.NaN).takeUnless { it.isNaN() }
        val photoPath = photoName?.let { graph.securityEventStore.photoFile(eventId, it).absolutePath }

        setContent {
            val state by graph.securityStateHolder.state.collectAsStateWithLifecycle()
            LaunchedEffect(state.alarmActive) {
                if (!state.alarmActive) finish()
            }
            AlarmFullScreen(
                kind = AlarmKind.INTRUDER,
                heading = stringResource(R.string.cctv_alert_heading),
                message = stringResource(R.string.cctv_alert_text, roomId),
                photoPath = photoPath,
                startedAtMillis = state.alarmStartedAtMillis,
                detail = intruderAlarmDetail(score),
                onDismiss = {
                    if (graph.securitySettings.armed) {
                        // The service also releases the camera / records the dismissal on the event.
                        startService(CameraCaptureService.dismissAlarmIntent(this))
                    } else {
                        graph.intrusionAlarm.dismiss() // e.g. Test alarm while disarmed
                    }
                    finish()
                },
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Same trick as the old PIR project: a short bright wake lock guarantees the panel lights up.
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "rewatt:intrusion")
            .also { runCatching { it.acquire(WAKE_MS) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // singleTask: a second alert (e.g. fire while the intruder screen is up) replaces the content.
        setIntent(intent)
        recreate()
    }

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) runCatching { it.release() } }
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_EVENT_ID = "event_id"
        private const val EXTRA_ROOM_ID = "room_id"
        private const val EXTRA_PHOTO = "photo"
        private const val EXTRA_SCORE = "score"
        private const val EXTRA_KIND = "kind"
        private const val EXTRA_VOC = "voc"
        private const val EXTRA_NOX = "nox"
        private const val EXTRA_TEST = "test"
        private const val KIND_FIRE = "fire"
        private const val WAKE_MS = 8_000L

        fun fireIntent(context: Context, roomId: String, vocIndex: Int?, noxIndex: Int?, test: Boolean): Intent =
            Intent(context, IntrusionAlertActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
                .putExtra(EXTRA_KIND, KIND_FIRE)
                .putExtra(EXTRA_ROOM_ID, roomId)
                .putExtra(EXTRA_VOC, vocIndex ?: -1)
                .putExtra(EXTRA_NOX, noxIndex ?: -1)
                .putExtra(EXTRA_TEST, test)

        fun intent(context: Context, eventId: String, roomId: String, photoName: String?, score: Float?): Intent =
            Intent(context, IntrusionAlertActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
                .putExtra(EXTRA_EVENT_ID, eventId)
                .putExtra(EXTRA_ROOM_ID, roomId)
                .putExtra(EXTRA_PHOTO, photoName)
                .putExtra(EXTRA_SCORE, score ?: Float.NaN)
    }
}
