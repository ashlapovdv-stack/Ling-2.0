package com.ling20.translator

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.Rect
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.googlecode.tesseract.android.TessBaseAPI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class CameraOcrBlock(
    val text: String,
    val rect: Rect,
    val confidence: Float,
)

data class CameraOcrResult(
    val imageWidth: Int,
    val imageHeight: Int,
    val fullText: String,
    val blocks: List<CameraOcrBlock>,
    val languageSpec: String,
)

private data class PreparedImage(
    val bitmap: Bitmap,
    val scaleFromOriginal: Float,
)

private data class RawOcrLine(
    val text: String,
    val rect: Rect,
    val confidence: Float,
)

internal object CameraOcrEngine {
    suspend fun recognize(
        context: Context,
        uriString: String,
        sourceLanguage: Language?,
    ): CameraOcrResult = withContext(Dispatchers.Default) {
        val original = loadCameraBitmap(context, uriString)
        val primaryLanguage = sourceLanguage.toTessLanguage()
        val allRequiredLanguages = if (sourceLanguage == null) {
            setOf("rus", "eng", "chi_sim")
        } else {
            setOf(primaryLanguage)
        }
        val dataPath = prepareTessData(context, allRequiredLanguages)
        val preparedImages = prepareImages(original)

        try {
            val rawLines = mutableListOf<RawOcrLine>()
            rawLines += recognizeWithLanguage(
                dataPath = dataPath,
                languageSpec = primaryLanguage,
                preparedImages = preparedImages,
                originalWidth = original.width,
                originalHeight = original.height,
            )

            // AUTO gets a second language-specific attempt. It noticeably helps
            // labels/signs where one script dominates but the combined model is
            // distracted by decorative fonts or mixed text.
            if (sourceLanguage == null) {
                val dominant = detectDominantLanguage(rawLines)
                val fallbackLanguages = if (isWeakResult(rawLines)) {
                    listOf("rus", "eng", "chi_sim")
                } else {
                    dominant?.let(::listOf).orEmpty()
                }

                fallbackLanguages
                    .filter { it != primaryLanguage }
                    .forEach { language ->
                        rawLines += recognizeWithLanguage(
                            dataPath = dataPath,
                            languageSpec = language,
                            preparedImages = preparedImages,
                            originalWidth = original.width,
                            originalHeight = original.height,
                        )
                    }
            }

            val deduplicated = deduplicateLines(rawLines)
            val blocks = groupLinesIntoBlocks(
                lines = deduplicated,
                imageWidth = original.width,
                imageHeight = original.height,
            )

            CameraOcrResult(
                imageWidth = original.width,
                imageHeight = original.height,
                fullText = blocks.joinToString("\n") { it.text }.trim(),
                blocks = blocks,
                languageSpec = primaryLanguage,
            )
        } finally {
            preparedImages
                .map { it.bitmap }
                .distinctBy { System.identityHashCode(it) }
                .forEach { bitmap ->
                    if (bitmap !== original && !bitmap.isRecycled) bitmap.recycle()
                }
            if (!original.isRecycled) original.recycle()
        }
    }

    private fun recognizeWithLanguage(
        dataPath: File,
        languageSpec: String,
        preparedImages: List<PreparedImage>,
        originalWidth: Int,
        originalHeight: Int,
    ): List<RawOcrLine> {
        val tess = TessBaseAPI()
        return try {
            check(
                tess.init(
                    dataPath.absolutePath,
                    languageSpec,
                    TessBaseAPI.OEM_LSTM_ONLY,
                ),
            ) { "Не удалось запустить офлайн OCR." }

            val result = mutableListOf<RawOcrLine>()

            // AUTO works best for ordinary paragraphs. SPARSE_TEXT is much
            // better for product labels, signs and large isolated headings.
            val passes = listOf(
                preparedImages[0] to TessBaseAPI.PageSegMode.PSM_AUTO,
                preparedImages[1] to TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT,
            )

            passes.forEach { (image, pageSegMode) ->
                tess.setPageSegMode(pageSegMode)
                tess.setImage(image.bitmap)
                val fullText = tess.getUTF8Text().orEmpty()
                if (fullText.isNotBlank()) {
                    result += extractLines(
                        tess = tess,
                        imageScale = image.scaleFromOriginal,
                        originalWidth = originalWidth,
                        originalHeight = originalHeight,
                    )
                }
                tess.clear()
            }

            result
        } finally {
            tess.recycle()
        }
    }

    private fun extractLines(
        tess: TessBaseAPI,
        imageScale: Float,
        originalWidth: Int,
        originalHeight: Int,
    ): List<RawOcrLine> {
        val lines = collectIteratorLevel(
            tess = tess,
            level = TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE,
            imageScale = imageScale,
            originalWidth = originalWidth,
            originalHeight = originalHeight,
        )
        if (lines.isNotEmpty()) return lines

        // Some sparse/decorative labels expose only word-level boxes.
        return collectIteratorLevel(
            tess = tess,
            level = TessBaseAPI.PageIteratorLevel.RIL_WORD,
            imageScale = imageScale,
            originalWidth = originalWidth,
            originalHeight = originalHeight,
        )
    }

    private fun collectIteratorLevel(
        tess: TessBaseAPI,
        level: Int,
        imageScale: Float,
        originalWidth: Int,
        originalHeight: Int,
    ): List<RawOcrLine> {
        val iterator = tess.resultIterator ?: return emptyList()
        val result = mutableListOf<RawOcrLine>()

        try {
            iterator.begin()
            do {
                val text = iterator.getUTF8Text(level)?.trim().orEmpty()
                val processedRect = iterator.getBoundingRect(level)
                val confidence = iterator.confidence(level)

                if (
                    text.isMeaningfulOcrText() &&
                    processedRect != null &&
                    confidence >= RAW_MIN_CONFIDENCE
                ) {
                    val rect = processedRect.toOriginalRect(
                        scale = imageScale,
                        width = originalWidth,
                        height = originalHeight,
                    )
                    if (rect.width() >= MIN_BOX_PIXELS && rect.height() >= MIN_BOX_PIXELS) {
                        result += RawOcrLine(
                            text = text,
                            rect = rect,
                            confidence = confidence,
                        )
                    }
                }
            } while (iterator.next(level))
        } finally {
            iterator.delete()
        }

        return result
    }

    private fun prepareImages(original: Bitmap): List<PreparedImage> {
        val longest = max(original.width, original.height).coerceAtLeast(1)
        val requestedScale = when {
            longest < 1400 -> 2.0f
            longest < 1900 -> 1.45f
            longest > 3000 -> 3000f / longest
            else -> 1f
        }
        val width = max(1, (original.width * requestedScale).roundToInt())
        val height = max(1, (original.height * requestedScale).roundToInt())
        val scaled = Bitmap.createScaledBitmap(original, width, height, true)
        val actualScale = width.toFloat() / original.width.toFloat()

        val enhanced = makeHighContrastGrayscale(scaled, binary = false)
        val binary = makeHighContrastGrayscale(scaled, binary = true)

        if (scaled !== original && scaled !== enhanced && scaled !== binary) {
            scaled.recycle()
        }

        return listOf(
            PreparedImage(enhanced, actualScale),
            PreparedImage(binary, actualScale),
        )
    }

    private fun makeHighContrastGrayscale(source: Bitmap, binary: Boolean): Bitmap {
        val width = source.width
        val height = source.height
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)

        val gray = IntArray(pixels.size)
        val histogram = IntArray(256)
        pixels.indices.forEach { index ->
            val pixel = pixels[index]
            val red = (pixel shr 16) and 0xFF
            val green = (pixel shr 8) and 0xFF
            val blue = pixel and 0xFF
            val value = ((red * 299 + green * 587 + blue * 114) / 1000).coerceIn(0, 255)
            gray[index] = value
            histogram[value]++
        }

        val low = percentile(histogram, pixels.size, 0.02f)
        val high = percentile(histogram, pixels.size, 0.98f).coerceAtLeast(low + 12)
        val otsu = otsuThreshold(histogram, pixels.size)
        val output = IntArray(pixels.size)

        gray.indices.forEach { index ->
            val stretched = ((gray[index] - low) * 255 / (high - low)).coerceIn(0, 255)
            val value = if (binary) {
                if (stretched >= otsu) 255 else 0
            } else {
                stretched
            }
            output[index] = (0xFF shl 24) or (value shl 16) or (value shl 8) or value
        }

        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
            bitmap.setPixels(output, 0, width, 0, 0, width, height)
        }
    }

    private fun percentile(histogram: IntArray, total: Int, fraction: Float): Int {
        val target = (total * fraction).roundToInt().coerceAtLeast(1)
        var count = 0
        histogram.forEachIndexed { value, amount ->
            count += amount
            if (count >= target) return value
        }
        return 255
    }

    private fun otsuThreshold(histogram: IntArray, total: Int): Int {
        if (total <= 0) return 128
        var sum = 0.0
        histogram.forEachIndexed { value, amount -> sum += value * amount.toDouble() }

        var backgroundWeight = 0
        var backgroundSum = 0.0
        var bestVariance = -1.0
        var bestThreshold = 128

        for (threshold in 0..255) {
            backgroundWeight += histogram[threshold]
            if (backgroundWeight == 0) continue
            val foregroundWeight = total - backgroundWeight
            if (foregroundWeight == 0) break

            backgroundSum += threshold * histogram[threshold].toDouble()
            val backgroundMean = backgroundSum / backgroundWeight
            val foregroundMean = (sum - backgroundSum) / foregroundWeight
            val variance = backgroundWeight.toDouble() * foregroundWeight *
                (backgroundMean - foregroundMean) * (backgroundMean - foregroundMean)

            if (variance > bestVariance) {
                bestVariance = variance
                bestThreshold = threshold
            }
        }
        return bestThreshold
    }

    private fun deduplicateLines(lines: List<RawOcrLine>): List<RawOcrLine> {
        val accepted = mutableListOf<RawOcrLine>()
        lines
            .sortedByDescending { it.confidence }
            .forEach { candidate ->
                val duplicateIndex = accepted.indexOfFirst { existing ->
                    rectIou(existing.rect, candidate.rect) >= DUPLICATE_IOU ||
                        (
                            normalizedText(existing.text) == normalizedText(candidate.text) &&
                                centerDistance(existing.rect, candidate.rect) <=
                                max(existing.rect.height(), candidate.rect.height()) * 1.5f
                            )
                }
                if (duplicateIndex < 0) accepted += candidate
            }
        return accepted.sortedWith(compareBy<RawOcrLine> { it.rect.top }.thenBy { it.rect.left })
    }

    private fun groupLinesIntoBlocks(
        lines: List<RawOcrLine>,
        imageWidth: Int,
        imageHeight: Int,
    ): List<CameraOcrBlock> {
        if (lines.isEmpty()) return emptyList()

        data class MutableBlock(
            val lines: MutableList<RawOcrLine>,
            var rect: Rect,
        )

        val groups = mutableListOf<MutableBlock>()
        lines.forEach { line ->
            val bestGroup = groups
                .filter { canJoin(it.rect, line.rect) }
                .minByOrNull { group -> verticalGap(group.rect, line.rect) }

            if (bestGroup == null) {
                groups += MutableBlock(mutableListOf(line), Rect(line.rect))
            } else {
                bestGroup.lines += line
                bestGroup.rect.union(line.rect)
            }
        }

        val minWidth = max(MIN_BOX_PIXELS, (imageWidth * MIN_BLOCK_WIDTH_FRACTION).roundToInt())
        val minHeight = max(MIN_BOX_PIXELS, (imageHeight * MIN_BLOCK_HEIGHT_FRACTION).roundToInt())

        return groups
            .mapNotNull { group ->
                val sortedLines = group.lines.sortedWith(
                    compareBy<RawOcrLine> { it.rect.top }.thenBy { it.rect.left },
                )
                val text = sortedLines.joinToString("\n") { it.text.trim() }.trim()
                val characterWeight = sortedLines.sumOf { it.text.count(Char::isLetterOrDigit) }
                val confidence = if (characterWeight > 0) {
                    sortedLines.sumOf {
                        it.confidence.toDouble() * it.text.count(Char::isLetterOrDigit)
                    }.toFloat() / characterWeight
                } else {
                    sortedLines.map { it.confidence }.average().toFloat()
                }
                val rect = group.rect

                if (
                    text.isMeaningfulOcrText() &&
                    rect.width() >= minWidth &&
                    rect.height() >= minHeight &&
                    (confidence >= FINAL_MIN_CONFIDENCE || text.length >= LONG_TEXT_OVERRIDE)
                ) {
                    CameraOcrBlock(text, Rect(rect), confidence)
                } else {
                    null
                }
            }
            .sortedWith(compareBy<CameraOcrBlock> { it.rect.top }.thenBy { it.rect.left })
    }

    private fun canJoin(existing: Rect, next: Rect): Boolean {
        val gap = verticalGap(existing, next)
        val typicalHeight = max(existing.height(), next.height()).coerceAtLeast(1)
        if (gap < -typicalHeight * 0.45f || gap > typicalHeight * MAX_VERTICAL_GAP_FACTOR) {
            return false
        }

        val horizontalOverlap = overlapLength(existing.left, existing.right, next.left, next.right)
        val overlapRatio = horizontalOverlap.toFloat() /
            min(existing.width(), next.width()).coerceAtLeast(1)
        val alignedLeft = abs(existing.left - next.left) <= typicalHeight * LEFT_ALIGNMENT_FACTOR
        val alignedRight = abs(existing.right - next.right) <= typicalHeight * LEFT_ALIGNMENT_FACTOR

        return overlapRatio >= MIN_HORIZONTAL_OVERLAP || alignedLeft || alignedRight
    }

    private fun verticalGap(a: Rect, b: Rect): Int = when {
        b.top >= a.bottom -> b.top - a.bottom
        a.top >= b.bottom -> a.top - b.bottom
        else -> -min(a.bottom, b.bottom) + max(a.top, b.top)
    }

    private fun overlapLength(aStart: Int, aEnd: Int, bStart: Int, bEnd: Int): Int =
        max(0, min(aEnd, bEnd) - max(aStart, bStart))

    private fun rectIou(a: Rect, b: Rect): Float {
        val intersectionWidth = overlapLength(a.left, a.right, b.left, b.right)
        val intersectionHeight = overlapLength(a.top, a.bottom, b.top, b.bottom)
        val intersection = intersectionWidth.toLong() * intersectionHeight.toLong()
        if (intersection <= 0L) return 0f
        val union = a.width().toLong() * a.height() + b.width().toLong() * b.height() - intersection
        return if (union > 0L) intersection.toFloat() / union else 0f
    }

    private fun centerDistance(a: Rect, b: Rect): Float {
        val ax = (a.left + a.right) / 2f
        val ay = (a.top + a.bottom) / 2f
        val bx = (b.left + b.right) / 2f
        val by = (b.top + b.bottom) / 2f
        val dx = ax - bx
        val dy = ay - by
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    private fun isWeakResult(lines: List<RawOcrLine>): Boolean {
        val characters = lines.sumOf { it.text.count(Char::isLetterOrDigit) }
        val averageConfidence = lines.map { it.confidence }.average().takeUnless { it.isNaN() } ?: 0.0
        return lines.size < 3 || characters < 24 || averageConfidence < 38.0
    }

    private fun detectDominantLanguage(lines: List<RawOcrLine>): String? {
        var cyrillic = 0
        var latin = 0
        var han = 0
        lines.forEach { line ->
            line.text.forEach { char ->
                when {
                    char in '\u0400'..'\u04FF' -> cyrillic++
                    char in '\u4E00'..'\u9FFF' -> han++
                    char.isLetter() && char.code < 0x0250 -> latin++
                }
            }
        }
        val maxCount = max(cyrillic, max(latin, han))
        if (maxCount < 4) return null
        return when (maxCount) {
            cyrillic -> "rus"
            han -> "chi_sim"
            else -> "eng"
        }
    }

    private fun prepareTessData(context: Context, languageCodes: Set<String>): File {
        val dataPath = File(context.filesDir, "camera_ocr").apply { mkdirs() }
        val tessDataDir = File(dataPath, "tessdata").apply { mkdirs() }
        languageCodes.forEach { code ->
            val fileName = "$code.traineddata"
            val destination = File(tessDataDir, fileName)
            if (!destination.exists() || destination.length() == 0L) {
                context.assets.open("tessdata/$fileName").use { input ->
                    destination.outputStream().use { output -> input.copyTo(output) }
                }
            }
        }
        return dataPath
    }

    private fun Language?.toTessLanguage(): String = when (this) {
        Language.RUSSIAN -> "rus"
        Language.ENGLISH -> "eng"
        Language.CHINESE -> "chi_sim"
        null -> "rus+eng+chi_sim"
    }

    private fun Rect.toOriginalRect(scale: Float, width: Int, height: Int): Rect {
        val safeScale = scale.coerceAtLeast(0.01f)
        return Rect(
            (left / safeScale).roundToInt().coerceIn(0, width),
            (top / safeScale).roundToInt().coerceIn(0, height),
            (right / safeScale).roundToInt().coerceIn(0, width),
            (bottom / safeScale).roundToInt().coerceIn(0, height),
        )
    }

    private fun String.isMeaningfulOcrText(): Boolean =
        count(Char::isLetterOrDigit) >= MIN_ALPHANUMERIC_CHARS

    private fun normalizedText(text: String): String = text
        .lowercase()
        .filter(Char::isLetterOrDigit)

    private const val RAW_MIN_CONFIDENCE = 14f
    private const val FINAL_MIN_CONFIDENCE = 24f
    private const val MIN_ALPHANUMERIC_CHARS = 2
    private const val MIN_BOX_PIXELS = 6
    private const val LONG_TEXT_OVERRIDE = 14
    private const val DUPLICATE_IOU = 0.46f
    private const val MIN_HORIZONTAL_OVERLAP = 0.18f
    private const val MAX_VERTICAL_GAP_FACTOR = 1.25f
    private const val LEFT_ALIGNMENT_FACTOR = 1.5f
    private const val MIN_BLOCK_WIDTH_FRACTION = 0.012f
    private const val MIN_BLOCK_HEIGHT_FRACTION = 0.004f
}

internal fun loadCameraBitmap(context: Context, uriString: String): Bitmap {
    val uri = Uri.parse(uriString)
    val source = if (uri.scheme == "file") {
        ImageDecoder.createSource(File(requireNotNull(uri.path)))
    } else {
        ImageDecoder.createSource(context.contentResolver, uri)
    }
    return ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
    }
}

@Composable
internal fun CameraOcrOverlay(
    result: CameraOcrResult,
    modifier: Modifier = Modifier,
) {
    val strokeWidth = 2.dp
    Canvas(modifier = modifier) {
        if (result.imageWidth <= 0 || result.imageHeight <= 0) return@Canvas

        val scale = min(
            size.width / result.imageWidth.toFloat(),
            size.height / result.imageHeight.toFloat(),
        )
        val displayedWidth = result.imageWidth * scale
        val displayedHeight = result.imageHeight * scale
        val offsetX = (size.width - displayedWidth) / 2f
        val offsetY = (size.height - displayedHeight) / 2f

        result.blocks.forEach { block ->
            val rect = block.rect
            drawRect(
                color = Color(0xFF55D6FF),
                topLeft = Offset(
                    x = offsetX + rect.left * scale,
                    y = offsetY + rect.top * scale,
                ),
                size = Size(
                    width = rect.width() * scale,
                    height = rect.height() * scale,
                ),
                style = Stroke(width = strokeWidth.toPx()),
            )
        }
    }
}
