package com.ling20.translator

import android.graphics.Rect
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

data class CameraTranslatedBlock(
    val rect: Rect,
    val originalText: String,
    val translatedText: String,
)

internal suspend fun translateCameraOcrBlocks(
    engine: TranslationEngine,
    result: CameraOcrResult,
    explicitSource: Language?,
    target: Language,
): List<CameraTranslatedBlock> = withContext(Dispatchers.Default) {
    check(engine.isReady) { "Локальная модель не загружена" }

    val candidates = if (result.blocks.isNotEmpty()) {
        result.blocks
    } else {
        result.lines.map { line ->
            CameraOcrBlock(
                text = line.text,
                rect = Rect(line.rect),
                confidence = line.confidence,
            )
        }
    }

    val translated = mutableListOf<CameraTranslatedBlock>()
    var attempted = 0
    var lastFailure: Throwable? = null

    candidates.forEach { block ->
        val clean = block.text
            .replace(Regex("\\s+"), " ")
            .trim()
        if (clean.count(Char::isLetter) < 2) return@forEach

        val source = explicitSource ?: detectCameraBlockLanguage(clean) ?: return@forEach
        // In Auto mode labels already written in the requested target language
        // stay untouched. This keeps brands such as "Compliment" readable while
        // Russian/Chinese neighbours are translated on the same photograph.
        if (source == target) return@forEach

        attempted++
        runCatching {
            engine.translate(clean, source, target).trim()
        }.onSuccess { output ->
            if (output.isNotBlank()) {
                translated += CameraTranslatedBlock(
                    rect = Rect(block.rect),
                    originalText = block.text,
                    translatedText = output,
                )
            }
        }.onFailure { error ->
            lastFailure = error
        }
    }

    if (translated.isEmpty() && attempted > 0 && lastFailure != null) {
        throw lastFailure!!
    }
    translated
}

private fun detectCameraBlockLanguage(text: String): Language? {
    var cyrillic = 0
    var latin = 0
    var han = 0
    text.forEach { char ->
        when {
            char in '\u0400'..'\u04FF' -> cyrillic++
            char in '\u4E00'..'\u9FFF' -> han++
            char.isLetter() && char.code < 0x0250 -> latin++
        }
    }
    val strongest = max(cyrillic, max(latin, han))
    if (strongest == 0) return null
    return when (strongest) {
        han -> Language.CHINESE
        cyrillic -> Language.RUSSIAN
        else -> Language.ENGLISH
    }
}

@Composable
internal fun CameraTranslationOverlay(
    result: CameraOcrResult,
    translations: List<CameraTranslatedBlock>,
    modifier: Modifier = Modifier,
) {
    if (result.imageWidth <= 0 || result.imageHeight <= 0 || translations.isEmpty()) return

    BoxWithConstraints(modifier = modifier) {
        val density = LocalDensity.current
        val containerWidthPx = with(density) { maxWidth.toPx() }
        val containerHeightPx = with(density) { maxHeight.toPx() }
        val scale = min(
            containerWidthPx / result.imageWidth.toFloat(),
            containerHeightPx / result.imageHeight.toFloat(),
        )
        val displayedWidth = result.imageWidth * scale
        val displayedHeight = result.imageHeight * scale
        val imageOffsetX = (containerWidthPx - displayedWidth) / 2f
        val imageOffsetY = (containerHeightPx - displayedHeight) / 2f

        translations.forEach { block ->
            val leftPx = imageOffsetX + block.rect.left * scale
            val topPx = imageOffsetY + block.rect.top * scale
            val sourceWidthPx = block.rect.width() * scale
            val sourceHeightPx = block.rect.height() * scale
            val availableWidthPx = (containerWidthPx - leftPx).coerceAtLeast(1f)
            val cardWidthPx = min(
                sourceWidthPx.coerceAtLeast(with(density) { 38.dp.toPx() }),
                availableWidthPx,
            )

            val left = with(density) { leftPx.toDp() }
            val top = with(density) { topPx.toDp() }
            val width = with(density) { cardWidthPx.toDp() }
            val minHeight = with(density) { sourceHeightPx.toDp() }
            val fontSp = estimateOverlayFontSp(
                widthDp = width.value,
                heightDp = minHeight.value,
                translatedText = block.translatedText,
                originalText = block.originalText,
            )

            Surface(
                modifier = Modifier
                    .offset(x = left, y = top)
                    .width(width)
                    .heightIn(min = minHeight.coerceAtLeast(12.dp)),
                shape = RoundedCornerShape(5.dp),
                color = Color.White.copy(alpha = 0.88f),
                contentColor = Color(0xFF14202B),
                shadowElevation = 1.dp,
            ) {
                Text(
                    text = block.translatedText,
                    modifier = Modifier.padding(horizontal = 3.dp, vertical = 2.dp),
                    fontSize = fontSp.sp,
                    lineHeight = (fontSp * 1.08f).sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 10,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun estimateOverlayFontSp(
    widthDp: Float,
    heightDp: Float,
    translatedText: String,
    originalText: String,
): Float {
    val originalLines = (originalText.count { it == '\n' } + 1).coerceAtLeast(1)
    val geometryLimit = (heightDp / originalLines * 0.62f).coerceIn(8.5f, 22f)
    val targetHeight = max(heightDp * 1.65f, 14f)
    val contentLength = translatedText.count { !it.isWhitespace() }.coerceAtLeast(1)

    var size = geometryLimit
    while (size > 8.5f) {
        val estimatedCharWidth = size * 0.54f
        val charsPerLine = max(1, (widthDp / estimatedCharWidth).toInt())
        val estimatedLines = max(
            originalLines,
            ceil(contentLength.toDouble() / charsPerLine.toDouble()).toInt(),
        )
        if (estimatedLines * size * 1.08f <= targetHeight) break
        size -= 0.5f
    }
    return size.coerceIn(8.5f, 22f)
}
