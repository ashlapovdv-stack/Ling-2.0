package com.ling20.translator

import android.content.Context
import android.speech.tts.TextToSpeech
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
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
    private var engine: TextToSpeech? = null
    @Volatile private var ready = false
    @Volatile private var failed = false

    init {
        engine = TextToSpeech(context) { status ->
            ready = status == TextToSpeech.SUCCESS
            failed = status != TextToSpeech.SUCCESS
        }
    }

    fun speak(text: String, language: Language) {
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
        var failedToQueue = false
        chunks.forEachIndexed { index, chunk ->
            val queueMode = if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            val result = tts.speak(
                chunk,
                queueMode,
                null,
                "ling_tts_${System.nanoTime()}_$index",
            )
            if (result == TextToSpeech.ERROR) failedToQueue = true
        }

        if (failedToQueue) {
            show("Не удалось озвучить текст.")
        }
    }

    fun shutdown() {
        engine?.stop()
        engine?.shutdown()
        engine = null
        ready = false
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
    val remainingChunks = mutableListOf<String>()
    var remaining = text.trim()

    while (remaining.length > limit) {
        val minimumBreak = limit * 2 / 3
        var breakAt = -1

        for (index in limit downTo minimumBreak) {
            val previous = remaining[index - 1]
            val current = remaining[index]
            if (previous in charArrayOf('.', '!', '?', '。', '！', '？', ';', ':', '\n') || current.isWhitespace()) {
                breakAt = index
                break
            }
        }

        if (breakAt < 0) breakAt = limit
        remaining.substring(0, breakAt).trim().takeIf { it.isNotEmpty() }?.let(remainingChunks::add)
        remaining = remaining.substring(breakAt).trimStart()
    }

    if (remaining.isNotEmpty()) remainingChunks += remaining
    return remainingChunks
}
