package app.apt443.data.live

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Spoken alerts from the TV's own speech engine. Nothing leaves the device:
 * the upstream app sent each alert headline to Google Translate's web TTS.
 */
class DeviceSpeech(context: Context) {
    private val ready = CompletableDeferred<TextToSpeech?>()
    private val engine: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        ready.complete(if (status == TextToSpeech.SUCCESS) engineRef else null)
    }
    private val engineRef: TextToSpeech get() = engine

    init {
        engine.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
    }

    /** One spoken line at a time. Doorbell Say and TV voice alerts share this turn. */
    suspend fun <T> oneAtATime(block: suspend () -> T): T = speechTurn.withLock { block() }

    suspend fun playOnTv(text: String) = oneAtATime { speakUnlocked(text) }

    private suspend fun speakUnlocked(text: String) {
        val line = text.trim().take(180)
        if (line.isEmpty()) error("Choose a message first.")
        val tts = withTimeoutOrNull(ENGINE_WAIT_MS) { ready.await() }
            ?: error("This TV has no speech engine.")
        tts.language = Locale.US
        withTimeout(SPEAK_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                val utterance = "uport-${System.nanoTime()}"
                cont.invokeOnCancellation { runCatching { tts.stop() } }
                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = Unit

                    override fun onDone(utteranceId: String?) {
                        if (utteranceId == utterance && cont.isActive) cont.resume(Unit)
                    }

                    @Deprecated("Framework calls onError(utteranceId, errorCode) on newer APIs.")
                    override fun onError(utteranceId: String?) {
                        if (utteranceId == utterance && cont.isActive) cont.resume(Unit)
                    }
                })
                if (tts.speak(line, TextToSpeech.QUEUE_FLUSH, Bundle(), utterance) != TextToSpeech.SUCCESS) {
                    if (cont.isActive) cont.resume(Unit)
                }
            }
        }
    }

    private companion object {
        /** Shared so Say and voice alerts never overlap. */
        val speechTurn = Mutex()
        const val ENGINE_WAIT_MS = 5_000L
        const val SPEAK_TIMEOUT_MS = 20_000L
    }
}
