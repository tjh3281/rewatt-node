package com.rewatt.app.security

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.rewatt.app.R
import com.rewatt.app.core.LocaleManager
import java.util.Locale

/**
 * Speaks the deterrent "You are being recorded" on the ALARM stream (so it is audible on silent).
 * Uses Malay when the app language is Malay and a Malay voice is installed, otherwise English.
 */
class DeterrentVoice(context: Context) : TextToSpeech.OnInitListener {
    private val appContext = context.applicationContext
    private var tts: TextToSpeech? = null

    @Volatile
    private var ready = false
    private var pendingDone: (() -> Unit)? = null

    fun prepare() {
        if (tts == null) tts = TextToSpeech(appContext, this)
    }

    override fun onInit(status: Int) {
        val engine = tts ?: return
        ready = status == TextToSpeech.SUCCESS
        if (!ready) {
            Log.w(TAG, "TextToSpeech unavailable (status $status)")
            return
        }
        engine.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) = finish()

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = finish()
        })
    }

    /** Speak the deterrent once; [onDone] runs when finished (or immediately if TTS is unavailable). */
    fun speak(onDone: () -> Unit) {
        if (!ALARM_SOUND_ON) { // muted for now: "You are being recorded" is not spoken
            onDone()
            return
        }
        val engine = tts
        if (engine == null || !ready) {
            onDone()
            return
        }
        val malay = LocaleManager.isMalay() &&
            engine.setLanguage(Locale("ms", "MY")) >= TextToSpeech.LANG_AVAILABLE
        if (!malay) engine.setLanguage(Locale.US)
        val text = localizedString(appContext, R.string.cctv_deterrent_speech, if (malay) LocaleManager.MALAY else LocaleManager.ENGLISH)
        pendingDone = onDone
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
    }

    private fun finish() {
        val done = pendingDone
        pendingDone = null
        done?.invoke()
    }

    fun shutdown() {
        tts?.shutdown()
        tts = null
        ready = false
    }

    companion object {
        private const val TAG = "DeterrentVoice"
        private const val UTTERANCE_ID = "rewatt-deterrent"
    }
}
