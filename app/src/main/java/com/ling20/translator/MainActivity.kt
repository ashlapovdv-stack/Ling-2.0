package com.ling20.translator

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    TranslatorScreen()
                }
            }
        }
    }
}

@Composable
private fun TranslatorScreen(
    engine: TranslationEngine = ModelNotLoadedEngine,
) {
    var source by remember { mutableStateOf(Language.RUSSIAN) }
    var target by remember { mutableStateOf(Language.ENGLISH) }
    var input by remember { mutableStateOf("") }
    var output by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            text = "Ling 2.0",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "Офлайн нейропереводчик",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Text(
            text = if (engine.isReady) "● Модель готова" else "○ Модель ещё не подключена",
            style = MaterialTheme.typography.labelLarge,
            color = if (engine.isReady) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )

        HorizontalDivider()

        LanguageSelector(
            title = "Исходный язык",
            selected = source,
            onSelected = { language ->
                source = language
                if (source == target) {
                    target = Language.entries.first { it != source }
                }
            },
        )

        LanguageSelector(
            title = "Перевести на",
            selected = target,
            onSelected = { language ->
                target = language
                if (source == target) {
                    source = Language.entries.first { it != target }
                }
            },
        )

        OutlinedButton(
            onClick = {
                val previousSource = source
                source = target
                target = previousSource
                val previousInput = input
                if (output.isNotBlank() && !output.startsWith("Модель")) {
                    input = output
                    output = previousInput
                }
            },
        ) {
            Text("⇄ Поменять языки")
        }

        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Текст") },
            minLines = 5,
            maxLines = 12,
        )

        Button(
            onClick = {
                output = if (!engine.isReady) {
                    "Модель ещё не подключена. Следующий этап — llama.cpp + GGUF."
                } else {
                    runCatching {
                        engine.translate(input.trim(), source, target)
                    }.getOrElse { error ->
                        "Ошибка перевода: ${error.message ?: "unknown error"}"
                    }
                }
            },
            enabled = input.isNotBlank() && source != target,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Перевести офлайн")
        }

        if (output.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Результат",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Surface(
                modifier = Modifier.fillMaxWidth(),
                tonalElevation = 2.dp,
                shape = MaterialTheme.shapes.medium,
            ) {
                Text(
                    text = output,
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        Text(
            text = "RU • EN • 中文  |  перевод без интернета",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LanguageSelector(
    title: String,
    selected: Language,
    onSelected: (Language) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Language.entries.forEach { language ->
                FilterChip(
                    selected = language == selected,
                    onClick = { onSelected(language) },
                    label = { Text(language.displayName) },
                )
            }
        }
    }
}
