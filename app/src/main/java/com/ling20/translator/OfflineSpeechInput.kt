package com.ling20.translator

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

@Composable
internal fun OfflineSpeechButton(
    language: Language?,
    enabled: Boolean,
    currentText: String,
    onTextChanged: (String) -> Unit,
) {
    val context = LocalContext.current
    val latestText by rememberUpdatedState(currentText)
    val latestOnTextChanged by rememberUpdatedState(onTextChanged)

    var recognizer by remember { mutableStateOf<SpeechRecognizer?>(null) }
    var listening by remember { mutableStateOf(false) }
    var pendingLanguage by remember { mutableStateOf<Language?>(null) }

    fun show(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    fun startRecognition(selectedLanguage: Language) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            !SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        ) {
            show("Офлайн-распознавание речи недоступно на этом устройстве.")
            return
        }

        recognizer?.destroy()

        val newRecognizer = runCatching {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        }.getOrElse {
            show("Не удалось запустить офлайн-распознавание речи.")
            return
        }

        newRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                listening = true
            }

            override fun onBeginningOfSpeech() {
                listening = true
            }

            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit

            override fun onError(error: Int) {
                listening = false
                if (recognizer === newRecognizer) recognizer = null
                newRecognizer.destroy()
                show(speechErrorMessage(error))
            }

            override fun onResults(results: Bundle?) {
                listening = false
                val spoken = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    ?.trim()
                    .orEmpty()

                if (spoken.isNotEmpty()) {
                    val base = latestText.trimEnd()
                    val merged = if (base.isEmpty()) spoken else "$base $spoken"
                    latestOnTextChanged(merged.take(MAX_INPUT_CHARS))
                }

                if (recognizer === newRecognizer) recognizer = null
                newRecognizer.destroy()
            }

            override fun onPartialResults(partialResults: Bundle?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, speechLanguageTag(selectedLanguage))
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }

        recognizer = newRecognizer
        listening = true
        runCatching { newRecognizer.startListening(intent) }
            .onFailure {
                listening = false
                recognizer = null
                newRecognizer.destroy()
                show("Не удалось начать распознавание речи.")
            }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val selectedLanguage = pendingLanguage
        pendingLanguage = null
        if (granted && selectedLanguage != null) {
            startRecognition(selectedLanguage)
        } else if (!granted) {
            show("Для голосового ввода нужен доступ к микрофону.")
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            recognizer?.destroy()
            recognizer = null
            listening = false
        }
    }

    IconButton(
        enabled = enabled,
        onClick = {
            if (listening) {
                recognizer?.stopListening()
                return@IconButton
            }

            val selectedLanguage = language
            if (selectedLanguage == null) {
                show("Для голосового ввода выберите Русский, English или 中文 вместо Авто.")
                return@IconButton
            }

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                !SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
            ) {
                show("Офлайн-распознавание речи доступно на Android 12 и новее при установленном языковом пакете.")
                return@IconButton
            }

            if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
            ) {
                startRecognition(selectedLanguage)
            } else {
                pendingLanguage = selectedLanguage
                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        },
    ) {
        Icon(
            Icons.Default.Mic,
            contentDescription = if (listening) "Остановить голосовой ввод" else "Голосовой ввод",
            tint = if (listening) MaterialTheme.colorScheme.primary else Color(0xFF667085),
        )
    }
}

private fun speechLanguageTag(language: Language): String = when (language) {
    Language.RUSSIAN -> "ru-RU"
    Language.ENGLISH -> "en-US"
    Language.CHINESE -> "zh-CN"
}

private fun speechErrorMessage(error: Int): String = when (error) {
    SpeechRecognizer.ERROR_NO_MATCH -> "Речь не распознана. Попробуйте ещё раз."
    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Речь не обнаружена."
    SpeechRecognizer.ERROR_AUDIO -> "Ошибка доступа к микрофону."
    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Нет разрешения на использование микрофона."
    SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
    SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "Для выбранного языка нет офлайн-пакета распознавания."
    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Распознавание уже запущено."
    else -> "Ошибка офлайн-распознавания речи ($error)."
}

private const val MAX_INPUT_CHARS = 5000
