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

private data class CameraTranslationCandidate(
    val order: Int,
    val rect: Rect,
    val text: String,
    val source: Language,
)

internal suspend fun translateCameraOcrBlocks(
    engine: TranslationEngine,
    result: CameraOcrResult,
    explicitSource: Language?,
    target: Language,
): List<CameraTranslatedBlock> = withContext(Dispatchers.Default) {
    check(engine.isReady) { "Локальная модель не загружена" }

    val rawCandidates = if (result.lines.isNotEmpty()) {
        result.lines.mapIndexed { index, line ->
            index to CameraOcrBlock(line.text, Rect(line.rect), line.confidence)
        }
    } else {
        result.blocks.mapIndexed { index, block -> index to block }
    }

    val candidates = rawCandidates.mapNotNull { (index, block) ->
        val clean = block.text
            .replace(Regex("\\s+"), " ")
            .trim()
        if (clean.count(Char::isLetter) < 2) return@mapNotNull null
        if (block.confidence < 4f && clean.count(Char::isLetter) < 4) return@mapNotNull null

        // AUTO may contain several languages on one label. Never translate text
        // that is visibly already written in the requested target script.
        if (explicitSource == null && alreadyLooksLikeTargetLanguage(clean, target)) {
            return@mapNotNull null
        }

        val source = explicitSource ?: detectCameraBlockLanguage(clean) ?: return@mapNotNull null
        if (source == target) return@mapNotNull null

        // Protect only a likely single-word Latin brand. Uppercase display text
        // such as AQUA SPRAY is real content and must still be translated.
        if (explicitSource == null && looksLikeAutoBrand(clean, source)) {
            return@mapNotNull null
        }

        CameraTranslationCandidate(
            order = index,
            rect = Rect(block.rect),
            text = clean,
            source = source,
        )
    }

    val translatedByOrder = mutableMapOf<Int, CameraTranslatedBlock>()
    var lastFailure: Throwable? = null
    var attempted = 0

    candidates.groupBy { it.source }.forEach { (source, group) ->
        if (source == Language.CHINESE && group.size > 1) {
            // Marker-heavy batch prompts were unreliable with Chinese OCR and could
            // leave the screen spinning with no usable cards. Translate compact
            // spatial chunks as ordinary text instead: fewer generations than one
            // line at a time, and every successful generation produces an overlay.
            group.chunked(CHINESE_CAMERA_CHUNK_SIZE).forEach { chunk ->
                attempted += chunk.size
                val sourceText = chunk.joinToString(" • ") { it.text }
                runCatching {
                    engine.translate(sourceText, source, target).trim()
                }.onSuccess { rawOutput ->
                    val output = cleanCameraTranslation(rawOutput)
                    if (
                        output.isNotBlank() &&
                        normalizedCameraText(output) != normalizedCameraText(sourceText)
                    ) {
                        val first = chunk.first()
                        translatedByOrder[first.order] = CameraTranslatedBlock(
                            rect = unionCameraRects(chunk.map { it.rect }),
                            originalText = sourceText,
                            translatedText = output,
                        )
                    }
                }.onFailure { error ->
                    lastFailure = error
                }
            }
        } else {
            attempted += group.size
            runCatching {
                engine.translateBatch(
                    texts = group.map { it.text },
                    source = source,
                    target = target,
                )
            }.onSuccess { outputs ->
                group.zip(outputs).forEach { (candidate, rawOutput) ->
                    val output = cleanCameraTranslation(rawOutput)
                    if (
                        output.isNotBlank() &&
                        normalizedCameraText(output) != normalizedCameraText(candidate.text)
                    ) {
                        translatedByOrder[candidate.order] = CameraTranslatedBlock(
                            rect = Rect(candidate.rect),
                            originalText = candidate.text,
                            translatedText = output,
                        )
                    }
                }

                // If a model ignored batch markers, do not silently throw all of
                // those lines away. Translate only the still-missing items in a
                // few compact groups and anchor each result to their union rect.
                val missing = group.filter { it.order !in translatedByOrder }
                missing.chunked(CAMERA_FALLBACK_CHUNK_SIZE).forEach { chunk ->
                    val sourceText = chunk.joinToString(" • ") { it.text }
                    runCatching {
                        engine.translate(sourceText, source, target).trim()
                    }.onSuccess { rawFallback ->
                        val fallback = cleanCameraTranslation(rawFallback)
                        if (
                            fallback.isNotBlank() &&
                            normalizedCameraText(fallback) != normalizedCameraText(sourceText)
                        ) {
                            val first = chunk.first()
                            translatedByOrder[first.order] = CameraTranslatedBlock(
                                rect = unionCameraRects(chunk.map { it.rect }),
                                originalText = sourceText,
                                translatedText = fallback,
                            )
                        }
                    }.onFailure { error ->
                        lastFailure = error
                    }
                }
            }.onFailure { error ->
                lastFailure = error
            }
        }
    }

    if (translatedByOrder.isEmpty() && attempted > 0 && lastFailure != null) {
        throw lastFailure!!
    }

    val translated = translatedByOrder
        .toSortedMap()
        .values
        .toList()

    if (translated.isEmpty()) {
        listOf(CameraTranslatedBlock(Rect(), "", ""))
    } else {
        translated
    }
}

private fun unionCameraRects(rects: List<Rect>): Rect {
    if (rects.isEmpty()) return Rect()
    val result = Rect(rects.first())
    rects.drop(1).forEach(result::union)
    return result
}

private fun alreadyLooksLikeTargetLanguage(text: String, target: Language): Boolean {
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

    return when (target) {
        Language.RUSSIAN -> cyrillic >= 2 && cyrillic >= latin
        Language.ENGLISH -> latin >= 3 && latin >= cyrillic * 2 && han == 0
        Language.CHINESE -> han >= 1 && han >= latin + cyrillic
    }
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

private fun looksLikeAutoBrand(text: String, source: Language): Boolean {
    if (source != Language.ENGLISH) return false

    val words = text
        .split(Regex("\\s+"))
        .filter { it.any(Char::isLetter) }
    val letters = text.filter(Char::isLetter)
    if (words.size != 1 || letters.length !in 4..20) return false

    return letters.firstOrNull()?.isUpperCase() == true &&
        letters.drop(1).any(Char::isLowerCase) &&
        letters.drop(1).count(Char::isUpperCase) <= 1
}

private fun cleanCameraTranslation(text: String): String = text
    .replace(Regex("\\[\\[LING_\\d+]]"), "")
    .replace(Regex("[ \\t]+"), " ")
    .replace(Regex(" *\\n *"), "\n")
    .trim()

private fun normalizedCameraText(text: String): String = text
    .lowercase()
    .filter(Char::isLetterOrDigit)

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
        val imageRightPx = imageOffsetX + displayedWidth
        val minCardWidthPx = with(density) { 42.dp.toPx() }

        translations.forEach { block ->
            if (
                block.translatedText.isBlank() ||
                block.rect.width() <= 0 ||
                block.rect.height() <= 0
            ) {
                return@forEach
            }

            val leftPx = imageOffsetX + block.rect.left * scale
            val topPx = imageOffsetY + block.rect.top * scale
            val sourceWidthPx = block.rect.width() * scale
            val sourceHeightPx = block.rect.height() * scale
            val availableWidthPx = (imageRightPx - leftPx).coerceAtLeast(1f)
            val desiredWidthPx = max(minCardWidthPx, sourceWidthPx * CARD_WIDTH_EXPANSION)
            val maxWidthPx = min(displayedWidth * CARD_MAX_IMAGE_WIDTH_FRACTION, availableWidthPx)
            val cardWidthPx = min(desiredWidthPx, maxWidthPx).coerceAtLeast(
                min(sourceWidthPx, availableWidthPx),
            )

            val left = with(density) { leftPx.toDp() }
            val top = with(density) { topPx.toDp() }
            val width = with(density) { cardWidthPx.toDp() }
            val sourceHeight = with(density) { sourceHeightPx.toDp() }
            val minHeight = sourceHeight.coerceAtLeast(11.dp)
            val maxHeight = maxOf(minHeight, sourceHeight * CARD_MAX_HEIGHT_FACTOR, 21.dp)
            val fontSp = estimateOverlayFontSp(
                widthDp = width.value,
                sourceHeightDp = sourceHeight.value,
                maxHeightDp = maxHeight.value,
                translatedText = block.translatedText,
            )

            Surface(
                modifier = Modifier
                    .offset(x = left, y = top)
                    .width(width)
                    .heightIn(min = minHeight, max = maxHeight),
                shape = RoundedCornerShape(5.dp),
                color = Color.White.copy(alpha = 0.92f),
                contentColor = Color(0xFF14202B),
                shadowElevation = 1.dp,
            ) {
                Text(
                    text = block.translatedText,
                    modifier = Modifier.padding(horizontal = 3.dp, vertical = 2.dp),
                    fontSize = fontSp.sp,
                    lineHeight = (fontSp * 1.06f).sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = CARD_MAX_LINES,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun estimateOverlayFontSp(
    widthDp: Float,
    sourceHeightDp: Float,
    maxHeightDp: Float,
    translatedText: String,
): Float {
    val geometryLimit = (sourceHeightDp * 0.58f).coerceIn(MIN_FONT_SP, MAX_FONT_SP)
    val contentLength = translatedText.count { !it.isWhitespace() }.coerceAtLeast(1)

    var size = geometryLimit
    while (size > MIN_FONT_SP) {
        val estimatedCharWidth = size * 0.53f
        val charsPerLine = max(1, (widthDp / estimatedCharWidth).toInt())
        val estimatedLines = ceil(contentLength.toDouble() / charsPerLine.toDouble()).toInt()
            .coerceAtLeast(1)
        if (estimatedLines <= CARD_MAX_LINES && estimatedLines * size * 1.06f <= maxHeightDp) {
            break
        }
        size -= 0.5f
    }
    return size.coerceIn(MIN_FONT_SP, MAX_FONT_SP)
}

private const val CHINESE_CAMERA_CHUNK_SIZE = 8
private const val CAMERA_FALLBACK_CHUNK_SIZE = 5
private const val CARD_WIDTH_EXPANSION = 1.18f
private const val CARD_MAX_IMAGE_WIDTH_FRACTION = 0.78f
private const val CARD_MAX_HEIGHT_FACTOR = 1.65f
private const val CARD_MAX_LINES = 6
private const val MIN_FONT_SP = 8.0f
private const val MAX_FONT_SP = 17.0f
