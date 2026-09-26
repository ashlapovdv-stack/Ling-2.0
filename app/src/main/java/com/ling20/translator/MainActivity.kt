package com.ling20.translator

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val historyRepository = TranslationHistoryRepository(applicationContext)

        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Color(0xFF2F80ED),
                    onPrimary = Color.White,
                    primaryContainer = Color(0xFFDDEBFF),
                    onPrimaryContainer = Color(0xFF0A2A5E),
                    background = Color(0xFFF7F9FC),
                    surface = Color.White,
                    onSurface = Color(0xFF111827),
                    onSurfaceVariant = Color(0xFF667085),
                )
            ) {
                LingApp(historyRepository = historyRepository)
            }
        }
    }
}
