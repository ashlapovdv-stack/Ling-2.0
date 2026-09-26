package com.ling20.translator

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class AppSection { TRANSLATE, CAMERA, DIALOG, SETTINGS }
private enum class SettingsPage { ROOT, TRANSLATION, MODEL, HISTORY }

private enum class SourceLanguageOption(
    val language: Language?,
    val displayName: String,
    val symbol: String,
) {
    AUTO(null, "Авто", "🌐"),
    RUSSIAN(Language.RUSSIAN, Language.RUSSIAN.displayName, "🇷🇺"),
    ENGLISH(Language.ENGLISH, Language.ENGLISH.displayName, "🇬🇧"),
    CHINESE(Language.CHINESE, Language.CHINESE.displayName, "🇨🇳");

    companion object {
        fun from(language: Language): SourceLanguageOption = when (language) {
            Language.RUSSIAN -> RUSSIAN
            Language.ENGLISH -> ENGLISH
            Language.CHINESE -> CHINESE
        }
    }
}

private val ScreenBackground = Color(0xFFF5F8FC)
private val CardBorder = Color(0xFFE3E8EF)
private val ResultBackground = Color(0xFFEAF2FF)
private val ModelReadyGreen = Color(0xFF12B76A)
private val ModelUnavailableGray = Color(0xFFD0D5DD)
private const val TTS_SOURCE = "source"
private const val TTS_RESULT = "result"
private const val TRANSLATION_PREFS = "ling_translation_settings"
private const val PREF_DEFAULT_SOURCE = "default_source_language"
private const val PREF_DEFAULT_TARGET = "default_target_language"

@Composable
fun LingApp(
    historyRepository: TranslationHistoryRepository,
    modelRepository: ModelRepository,
    engine: LlamaTranslationEngine,
) {
    val context = LocalContext.current
    val appScope = rememberCoroutineScope()
    val translationPreferences = remember(context) {
        context.applicationContext.getSharedPreferences(TRANSLATION_PREFS, Context.MODE_PRIVATE)
    }
    val initialDefaultSource = remember(translationPreferences) {
        val saved = translationPreferences.getString(
            PREF_DEFAULT_SOURCE,
            SourceLanguageOption.RUSSIAN.name,
        )
        runCatching { SourceLanguageOption.valueOf(saved ?: SourceLanguageOption.RUSSIAN.name) }
            .getOrDefault(SourceLanguageOption.RUSSIAN)
    }
    val initialDefaultTarget = remember(translationPreferences) {
        val saved = translationPreferences.getString(
            PREF_DEFAULT_TARGET,
            Language.ENGLISH.name,
        )
        runCatching { Language.valueOf(saved ?: Language.ENGLISH.name) }
            .getOrDefault(Language.ENGLISH)
    }

    var section by rememberSaveable { mutableStateOf(AppSection.TRANSLATE) }
    var settingsPage by rememberSaveable { mutableStateOf(SettingsPage.ROOT) }
    var history by remember { mutableStateOf(historyRepository.load()) }
    var engineReady by remember { mutableStateOf(engine.isReady) }
    var modelName by remember { mutableStateOf(engine.loadedModelName) }
    var modelLoading by remember { mutableStateOf(true) }
    var modelError by remember { mutableStateOf<String?>(null) }
    var defaultSourceName by remember { mutableStateOf(initialDefaultSource.name) }
    var defaultTargetName by remember { mutableStateOf(initialDefaultTarget.name) }

    var sourceOptionName by rememberSaveable {
        mutableStateOf(
            if (initialDefaultSource.language == initialDefaultTarget) {
                SourceLanguageOption.AUTO.name
            } else {
                initialDefaultSource.name
            },
        )
    }
    var targetName by rememberSaveable { mutableStateOf(initialDefaultTarget.name) }
    var input by rememberSaveable { mutableStateOf("") }
    var output by rememberSaveable { mutableStateOf("") }
    var translating by remember { mutableStateOf(false) }

    val sourceOption = SourceLanguageOption.valueOf(sourceOptionName)
    val target = Language.valueOf(targetName)
    val defaultSource = runCatching { SourceLanguageOption.valueOf(defaultSourceName) }
        .getOrDefault(SourceLanguageOption.RUSSIAN)
    val defaultTarget = runCatching { Language.valueOf(defaultTargetName) }
        .getOrDefault(Language.ENGLISH)

    fun syncEngine() {
        engineReady = engine.isReady
        modelName = engine.loadedModelName
    }

    LaunchedEffect(Unit) {
        modelRepository.currentModel()?.let { installed ->
            val result = withContext(Dispatchers.Default) {
                runCatching { engine.loadModel(installed) }
            }
            modelError = result.exceptionOrNull()?.message
        }
        syncEngine()
        modelLoading = false
    }

    Scaffold(
        containerColor = ScreenBackground,
        bottomBar = {
            BottomModes(section) { next ->
                section = next
                if (next != AppSection.SETTINGS) settingsPage = SettingsPage.ROOT
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (section) {
                AppSection.TRANSLATE -> TranslatorScreen(
                    source = sourceOption,
                    target = target,
                    input = input,
                    output = output,
                    translating = translating,
                    modelLoading = modelLoading,
                    modelReady = engineReady,
                    onSourceSelected = { chosen ->
                        sourceOptionName = chosen.name
                        val explicit = chosen.language
                        if (explicit != null && explicit == target) {
                            targetName = Language.entries.first { it != explicit }.name
                        }
                    },
                    onTargetSelected = { chosen ->
                        targetName = chosen.name
                        if (sourceOption.language == chosen) {
                            sourceOptionName = SourceLanguageOption.from(
                                Language.entries.first { it != chosen },
                            ).name
                        }
                    },
                    onSwap = {
                        val actualSource = sourceOption.language ?: runCatching {
                            detectSupportedLanguage(input)
                        }.getOrNull()

                        if (actualSource != null && actualSource != target) {
                            sourceOptionName = SourceLanguageOption.from(target).name
                            targetName = actualSource.name
                            if (output.isNotBlank() && engineReady) {
                                val oldInput = input
                                input = output
                                output = oldInput
                            }
                        }
                    },
                    onInputChanged = { value ->
                        if (value.length <= 5000 && !translating) input = value
                    },
                    onClearInput = {
                        if (!translating) input = ""
                    },
                    onTranslate = {
                        val clean = input.trim()
                        if (!engineReady) {
                            output = "Откройте Настройки → Локальная модель и выберите GGUF-файл."
                        } else if (clean.isNotEmpty() && !translating) {
                            val resolvedSource = runCatching {
                                sourceOption.language ?: detectSupportedLanguage(clean)
                            }

                            resolvedSource.onFailure { error ->
                                output = error.message ?: "Не удалось определить язык исходного текста."
                            }.onSuccess { translateSource ->
                                if (translateSource == target) {
                                    output = "Определённый язык совпадает с языком перевода. Выберите другой язык результата."
                                } else {
                                    translating = true
                                    output = ""
                                    val translateTarget = target
                                    appScope.launch {
                                        val result = withContext(Dispatchers.Default) {
                                            runCatching {
                                                engine.translate(clean, translateSource, translateTarget)
                                            }
                                        }
                                        result.onSuccess { translated ->
                                            output = translated
                                            if (translated.isNotBlank()) {
                                                historyRepository.add(
                                                    translateSource,
                                                    translateTarget,
                                                    clean,
                                                    translated,
                                                )
                                                history = historyRepository.load()
                                            }
                                        }.onFailure { error ->
                                            output = "Ошибка перевода: ${error.message ?: "неизвестная ошибка"}"
                                        }
                                        translating = false
                                    }
                                }
                            }
                        }
                    },
                    onClearResult = { output = "" },
                )

                AppSection.CAMERA -> ComingSoon(
                    title = "Камера",
                    description = "Перевод текста со снимка и изображения из галереи будет добавлен следующим этапом.",
                    camera = true,
                )

                AppSection.DIALOG -> ComingSoon(
                    title = "Диалог",
                    description = "Двусторонний голосовой перевод будет добавлен после базового текстового режима.",
                    camera = false,
                )

                AppSection.SETTINGS -> when (settingsPage) {
                    SettingsPage.ROOT -> SettingsRoot(
                        engineReady = engineReady,
                        modelLoading = modelLoading,
                        modelName = modelName ?: modelRepository.currentModel()?.displayName,
                        modelError = modelError,
                        historyCount = history.size,
                        defaultSource = defaultSource,
                        onTranslation = { settingsPage = SettingsPage.TRANSLATION },
                        onModel = { settingsPage = SettingsPage.MODEL },
                        onHistory = { settingsPage = SettingsPage.HISTORY },
                    )

                    SettingsPage.TRANSLATION -> TranslationSettings(
                        defaultSource = defaultSource,
                        defaultTarget = defaultTarget,
                        onBack = { settingsPage = SettingsPage.ROOT },
                        onDefaultSourceChanged = { selected ->
                            defaultSourceName = selected.name
                            translationPreferences.edit()
                                .putString(PREF_DEFAULT_SOURCE, selected.name)
                                .apply()
                            if (input.isBlank() && output.isBlank()) {
                                sourceOptionName = if (selected.language == target) {
                                    SourceLanguageOption.AUTO.name
                                } else {
                                    selected.name
                                }
                            }
                        },
                        onDefaultTargetChanged = { selected ->
                            defaultTargetName = selected.name
                            translationPreferences.edit()
                                .putString(PREF_DEFAULT_TARGET, selected.name)
                                .apply()
                            if (input.isBlank() && output.isBlank()) {
                                targetName = selected.name
                                if (sourceOption.language == selected) {
                                    sourceOptionName = SourceLanguageOption.AUTO.name
                                }
                            }
                        },
                    )

                    SettingsPage.MODEL -> ModelSettings(
                        repository = modelRepository,
                        engine = engine,
                        engineReady = engineReady,
                        onBack = { settingsPage = SettingsPage.ROOT },
                        onChanged = {
                            modelError = null
                            syncEngine()
                        },
                    )

                    SettingsPage.HISTORY -> HistoryScreen(
                        items = history,
                        onBack = { settingsPage = SettingsPage.ROOT },
                        onClear = {
                            historyRepository.clear()
                            history = emptyList()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun TranslatorScreen(
    source: SourceLanguageOption,
    target: Language,
    input: String,
    output: String,
    translating: Boolean,
    modelLoading: Boolean,
    modelReady: Boolean,
    onSourceSelected: (SourceLanguageOption) -> Unit,
    onTargetSelected: (Language) -> Unit,
    onSwap: () -> Unit,
    onInputChanged: (String) -> Unit,
    onClearInput: () -> Unit,
    onTranslate: () -> Unit,
    onClearResult: () -> Unit,
) {
    val context = LocalContext.current
    val tts = rememberOfflineTextToSpeech()
    val sourceSpeechLanguage = source.language ?: runCatching {
        detectSupportedLanguage(input)
    }.getOrNull()
    val sourceSpeaking = tts.activeRequestId == TTS_SOURCE
    val resultSpeaking = tts.activeRequestId == TTS_RESULT

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        LanguageRow(
            source = source,
            target = target,
            enabled = !translating,
            swapEnabled = !translating && (source != SourceLanguageOption.AUTO || input.isNotBlank()),
            onSourceSelected = onSourceSelected,
            onTargetSelected = onTargetSelected,
            onSwap = onSwap,
        )

        InputCard(
            input = input,
            enabled = !translating,
            modelReady = modelReady,
            speechLanguage = source.language,
            speaking = sourceSpeaking,
            onInputChanged = onInputChanged,
            onSpeak = {
                if (sourceSpeechLanguage != null) {
                    tts.toggleSpeak(TTS_SOURCE, input, sourceSpeechLanguage)
                } else {
                    Toast.makeText(
                        context,
                        "Не удалось определить язык для озвучивания.",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            },
            onCopy = {
                if (input.isNotBlank()) {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Ling source", input))
                    Toast.makeText(context, "Исходный текст скопирован", Toast.LENGTH_SHORT).show()
                }
            },
            onClear = {
                tts.stop()
                onClearInput()
                onClearResult()
            },
        )

        Button(
            onClick = onTranslate,
            enabled = input.isNotBlank() &&
                (source.language == null || source.language != target) &&
                !translating && !modelLoading,
            modifier = Modifier.fillMaxWidth().height(58.dp),
            shape = RoundedCornerShape(18.dp),
        ) {
            if (translating) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                Text(
                    "  Перевожу…",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            } else {
                Icon(Icons.Default.Translate, contentDescription = null)
                Text(
                    "  Перевести",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        if (output.isNotBlank()) {
            TranslationResultCard(
                target = target,
                output = output,
                speaking = resultSpeaking,
                onSpeak = { tts.toggleSpeak(TTS_RESULT, output, target) },
                onCopy = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Ling translation", output))
                    Toast.makeText(context, "Перевод скопирован", Toast.LENGTH_SHORT).show()
                },
                onClear = {
                    if (resultSpeaking) tts.stop()
                    onClearResult()
                },
            )
        } else {
            EmptyResultHint(target)
        }

        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun LanguageRow(
    source: SourceLanguageOption,
    target: Language,
    enabled: Boolean,
    swapEnabled: Boolean,
    onSourceSelected: (SourceLanguageOption) -> Unit,
    onTargetSelected: (Language) -> Unit,
    onSwap: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        SourceLanguagePicker(
            source = source,
            enabled = enabled,
            onSelected = onSourceSelected,
            modifier = Modifier.weight(1f),
        )

        Surface(
            modifier = Modifier
                .size(46.dp)
                .clickable(enabled = swapEnabled, onClick = onSwap),
            shape = CircleShape,
            color = Color(0xFFE5EEFC),
            contentColor = if (swapEnabled) MaterialTheme.colorScheme.primary else Color(0xFF98A2B3),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Default.SwapHoriz,
                    contentDescription = "Поменять языки",
                    modifier = Modifier.size(23.dp),
                )
            }
        }

        LanguagePicker(
            language = target,
            enabled = enabled,
            onSelected = onTargetSelected,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun SourceLanguagePicker(
    source: SourceLanguageOption,
    enabled: Boolean,
    onSelected: (SourceLanguageOption) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .clickable(enabled = enabled) { expanded = true },
            shape = RoundedCornerShape(17.dp),
            color = Color.White,
            border = BorderStroke(1.dp, CardBorder),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 13.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(source.symbol, style = MaterialTheme.typography.titleMedium)
                Text(
                    source.displayName,
                    modifier = Modifier.padding(start = 7.dp).weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Icon(
                    Icons.Default.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            SourceLanguageOption.entries.forEach { item ->
                DropdownMenuItem(
                    text = {
                        Text(
                            if (item == SourceLanguageOption.AUTO) {
                                "${item.symbol}  Автоопределение"
                            } else {
                                "${item.symbol}  ${item.displayName}"
                            },
                        )
                    },
                    onClick = {
                        expanded = false
                        onSelected(item)
                    },
                )
            }
        }
    }
}

@Composable
private fun LanguagePicker(
    language: Language,
    enabled: Boolean,
    onSelected: (Language) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .clickable(enabled = enabled) { expanded = true },
            shape = RoundedCornerShape(17.dp),
            color = Color.White,
            border = BorderStroke(1.dp, CardBorder),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 13.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(language.flag(), style = MaterialTheme.typography.titleMedium)
                Text(
                    language.displayName,
                    modifier = Modifier.padding(start = 7.dp).weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Icon(
                    Icons.Default.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            Language.entries.forEach { item ->
                DropdownMenuItem(
                    text = { Text("${item.flag()}  ${item.displayName}") },
                    onClick = {
                        expanded = false
                        onSelected(item)
                    },
                )
            }
        }
    }
}

@Composable
private fun InputCard(
    input: String,
    enabled: Boolean,
    modelReady: Boolean,
    speechLanguage: Language?,
    speaking: Boolean,
    onInputChanged: (String) -> Unit,
    onSpeak: () -> Unit,
    onCopy: () -> Unit,
    onClear: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 17.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Исходный текст",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF475467),
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "${input.length} / 5000",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF98A2B3),
                )
                Spacer(Modifier.size(8.dp))
                ModelAiBadge(modelReady)
            }

            BasicTextField(
                value = input,
                onValueChange = onInputChanged,
                enabled = enabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 142.dp),
                textStyle = TextStyle(
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = MaterialTheme.typography.bodyLarge.fontSize,
                    lineHeight = MaterialTheme.typography.bodyLarge.lineHeight,
                ),
                decorationBox = { innerTextField ->
                    Box(Modifier.fillMaxSize()) {
                        if (input.isEmpty()) {
                            Text(
                                "Введите текст…",
                                color = Color(0xFF98A2B3),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                        innerTextField()
                    }
                },
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OfflineSpeechButton(
                    language = speechLanguage,
                    enabled = enabled,
                    currentText = input,
                    onTextChanged = onInputChanged,
                )
                Spacer(Modifier.weight(1f))
                IconButton(
                    enabled = enabled && input.isNotBlank(),
                    onClick = onSpeak,
                ) {
                    Icon(
                        imageVector = if (speaking) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                        contentDescription = if (speaking) {
                            "Остановить озвучивание исходного текста"
                        } else {
                            "Озвучить исходный текст"
                        },
                        tint = if (speaking) MaterialTheme.colorScheme.primary else Color(0xFF667085),
                    )
                }
                IconButton(
                    enabled = enabled && input.isNotEmpty(),
                    onClick = onCopy,
                ) {
                    Icon(
                        Icons.Default.ContentCopy,
                        contentDescription = "Копировать исходный текст",
                    )
                }
                IconButton(
                    enabled = enabled && input.isNotEmpty(),
                    onClick = onClear,
                ) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Очистить исходный текст и перевод",
                    )
                }
            }
        }
    }
}

@Composable
private fun ModelAiBadge(modelReady: Boolean) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (modelReady) ModelReadyGreen else ModelUnavailableGray,
    ) {
        Text(
            text = "AI",
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = if (modelReady) Color.White else Color(0xFF667085),
        )
    }
}

@Composable
private fun TranslationResultCard(
    target: Language,
    output: String,
    speaking: Boolean,
    onSpeak: () -> Unit,
    onCopy: () -> Unit,
    onClear: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = ResultBackground),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Перевод (${target.displayName})",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF475467),
                )
                Spacer(Modifier.weight(1f))
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = Color.White.copy(alpha = 0.72f),
                ) {
                    Text(
                        "AI",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            Text(
                output,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ResultIconButton(
                    icon = if (speaking) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                    contentDescription = if (speaking) {
                        "Остановить озвучивание перевода"
                    } else {
                        "Озвучить перевод"
                    },
                    onClick = onSpeak,
                    active = speaking,
                )
                ResultIconButton(
                    icon = Icons.Default.ContentCopy,
                    contentDescription = "Копировать перевод",
                    onClick = onCopy,
                )
                ResultIconButton(
                    icon = Icons.Default.Delete,
                    contentDescription = "Очистить перевод",
                    onClick = onClear,
                )
            }
        }
    }
}

@Composable
private fun ResultIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    active: Boolean = false,
) {
    IconButton(onClick = onClick) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (active) MaterialTheme.colorScheme.primary else Color(0xFF667085),
        )
    }
}

@Composable
private fun EmptyResultHint(target: Language) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = Color(0xFFF0F4F9),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Text(
                "Перевод (${target.displayName})",
                style = MaterialTheme.typography.labelLarge,
                color = Color(0xFF667085),
            )
            Text(
                "Результат появится здесь",
                style = MaterialTheme.typography.bodyLarge,
                color = Color(0xFF98A2B3),
            )
        }
    }
}

private fun Language.flag() = when (this) {
    Language.RUSSIAN -> "🇷🇺"
    Language.ENGLISH -> "🇬🇧"
    Language.CHINESE -> "🇨🇳"
}

private fun detectSupportedLanguage(text: String): Language {
    var cyrillic = 0
    var latin = 0
    var han = 0

    text.forEach { character ->
        when {
            character in '\u0400'..'\u04FF' -> cyrillic++
            character in '\u4E00'..'\u9FFF' || character in '\u3400'..'\u4DBF' -> han++
            character in 'A'..'Z' || character in 'a'..'z' -> latin++
        }
    }

    val max = maxOf(cyrillic, latin, han)
    require(max > 0) {
        "Не удалось определить язык. Выберите Русский, English или 中文 вручную."
    }

    return when (max) {
        cyrillic -> Language.RUSSIAN
        han -> Language.CHINESE
        else -> Language.ENGLISH
    }
}

@Composable
private fun ComingSoon(title: String, description: String, camera: Boolean) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
        ) {
            Box(Modifier.padding(22.dp)) {
                Icon(if (camera) Icons.Default.CameraAlt else Icons.Default.Mic, contentDescription = null)
            }
        }
        Spacer(Modifier.height(18.dp))
        Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SettingsRoot(
    engineReady: Boolean,
    modelLoading: Boolean,
    modelName: String?,
    modelError: String?,
    historyCount: Int,
    defaultSource: SourceLanguageOption,
    onTranslation: () -> Unit,
    onModel: () -> Unit,
    onHistory: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Настройки", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)

        SettingsRow(
            icon = { Icon(Icons.Default.Translate, contentDescription = null) },
            title = "Перевод",
            subtitle = "Язык ввода по умолчанию: ${defaultSource.settingsLabel()}",
            onClick = onTranslation,
        )

        SettingsRow(
            icon = { Icon(Icons.Default.Translate, contentDescription = null) },
            title = "Локальная модель",
            subtitle = when {
                modelLoading -> "Проверяю модель…"
                modelError != null -> "Ошибка: $modelError"
                engineReady -> modelName ?: "Модель загружена"
                modelName != null -> "$modelName · не загружена"
                else -> "Выберите GGUF-файл"
            },
            onClick = onModel,
        )

        SettingsRow(
            icon = { Icon(Icons.Default.History, contentDescription = null) },
            title = "История",
            subtitle = if (historyCount == 0) "Переводов пока нет" else "Сохранено: $historyCount",
            onClick = onHistory,
        )

        SettingsRow(
            icon = { Icon(Icons.Default.Settings, contentDescription = null) },
            title = "О приложении",
            subtitle = "Ling 2.0 · v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · Android · офлайн",
            onClick = null,
        )
    }
}

@Composable
private fun TranslationSettings(
    defaultSource: SourceLanguageOption,
    defaultTarget: Language,
    onBack: () -> Unit,
    onDefaultSourceChanged: (SourceLanguageOption) -> Unit,
    onDefaultTargetChanged: (Language) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Назад")
            }
            Text(
                "Перевод",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "Язык ввода по умолчанию",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                SourceLanguagePicker(
                    source = defaultSource,
                    enabled = true,
                    onSelected = onDefaultSourceChanged,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Доступны: Автоопределение, Русский, English и 中文. Выбранный язык используется при следующем запуске приложения.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "Язык вывода по умолчанию",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                LanguagePicker(
                    language = defaultTarget,
                    enabled = true,
                    onSelected = onDefaultTargetChanged,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Доступны: Русский, English и 中文. Выбранный язык используется как язык результата при следующем запуске приложения.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun SourceLanguageOption.settingsLabel(): String = when (this) {
    SourceLanguageOption.AUTO -> "Автоопределение"
    else -> displayName
}

@Composable
private fun ModelSettings(
    repository: ModelRepository,
    engine: LlamaTranslationEngine,
    engineReady: Boolean,
    onBack: () -> Unit,
    onChanged: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var installed by remember { mutableStateOf(repository.currentModel()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            busy = true
            error = null
            scope.launch {
                val result = runCatching {
                    val imported = repository.importModel(uri)
                    withContext(Dispatchers.Default) { engine.loadModel(imported) }
                    imported
                }
                result.onSuccess {
                    installed = it
                    onChanged()
                }.onFailure {
                    error = it.message ?: "Не удалось загрузить модель"
                }
                busy = false
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Назад")
            }
            Text("Локальная модель", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        }

        installed?.let { model ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(model.displayName, fontWeight = FontWeight.SemiBold)
                    Text(formatBytes(model.sizeBytes), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(if (engineReady) "Модель активна" else "Модель сохранена, но не активна")
                }
            }
        }

        if (error != null) {
            Text("Ошибка: $error", color = MaterialTheme.colorScheme.error)
        }

        Button(
            onClick = { picker.launch(arrayOf("*/*")) },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            Text(if (busy) "  Загружаю…" else "Выбрать GGUF-модель")
        }

        if (installed != null) {
            OutlinedButton(
                onClick = {
                    engine.unloadModel()
                    repository.removeModel()
                    installed = null
                    onChanged()
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Удалить модель с устройства")
            }
        }

        Text(
            "Модель хранится только на устройстве. Для первой версии рекомендуется Qwen3-0.6B GGUF Q4.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SettingsRow(
    icon: @Composable () -> Unit,
    title: String,
    subtitle: String,
    onClick: (() -> Unit)?,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            icon()
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (onClick != null) Icon(Icons.Default.ChevronRight, contentDescription = null)
        }
    }
}

@Composable
private fun HistoryScreen(
    items: List<TranslationHistoryItem>,
    onBack: () -> Unit,
    onClear: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Назад")
            }
            Text(
                "История",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            if (items.isNotEmpty()) OutlinedButton(onClick = onClear) { Text("Очистить") }
        }

        if (items.isEmpty()) {
            Text("Здесь будут храниться успешные переводы. История находится только на устройстве.")
        } else {
            items.forEach { item -> HistoryCard(item) }
        }
    }
}

@Composable
private fun HistoryCard(item: TranslationHistoryItem) {
    val date = remember(item.createdAtMillis) {
        SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(item.createdAtMillis))
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "${item.source.flag()} ${item.source.displayName} → ${item.target.flag()} ${item.target.displayName}",
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            )
            Text(item.input)
            Text(item.output, fontWeight = FontWeight.SemiBold)
            Text(
                date,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun BottomModes(selected: AppSection, onSelect: (AppSection) -> Unit) {
    Surface(
        color = Color.White,
        shadowElevation = 12.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 9.dp, vertical = 9.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            ModeTile(
                selected = selected == AppSection.TRANSLATE,
                label = "Перевод",
                icon = Icons.Default.Translate,
                onClick = { onSelect(AppSection.TRANSLATE) },
                modifier = Modifier.weight(1f),
            )
            ModeTile(
                selected = selected == AppSection.CAMERA,
                label = "Камера",
                icon = Icons.Default.CameraAlt,
                onClick = { onSelect(AppSection.CAMERA) },
                modifier = Modifier.weight(1f),
            )
            ModeTile(
                selected = selected == AppSection.DIALOG,
                label = "Диалог",
                icon = Icons.Default.Mic,
                onClick = { onSelect(AppSection.DIALOG) },
                modifier = Modifier.weight(1f),
            )
            ModeTile(
                selected = selected == AppSection.SETTINGS,
                label = "Настройки",
                icon = Icons.Default.Settings,
                onClick = { onSelect(AppSection.SETTINGS) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun ModeTile(
    selected: Boolean,
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    Surface(
        modifier = modifier
            .height(66.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        color = if (selected) MaterialTheme.colorScheme.primary else Color(0xFFF2F5F9),
        contentColor = if (selected) Color.White else Color(0xFF667085),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 3.dp, vertical = 9.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
            Spacer(Modifier.height(4.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun formatBytes(bytes: Long): String {
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1024.0) String.format(Locale.US, "%.2f GB", mb / 1024.0)
    else String.format(Locale.US, "%.0f MB", mb)
}
