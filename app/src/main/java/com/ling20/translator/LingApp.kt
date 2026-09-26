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

private enum class AppSection { TRANSLATE, CAMERA, DIALOG, SETTINGS }
private enum class SettingsPage { ROOT, MODEL, HISTORY }

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
    var modelName by remember { mutableStateOf(engine.loadedModelName) }
    var modelLoading by remember { mutableStateOf(true) }
    var modelError by remember { mutableStateOf<String?>(null) }

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
        containerColor = MaterialTheme.colorScheme.background,
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
                    engine = engine,
                    engineReady = engineReady,
                    modelLoading = modelLoading,
                    onSaved = { source, target, input, output ->
                        historyRepository.add(source, target, input, output)
                        history = historyRepository.load()
                    },
                )

                AppSection.CAMERA -> ComingSoon(
                    "Камера",
                    "Перевод текста со снимка и изображения из галереи будет добавлен следующим этапом.",
                    true,
                )

                AppSection.DIALOG -> ComingSoon(
                    "Диалог",
                    "Двусторонний голосовой перевод будет добавлен после базового текстового режима.",
                    false,
                )

                AppSection.SETTINGS -> when (settingsPage) {
                    SettingsPage.ROOT -> SettingsRoot(
                        engineReady = engineReady,
                        modelLoading = modelLoading,
                        modelName = modelName ?: modelRepository.currentModel()?.displayName,
                        modelError = modelError,
                        historyCount = history.size,
                        onModel = { settingsPage = SettingsPage.MODEL },
                        onHistory = { settingsPage = SettingsPage.HISTORY },
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
    engine: TranslationEngine,
    engineReady: Boolean,
    modelLoading: Boolean,
    onSaved: (Language, Language, String, String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var sourceName by rememberSaveable { mutableStateOf(Language.RUSSIAN.name) }
    var targetName by rememberSaveable { mutableStateOf(Language.ENGLISH.name) }
    var input by rememberSaveable { mutableStateOf("") }
    var output by rememberSaveable { mutableStateOf("") }
    var translating by remember { mutableStateOf(false) }

    val source = Language.valueOf(sourceName)
    val target = Language.valueOf(targetName)

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Header(engineReady, modelLoading)

        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LanguagePicker(source, { chosen ->
                sourceName = chosen.name
                if (chosen == target) targetName = Language.entries.first { it != chosen }.name
            }, Modifier.weight(1f))

            IconButton(
                enabled = !translating,
                onClick = {
                    val oldSource = sourceName
                    sourceName = targetName
                    targetName = oldSource
                    if (output.isNotBlank() && engineReady) {
                        val oldInput = input
                        input = output
                        output = oldInput
                    }
                },
            ) {
                Icon(Icons.Default.SwapHoriz, "Поменять языки")
            }

            LanguagePicker(target, { chosen ->
                targetName = chosen.name
                if (chosen == source) sourceName = Language.entries.first { it != chosen }.name
            }, Modifier.weight(1f))
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Исходный текст", style = MaterialTheme.typography.labelLarge)
                    Text("${input.length} / 5000", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                OutlinedTextField(
                    value = input,
                    onValueChange = { value -> if (value.length <= 5000 && !translating) input = value },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Введите текст") },
                    minLines = 5,
                    maxLines = 10,
                    enabled = !translating,
                )

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row {
                        IconButton(enabled = false, onClick = {}) {
                            Icon(Icons.Default.Mic, "Голосовой ввод")
                        }
                        IconButton(enabled = false, onClick = {}) {
                            Icon(Icons.Default.Image, "Изображение")
                        }
                    }
                    Icon(Icons.Default.Keyboard, null)
                }
            }
        }

        Button(
            onClick = {
                val clean = input.trim()
                if (!engineReady) {
                    output = "Откройте Настройки → Локальная модель и выберите GGUF-файл."
                } else {
                    translating = true
                    output = ""
                    scope.launch {
                        val result = withContext(Dispatchers.Default) {
                            runCatching { engine.translate(clean, source, target) }
                        }
                        result.onSuccess { translated ->
                            output = translated
                            if (translated.isNotBlank()) onSaved(source, target, clean, translated)
                        }.onFailure { error ->
                            output = "Ошибка перевода: ${error.message ?: "неизвестная ошибка"}"
                        }
                        translating = false
                    }
                }
            },
            enabled = input.isNotBlank() && source != target && !translating && !modelLoading,
            modifier = Modifier.fillMaxWidth().height(58.dp),
            shape = RoundedCornerShape(18.dp),
        ) {
            if (translating) {
                CircularProgressIndicator(
                    Modifier.size(22.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                Text("  Перевожу…")
            } else {
                Icon(Icons.Default.Translate, null)
                Text("  Перевести")
            }
        }

        if (output.isNotBlank()) {
            Card(
                Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                ),
            ) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Перевод (${target.displayName})", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(output, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("Ling translation", output))
                            Toast.makeText(context, "Перевод скопирован", Toast.LENGTH_SHORT).show()
                        }) {
                            Icon(Icons.Default.ContentCopy, null)
                            Text("  Копировать")
                        }
                        OutlinedButton(onClick = { output = "" }) {
                            Icon(Icons.Default.Delete, null)
                            Text("  Очистить")
                        }
                    }
                }
            }
        }

        Text(
            "RU • EN • 中文 · офлайн",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Header(engineReady: Boolean, modelLoading: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Ling 2.0", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                Text("AI · Локальная модель", Modifier.padding(horizontal = 10.dp, vertical = 8.dp))
            }
        }

        Surface(
            Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = if (engineReady) Color(0xFFE5F6EC) else Color(0xFFFFF4DD),
        ) {
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                if (modelLoading) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Icon(
                        Icons.Default.CheckCircle,
                        null,
                        tint = if (engineReady) Color(0xFF208A53) else Color(0xFFB7791F),
                    )
                }
                Text(
                    when {
                        modelLoading -> "  Загружаю локальную модель…"
                        engineReady -> "  Офлайн · модель загружена"
                        else -> "  Офлайн · выберите модель в настройках"
                    },
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
            Icon(Icons.Default.ExpandMore, null)
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

private fun Language.flag() = when (this) {
    Language.RUSSIAN -> "🇷🇺"
    Language.ENGLISH -> "🇬🇧"
    Language.CHINESE -> "🇨🇳"
}

@Composable
private fun ComingSoon(title: String, description: String, camera: Boolean) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.primaryContainer) {
            Box(Modifier.padding(22.dp)) {
                Icon(if (camera) Icons.Default.CameraAlt else Icons.Default.Mic, null)
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
    onModel: () -> Unit,
    onHistory: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Настройки", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)

        SettingsRow(
            icon = { Icon(Icons.Default.Translate, null) },
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
            icon = { Icon(Icons.Default.History, null) },
            title = "История",
            subtitle = if (historyCount == 0) "Переводов пока нет" else "Сохранено: $historyCount",
            onClick = onHistory,
        )

        SettingsRow(
            icon = { Icon(Icons.Default.Settings, null) },
            title = "О приложении",
            subtitle = "Ling 2.0 · Android · офлайн",
            onClick = null,
        )
    }
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
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Назад") }
            Text("Локальная модель", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        }

        installed?.let { model ->
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
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
        modifier = Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        shape = RoundedCornerShape(18.dp),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            icon()
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (onClick != null) Icon(Icons.Default.ChevronRight, null)
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
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Назад") }
            Text("История", Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
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
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "${item.source.flag()} ${item.source.displayName} → ${item.target.flag()} ${item.target.displayName}",
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            )
            Text(item.input)
            Text(item.output, fontWeight = FontWeight.SemiBold)
            Text(date, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun BottomModes(selected: AppSection, onSelect: (AppSection) -> Unit) {
    Surface(tonalElevation = 4.dp, shadowElevation = 8.dp) {
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ModeTile(selected == AppSection.TRANSLATE, "Перевод", { Icon(Icons.Default.Translate, null) }, { onSelect(AppSection.TRANSLATE) }, Modifier.weight(1f))
            ModeTile(selected == AppSection.CAMERA, "Камера", { Icon(Icons.Default.CameraAlt, null) }, { onSelect(AppSection.CAMERA) }, Modifier.weight(1f))
            ModeTile(selected == AppSection.DIALOG, "Диалог", { Icon(Icons.Default.Mic, null) }, { onSelect(AppSection.DIALOG) }, Modifier.weight(1f))
            ModeTile(selected == AppSection.SETTINGS, "Настройки", { Icon(Icons.Default.Settings, null) }, { onSelect(AppSection.SETTINGS) }, Modifier.weight(1f))
        }
    }
}

@Composable
private fun ModeTile(
    selected: Boolean,
    label: String,
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
    ) {
        Column(
            Modifier.padding(horizontal = 4.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            icon()
            Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

private fun formatBytes(bytes: Long): String {
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1024.0) String.format(Locale.US, "%.2f GB", mb / 1024.0)
    else String.format(Locale.US, "%.0f MB", mb)
}
