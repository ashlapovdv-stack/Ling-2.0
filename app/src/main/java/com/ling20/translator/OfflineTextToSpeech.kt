package com.ling20.translator

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import java.util.Locale

@Composable
internal fun rememberOfflineTextToSpeech(): OfflineTextToSpeech {
    val context = LocalContext.current
    val controller = remember { OfflineTextToSpeech(context.applicationContext) }

    DisposableEffect(controller) {
        onDispose { controller.shutdown() }
    }

    return controller
}

internal class OfflineTextToSpeech(
    private val context: Context,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var engine: TextToSpeech? = null
    @Volatile private var ready = false
    @Volatile private var failed = false
    @Volatile private var activeFinalUtteranceId: String? = null

    var activeRequestId by mutableStateOf<String?>(null)
        private set

    init {
        engine = TextToSpeech(context) { status ->
            ready = status == TextToSpeech.SUCCESS
            failed = status != TextToSpeech.SUCCESS

            if (status == TextToSpeech.SUCCESS) {
                engine?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = Unit

                    override fun onDone(utteranceId: String?) {
                        clearIfFinished(utteranceId)
                    }

                    @Deprecated("Deprecated by Android, still required by the listener API")
                    override fun onError(utteranceId: String?) {
                        clearIfFinished(utteranceId)
                    }
                })
            }
        }
    }

    fun toggleSpeak(requestId: String, text: String, language: Language) {
        if (activeRequestId == requestId) {
            stop()
            return
        }
        speak(requestId, text, language)
    }

    fun stop() {
        engine?.stop()
        activeFinalUtteranceId = null
        activeRequestId = null
    }

    private fun speak(requestId: String, text: String, language: Language) {
        if (text.isBlank()) return

        val tts = engine
        if (tts == null || failed) {
            show("Синтез речи недоступен на этом устройстве.")
            return
        }
        if (!ready) {
            show("Озвучивание ещё запускается. Попробуйте ещё раз.")
            return
        }

        val locale = language.ttsLocale()
        val voice = runCatching {
            tts.voices
                ?.asSequence()
                ?.filter { candidate ->
                    !candidate.isNetworkConnectionRequired &&
                        candidate.locale.language.equals(locale.language, ignoreCase = true)
                }
                ?.sortedWith(
                    compareByDescending<android.speech.tts.Voice> {
                        it.locale.country.equals(locale.country, ignoreCase = true)
                    }.thenByDescending { it.quality }
                        .thenBy { it.latency },
                )
                ?.firstOrNull()
        }.getOrNull()

        if (voice == null) {
            show("Для ${language.displayName} не установлен офлайн-голос Android.")
            return
        }

        if (tts.setVoice(voice) == TextToSpeech.ERROR) {
            show("Не удалось включить офлайн-голос для ${language.displayName}.")
            return
        }

        tts.stop()
        tts.setSpeechRate(1.0f)

        val chunks = splitForSpeech(text, TextToSpeech.getMaxSpeechInputLength())
        if (chunks.isEmpty()) return

        val session = System.nanoTime()
        val finalUtteranceId = "ling_tts_${session}_${chunks.lastIndex}"
        activeFinalUtteranceId = finalUtteranceId
        activeRequestId = requestId

        var failedToQueue = false
        chunks.forEachIndexed { index, chunk ->
            val queueMode = if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            val utteranceId = "ling_tts_${session}_$index"
            val result = tts.speak(
                chunk,
                queueMode,
                null,
                utteranceId,
            )
            if (result == TextToSpeech.ERROR) failedToQueue = true
        }

        if (failedToQueue) {
            stop()
            show("Не удалось озвучить текст.")
        }
    }

    fun shutdown() {
        stop()
        engine?.shutdown()
        engine = null
        ready = false
    }

    private fun clearIfFinished(utteranceId: String?) {
        if (utteranceId != null && utteranceId == activeFinalUtteranceId) {
            activeFinalUtteranceId = null
            mainHandler.post {
                activeRequestId = null
            }
        }
    }

    private fun show(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }
}

private fun Language.ttsLocale(): Locale = when (this) {
    Language.RUSSIAN -> Locale("ru", "RU")
    Language.ENGLISH -> Locale.US
    Language.CHINESE -> Locale.SIMPLIFIED_CHINESE
}

private fun splitForSpeech(text: String, platformLimit: Int): List<String> {
    val limit = platformLimit.coerceAtMost(3500).coerceAtLeast(500)
    val chunks = mutableListOf<String>()
    var remaining = text.trim()

    while (remaining.length > limit) {
        val minimumBreak = limit * 2 / 3
        var breakAt = -1

        for (index in limit downTo minimumBreak) {
            val previous = remaining[index - 1]
            val current = remaining[index]
            if (
                previous in charArrayOf('.', '!', '?', '。', '！', '？', ';', ':', '\n') ||
                current.isWhitespace()
            ) {
                breakAt = index
                break
            }
        }

        if (breakAt < 0) breakAt = limit
        remaining.substring(0, breakAt).trim().takeIf { it.isNotEmpty() }?.let(chunks::add)
        remaining = remaining.substring(breakAt).trimStart()
    }

    if (remaining.isNotEmpty()) chunks += remaining
    return chunks
}
