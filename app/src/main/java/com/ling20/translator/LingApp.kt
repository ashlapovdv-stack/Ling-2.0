package com.ling20.translator

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Translate
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
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class AppSection {
    TRANSLATE,
    CAMERA,
    DIALOG,
    SETTINGS,
}

private enum class SettingsPage {
    ROOT,
    MODEL,
    HISTORY,
}

@Composable
fun LingApp(
    historyRepository: TranslationHistoryRepository,
    modelRepository: ModelRepository,
    engine: LlamaTranslationEngine,
) {
    var section by rememberSaveable { mutableStateOf(AppSection.TRANSLATE) }
    var settingsPage by rememberSaveable { mutableStateOf(SettingsPage.ROOT) }
    var history by remember { mutableStateOf(historyRepository.load()) }
    var engineReady by remember { mutableStateOf(engine.isReady) }
    var loadedModelName by remember { mutableStateOf(engine.loadedModelName) }
    var modelLoading by remember { mutableStateOf(true) }
    var startupModelError by remember { mutableStateOf<String?>(null) }

    fun syncEngineState() {
        engineReady = engine.isReady
        loadedModelName = engine.loadedModelName
    }

    LaunchedEffect(Unit) {
        val installed = modelRepository.currentModel()
        if (installed != null) {
            val result = withContext(Dispatchers.Default) {
                runCatching { engine.loadModel(installed) }
            }
            startupModelError = result.exceptionOrNull()?.message
        }
        syncEngineState()
        modelLoading = false
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            ModeBottomBar(
                selected = section,
                onSelected = { next ->
                    section = next
                    if (next != AppSection.SETTINGS) settingsPage = SettingsPage.ROOT
                },
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            when (section) {
                AppSection.TRANSLATE -> TranslatorScreen(
                    engine = engine,
                    engineReady = engineReady,
                    modelLoading = modelLoading,
                    onTranslationSaved = { source, target, input, output ->
                        historyRepository.add(source, target, input, output)
                        history = historyRepository.load()
                    },
                )

                AppSection.CAMERA -> ComingSoonScreen(
                    title = "Камера",
                    description = "Перевод текста со снимка и изображения из галереи появится на следующем этапе.",
                    icon = { Icon(Icons.Default.CameraAlt, contentDescription = null) },
                )

                AppSection.DIALOG -> ComingSoonScreen(
                    title = "Диалог",
                    description = "Голосовой двусторонний перевод появится отдельным этапом после базового текстового перевода.",
                    icon = { Icon(Icons.Default.Mic, contentDescription = null) },
                )

                AppSection.SETTINGS -> when (settingsPage) {
                    SettingsPage.ROOT -> SettingsScreen(
                        engineReady = engineReady,
                        modelLoading = modelLoading,
                        modelName = loadedModelName ?: modelRepository.currentModel()?.displayName,
                        modelError = startupModelError,
                        historyCount = history.size,
                        onModelClick = { settingsPage = SettingsPage.MODEL },
                        onHistoryClick = { settingsPage = SettingsPage.HISTORY },
                    )

                    SettingsPage.MODEL -> ModelSettingsScreen(
                        modelRepository = modelRepository,
                        engine = engine,
                        engineReady = engineReady,
                        onBack = { settingsPage = SettingsPage.ROOT },
                        onEngineChanged = {
                            startupModelError = null
                            syncEngineState()
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
    engine: TranslationEngine,
    engineReady: Boolean,
    modelLoading: Boolean,
    onTranslationSaved: (Language, Language, String, String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var sourceName by rememberSaveable { mutableStateOf(Language.RUSSIAN.name) }
    var targetName by rememberSaveable { mutableStateOf(Language.ENGLISH.name) }
    var input by rememberSaveable { mutableStateOf("") }
    var output by rememberSaveable { mutableStateOf("") }
    var isTranslating by remember { mutableStateOf(false) }

    val source = Language.valueOf(sourceName)
    val target = Language.valueOf(targetName)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        AppHeader(engineReady = engineReady, modelLoading = modelLoading)

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LanguagePicker(
                language = source,
                onSelected = { selected ->
                    sourceName = selected.name
                    if (selected == target) {
                        targetName = Language.entries.first { it != selected }.name
                    }
                },
                modifier = Modifier.weight(1f),
            )

            IconButton(
                enabled = !isTranslating,
                onClick = {
                    val previousSource = sourceName
                    sourceName = targetName
                    targetName = previousSource
                    if (output.isNotBlank() && engineReady) {
                        val previousInput = input
                        input = output
                        output = previousInput
                    }
                },
            ) {
                Icon(Icons.Default.SwapHoriz, contentDescription = "Поменять языки")
            }

            LanguagePicker(
                language = target,
                onSelected = { selected ->
                    targetName = selected.name
                    if (selected == source) {
                        sourceName = Language.entries.first { it != selected }.name
                    }
                },
                modifier = Modifier.weight(1f),
            )
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("Исходный текст", style = MaterialTheme.typography.labelLarge)
                    Text(
                        "${input.length} / 5000",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                OutlinedTextField(
                    value = input,
                    onValueChange = { if (it.length <= 5000 && !isTranslating) input = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Введите текст") },
                    minLines = 5,
                    maxLines = 10,
                    enabled = !isTranslating,
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row {
                        IconButton(enabled = false, onClick = {}) {
                            Icon(Icons.Default.Mic, contentDescription = "Голосовой ввод — позже")
                        }
                        IconButton(enabled = false, onClick = {}) {
                            Icon(Icons.Default.Image, contentDescription = "Изображение — позже")
                        }
                    }
                    Icon(Icons.Default.Keyboard, contentDescription = null)
                }
            }
        }

        Button(
            onClick = {
                val cleanInput = input.trim()
                if (!engineReady) {
                    output = "Откройте Настройки → Локальная модель и выберите GGUF-файл."
                    return@Button
                }

                isTranslating = true
                output = ""
                scope.launch {
                    val result = withContext(Dispatchers.Default) {
                        runCatching { engine.translate(cleanInput, source, target) }
                    }
                    result.onSuccess { translated ->
                        output = translated
                        if (translated.isNotBlank()) {
                            onTranslationSaved(source, target, cleanInput, translated)
                        }
                    }.onFailure { error ->
                        output = "Ошибка перевода: ${error.message ?: "неизвестная ошибка"}"
                    }
                    isTranslating = false
                }
            },
            enabled = input.isNotBlank() && source != target && !isTranslating && !modelLoading,
            modifier = Modifier
                .fillMaxWidth()
                .height(58.dp),
            shape = RoundedCornerShape(18.dp),
        ) {
            if (isTranslating) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                Text("  Перевожу…", style = MaterialTheme.typography.titleMedium)
            } else {
                Icon(Icons.Default.Translate, contentDescription = null)
                Text("  Перевести", style = MaterialTheme.typography.titleMedium)
            }
        }

        if (output.isNotBlank()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.42f),
                ),
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        "Перевод (${target.displayName})",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        output,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("Ling translation", output))
                                Toast.makeText(context, "Перевод скопирован", Toast.LENGTH_SHORT).show()
                            },
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null)
                            Text("  Копировать")
                        }
                        OutlinedButton(onClick = { output = "" }) {
                            Icon(Icons.Default.Delete, contentDescription = null)
                            Text("  Очистить")
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(4.dp))
        Text(
            "RU • EN • 中文  ·  офлайн",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AppHeader(engineReady: Boolean, modelLoading: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                "Ling 2.0",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
            )
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Text(
                    "AI · Локальная модель",
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = if (engineReady) Color(0xFFE5F6EC) else Color(0xFFFFF4DD),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (modelLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = if (engineReady) Color(0xFF208A53) else Color(0xFFB7791F),
                    )
                }
                Text(
                    text = when {
                        modelLoading -> "  Загружаю локальную модель…"
                        engineReady -> "  Офлайн · модель загружена"
                        else -> "  Офлайн · выберите модель в настройках"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

@Composable
private fun LanguagePicker(
    language: Language,
    onSelected: (Language) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
        ) {
            Text("${language.flag()} ${language.displayName}", maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.weight(1f))
            Icon(Icons.Default.ExpandMore, contentDescription = null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            Language.entries.forEach { item ->
                DropdownMenuItem(
                    text = { Text("${item.flag()} ${item.displayName}") },
                    onClick = {
                        expanded = false
                        onSelected(item)
                    },
                )
            }
        }
    }
}

private fun Language.flag(): String = when (this) {
    Language.RUSSIAN -> "🇷🇺"
    Language.ENGLISH -> "🇬🇧"
    Language.CHINESE -> "🇨🇳"
}

@Composable
private fun ComingSoonScreen(
    title: String,
    description: String,
    icon: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.primaryContainer) {
            Box(Modifier.padding(22.dp)) { icon() }
        }
        Spacer(Modifier.height(18.dp))
        Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            description,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SettingsScreen(
    engineReady: Boolean,
    modelLoading: Boolean,
    modelName: String?,
    modelError: String?,
    historyCount: Int,
    onModelClick: () -> Unit,
    onHistoryClick: () -> Unit,
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
            title = "Локальная модель",
            subtitle = when {
                modelLoading -> "Загрузка модели…"
                engineReady -> modelName ?: "Модель готова к работе"
                modelError != null -> "Ошибка: $modelError"
                modelName != null -> "$modelName · требуется загрузка"
                else -> "Выберите GGUF модель"
            },
            onClick = onModelClick,
        )

        SettingsRow(
            icon = { Icon(Icons.Default.History, contentDescription = null) },
            title = "История",
            subtitle = if (historyCount == 0) "Переводов пока нет" else "Сохранено: $historyCount",
            onClick = onHistoryClick,
        )

        SettingsRow(
            icon = { Icon(Icons.Default.Settings, contentDescription = null) },
            title = "О приложении",
            subtitle = "Ling 2.0 · Android · полностью офлайн",
            onClick = null,
        )
    }
}

@Composable
private fun ModelSettingsScreen(
    modelRepository: ModelRepository,
    engine: LlamaTranslationEngine,
    engineReady: Boolean,
    onBack: () -> Unit,
    onEngineChanged: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var installedModel by remember { mutableStateOf(modelRepository.currentModel()) }
    var isBusy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            isBusy = true
            message = "Копирую модель в память приложения…"
            try {
                val model = modelRepository.importModel(uri)
                message = "Загружаю модель…"
                withContext(Dispatchers.Default) { engine.loadModel(model) }
                installedModel = model
                message = "Модель готова"
                onEngineChanged()
            } catch (error: Throwable) {
                message = "Ошибка: ${error.message ?: "не удалось загрузить модель"}"
                onEngineChanged()
            } finally {
                isBusy = false
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, enabled = !isBusy) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Назад")
            }
            Text(
                "Локальная модель",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
        }

        Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    if (engineReady) "Модель загружена" else "Модель не загружена",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                if (installedModel != null) {
                    Text(installedModel!!.displayName)
                    Text(
                        formatModelSize(installedModel!!.sizeBytes),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        "Для первого MVP выберите локальный GGUF-файл. Рекомендуемая стартовая модель: Qwen3-0.6B Q4_K_M.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                message?.let {
                    Text(
                        it,
                        color = if (it.startsWith("Ошибка")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }

        Button(
            onClick = { picker.launch(arrayOf("application/octet-stream", "*/*")) },
            enabled = !isBusy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (isBusy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                Text("  Обработка…")
            } else {
                Text(if (installedModel == null) "Выбрать GGUF модель" else "Заменить модель")
            }
        }

        if (installedModel != null) {
            OutlinedButton(
                onClick = {
                    isBusy = true
                    scope.launch {
                        withContext(Dispatchers.Default) { engine.unloadModel() }
                        modelRepository.removeModel()
                        installedModel = null
                        message = "Модель удалена с устройства"
                        isBusy = false
                        onEngineChanged()
                    }
                },
                enabled = !isBusy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.Delete, contentDescription = null)
                Text("  Удалить модель")
            }
        }

        Text(
            "Модель хранится только в приватной памяти приложения. Для перевода интернет не используется.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun formatModelSize(bytes: Long): String {
    val mb = bytes.toDouble() / (1024.0 * 1024.0)
    return if (mb >= 1024.0) {
        String.format(Locale.US, "%.2f GB", mb / 1024.0)
    } else {
        String.format(Locale.US, "%.0f MB", mb)
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
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            icon()
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 14.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Назад")
            }
            Text(
                "История",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            if (items.isNotEmpty()) {
                OutlinedButton(onClick = onClear) { Text("Очистить") }
            }
        }

        if (items.isEmpty()) {
            Text(
                "Здесь будут храниться успешные переводы. История находится только на устройстве.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            items.forEach { HistoryItemCard(it) }
        }
    }
}

@Composable
private fun HistoryItemCard(item: TranslationHistoryItem) {
    val dateText = remember(item.createdAtMillis) {
        SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(item.createdAtMillis))
    }
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Text(
                "${item.source.flag()} ${item.source.displayName} → ${item.target.flag()} ${item.target.displayName}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(item.input, style = MaterialTheme.typography.bodyMedium)
            Text(item.output, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            Text(dateText, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ModeBottomBar(selected: AppSection, onSelected: (AppSection) -> Unit) {
    Surface(
        tonalElevation = 4.dp,
        shadowElevation = 8.dp,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ModeTile(
                selected = selected == AppSection.TRANSLATE,
                label = "Перевод",
                icon = { Icon(Icons.Default.Translate, contentDescription = null) },
                onClick = { onSelected(AppSection.TRANSLATE) },
                modifier = Modifier.weight(1f),
            )
            ModeTile(
                selected = selected == AppSection.CAMERA,
                label = "Камера",
                icon = { Icon(Icons.Default.CameraAlt, contentDescription = null) },
                onClick = { onSelected(AppSection.CAMERA) },
                modifier = Modifier.weight(1f),
            )
            ModeTile(
                selected = selected == AppSection.DIALOG,
                label = "Диалог",
                icon = { Icon(Icons.Default.Mic, contentDescription = null) },
                onClick = { onSelected(AppSection.DIALOG) },
                modifier = Modifier.weight(1f),
            )
            ModeTile(
                selected = selected == AppSection.SETTINGS,
                label = "Настройки",
                icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                onClick = { onSelected(AppSection.SETTINGS) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun ModeTile(
    selected: Boolean,
    label: String,
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            icon()
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
