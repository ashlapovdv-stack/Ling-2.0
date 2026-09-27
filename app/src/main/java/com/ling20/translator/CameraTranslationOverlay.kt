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

private data class CameraScriptProfile(
    val cyrillic: Int,
    val latin: Int,
    val han: Int,
) {
    val letters: Int
        get() = cyrillic + latin + han
}

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
        if (block.confidence < MIN_CAMERA_TRANSLATION_CONFIDENCE && clean.count(Char::isLetter) < 4) {
            return@mapNotNull null
        }

        val profile = cameraScriptProfile(clean)
        val source = if (explicitSource != null) {
            explicitSource
        } else {
            // AUTO is deliberately conservative. OCR frequently confuses Latin
            // and Cyrillic glyphs on packaging; translating a line that already
            // contains a meaningful amount of the target script produces the
            // hallucinated cards seen on the Russian bottle tests.
            if (alreadyLooksLikeTargetLanguage(profile, target)) return@mapNotNull null
            detectCameraBlockLanguage(profile) ?: return@mapNotNull null
        }

        if (source == target) return@mapNotNull null
        if (explicitSource == null && looksLikeAutoBrand(clean, source)) return@mapNotNull null
        if (explicitSource == null && looksLikeAutoOcrNoise(clean, source)) return@mapNotNull null

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
            // Translate several spatial bands in ONE marked generation. This keeps
            // Chinese camera translation bounded to one native generation in the
            // common case instead of four or more 256-token generations.
            val spatialGroups = buildChineseSpatialGroups(group)
            val sourceTexts = spatialGroups.map { chunk ->
                chunk.joinToString(" • ") { it.text }
            }
            attempted += group.size

            var produced = 0
            runCatching {
                engine.translateBatch(
                    texts = sourceTexts,
                    source = source,
                    target = target,
                )
            }.onSuccess { outputs ->
                spatialGroups.zip(outputs).forEach { (chunk, rawOutput) ->
                    val sourceText = chunk.joinToString(" • ") { it.text }
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
                        produced++
                    }
                }
            }.onFailure { error ->
                lastFailure = error
            }

            // Some local models occasionally ignore every batch marker. One
            // ordinary document translation is a bounded fallback and is still
            // much faster than retrying every OCR line separately.
            if (produced == 0) {
                val sourceText = group
                    .sortedWith(compareBy<CameraTranslationCandidate> { it.rect.top }.thenBy { it.rect.left })
                    .joinToString(" • ") { it.text }
                runCatching {
                    engine.translate(sourceText, source, target).trim()
                }.onSuccess { rawOutput ->
                    val output = cleanCameraTranslation(rawOutput)
                    if (
                        output.isNotBlank() &&
                        normalizedCameraText(output) != normalizedCameraText(sourceText)
                    ) {
                        val first = group.minBy { it.order }
                        translatedByOrder[first.order] = CameraTranslatedBlock(
                            rect = unionCameraRects(group.map { it.rect }),
                            originalText = sourceText,
                            translatedText = output,
                        )
                    }
                }.onFailure { error ->
                    lastFailure = error
                }
            }
        } else if (group.size <= DIRECT_CAMERA_TRANSLATION_LIMIT) {
            // A couple of display words (for example AQUA / SPRAY) are cheaper
            // and more reliable as direct translations than as a marker batch.
            group.forEach { candidate ->
                attempted++
                runCatching {
                    engine.translate(candidate.text, source, target).trim()
                }.onSuccess { rawOutput ->
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

private fun buildChineseSpatialGroups(
    candidates: List<CameraTranslationCandidate>,
): List<List<CameraTranslationCandidate>> {
    val sorted = candidates.sortedWith(
        compareBy<CameraTranslationCandidate> { it.rect.top }.thenBy { it.rect.left },
    )
    val targetGroupCount = ceil(sorted.size / CHINESE_LINES_PER_OVERLAY_GROUP.toDouble())
        .toInt()
        .coerceIn(1, CHINESE_MAX_OVERLAY_GROUPS)
    val chunkSize = ceil(sorted.size / targetGroupCount.toDouble()).toInt().coerceAtLeast(1)
    return sorted.chunked(chunkSize)
}

private fun unionCameraRects(rects: List<Rect>): Rect {
    if (rects.isEmpty()) return Rect()
    val result = Rect(rects.first())
    rects.drop(1).forEach(result::union)
    return result
}

private fun cameraScriptProfile(text: String): CameraScriptProfile {
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
    return CameraScriptProfile(cyrillic = cyrillic, latin = latin, han = han)
}

private fun alreadyLooksLikeTargetLanguage(
    profile: CameraScriptProfile,
    target: Language,
): Boolean = when (target) {
    Language.RUSSIAN ->
        profile.cyrillic >= 2 && profile.cyrillic * 2 >= profile.latin && profile.han == 0
    Language.ENGLISH ->
        profile.latin >= 3 && profile.cyrillic == 0 && profile.han == 0
    Language.CHINESE ->
        profile.han >= 1 && profile.han >= profile.cyrillic + profile.latin / 2
}

private fun detectCameraBlockLanguage(profile: CameraScriptProfile): Language? {
    if (profile.letters == 0) return null

    // Han is distinctive enough that a small amount of Latin OCR noise should
    // not turn a Chinese line into English.
    if (profile.han >= 1 && profile.han * 2 >= max(profile.latin, profile.cyrillic)) {
        return Language.CHINESE
    }

    // Any substantial Cyrillic presence wins over visually-similar Latin OCR.
    if (profile.cyrillic >= 2 && profile.cyrillic * 2 >= profile.latin) {
        return Language.RUSSIAN
    }

    // AUTO only translates English when the line is cleanly Latin. Mixed-script
    // packaging text is safer left untouched than mistranslated.
    if (profile.latin >= 2 && profile.cyrillic == 0 && profile.han == 0) {
        return Language.ENGLISH
    }

    return null
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

private fun looksLikeAutoOcrNoise(text: String, source: Language): Boolean {
    if (source != Language.ENGLISH) return false

    val letters = text.filter(Char::isLetter)
    if (letters.isEmpty()) return true

    // Long all-caps pseudo-Latin tokens such as AKBA-KPEM are commonly Russian
    // Cyrillic text misread by the Latin OCR pass. Short display words AQUA and
    // SPRAY remain eligible for translation.
    val longAllCapsToken =
        !text.any(Char::isWhitespace) && letters.length > 5 && letters.all(Char::isUpperCase)
    val alphaNumericFragment =
        text.any(Char::isDigit) && letters.length <= 8 && !text.any(Char::isWhitespace)

    return longAllCapsToken || alphaNumericFragment
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
            val minHeightValue = sourceHeight.value.coerceIn(MIN_CARD_HEIGHT_DP, MAX_MIN_CARD_HEIGHT_DP)
            val maxHeightValue = max(
                minHeightValue,
                min(sourceHeight.value * CARD_MAX_HEIGHT_FACTOR, CARD_ABSOLUTE_MAX_HEIGHT_DP),
            )
            val minHeight = minHeightValue.dp
            val maxHeight = maxHeightValue.dp
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
    val boundedSourceHeight = min(sourceHeightDp, FONT_GEOMETRY_HEIGHT_CAP_DP)
    val geometryLimit = (boundedSourceHeight * 0.58f).coerceIn(MIN_FONT_SP, MAX_FONT_SP)
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

private const val MIN_CAMERA_TRANSLATION_CONFIDENCE = 8f
private const val DIRECT_CAMERA_TRANSLATION_LIMIT = 4
private const val CHINESE_LINES_PER_OVERLAY_GROUP = 8
private const val CHINESE_MAX_OVERLAY_GROUPS = 4
private const val CARD_WIDTH_EXPANSION = 1.12f
private const val CARD_MAX_IMAGE_WIDTH_FRACTION = 0.68f
private const val CARD_MAX_HEIGHT_FACTOR = 1.20f
private const val CARD_MAX_LINES = 5
private const val MIN_CARD_HEIGHT_DP = 13f
private const val MAX_MIN_CARD_HEIGHT_DP = 38f
private const val CARD_ABSOLUTE_MAX_HEIGHT_DP = 112f
private const val FONT_GEOMETRY_HEIGHT_CAP_DP = 28f
private const val MIN_FONT_SP = 8.0f
private const val MAX_FONT_SP = 16.0f
