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

    // Line geometry is a much better overlay anchor than paragraph blocks: it
    // keeps labels compact and prevents one translation card from covering a
    // large part of a product. Blocks remain a fallback for unusual OCR results.
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
        if (block.confidence < MIN_TRANSLATION_CONFIDENCE) return@mapNotNull null

        val source = explicitSource ?: detectCameraBlockLanguage(clean) ?: return@mapNotNull null
        if (source == target) return@mapNotNull null
        if (explicitSource == null && looksLikeAutoDisplayLabel(clean, source)) {
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
        }.onFailure { error ->
            lastFailure = error
        }
    }

    if (translatedByOrder.isEmpty() && attempted > 0 && lastFailure != null) {
        throw lastFailure!!
    }

    val translated = translatedByOrder
        .toSortedMap()
        .values
        .toList()

    // A blank sentinel tells CameraModeScreen that translation has completed
    // even when every visible line is already in the target language or was a
    // protected brand/display label. CameraTranslationOverlay ignores it, so the
    // user sees the clean photo instead of permanent debug OCR rectangles.
    if (translated.isEmpty()) {
        listOf(CameraTranslatedBlock(Rect(), "", ""))
    } else {
        translated
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

private fun looksLikeAutoDisplayLabel(text: String, source: Language): Boolean {
    // AUTO mode should not turn logos/product names into literal translations.
    // The bottle test exposed both cases: "Compliment" became "Благодарность"
    // and short display lettering such as AQUA/SPRAY generated noisy cards.
    if (source != Language.ENGLISH) return false

    val words = text
        .split(Regex("\\s+"))
        .filter { it.any(Char::isLetter) }
    val letters = text.filter(Char::isLetter)
    if (letters.length !in 3..28) return false

    val singleBrandLike = words.size == 1 &&
        letters.length <= 20 &&
        letters.firstOrNull()?.isUpperCase() == true &&
        letters.drop(1).any(Char::isLowerCase)
    val shortDisplayCaps = words.size <= 3 &&
        letters.length <= 24 &&
        letters.all(Char::isUpperCase)

    return singleBrandLike || shortDisplayCaps
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
            val maxHeight = maxOf(minHeight, sourceHeight * CARD_MAX_HEIGHT_FACTOR, 19.dp)
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
                color = Color.White.copy(alpha = 0.91f),
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

private const val MIN_TRANSLATION_CONFIDENCE = 18f
private const val CARD_WIDTH_EXPANSION = 1.18f
private const val CARD_MAX_IMAGE_WIDTH_FRACTION = 0.72f
private const val CARD_MAX_HEIGHT_FACTOR = 1.65f
private const val CARD_MAX_LINES = 4
private const val MIN_FONT_SP = 8.0f
private const val MAX_FONT_SP = 17.0f
