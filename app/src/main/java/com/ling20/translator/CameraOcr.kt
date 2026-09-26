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
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
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

private enum class OcrSource {
    TESSERACT,
    MLKIT_LATIN,
    MLKIT_CHINESE,
}

private enum class OcrScript {
    CYRILLIC,
    LATIN,
    HAN,
    OTHER,
}

private data class RawOcrLine(
    val text: String,
    val rect: Rect,
    val confidence: Float,
    val source: OcrSource,
)

internal object CameraOcrEngine {
    suspend fun recognize(
        context: Context,
        uriString: String,
        sourceLanguage: Language?,
    ): CameraOcrResult = withContext(Dispatchers.Default) {
        val original = loadCameraBitmap(context, uriString)
        // Keep the common path deliberately small. ML Kit handles Latin (and
        // Chinese in AUTO/Chinese), while Tesseract concentrates on Cyrillic and
        // mixed Russian/English text. Expensive precision passes are only used
        // when the first result is genuinely weak.
        val primaryLanguage = sourceLanguage.toTessLanguageSpec()
        val dataPath = prepareTessData(context, setOf("rus", "eng", "chi_sim"))
        val preparedImages = prepareImages(original)

        try {
            val rawLines = mutableListOf<RawOcrLine>()

            rawLines += recognizeWithMlKit(
                bitmap = original,
                sourceLanguage = sourceLanguage,
            )
            rawLines += recognizeWithLanguage(
                dataPath = dataPath,
                languageSpec = primaryLanguage,
                preparedImages = preparedImages,
                originalWidth = original.width,
                originalHeight = original.height,
                fullPassSet = false,
            )

            val firstPass = deduplicateLines(rawLines)
            if (isWeakResult(firstPass)) {
                val precisionLanguage = when (sourceLanguage) {
                    Language.RUSSIAN -> "rus"
                    Language.ENGLISH -> "eng"
                    Language.CHINESE -> "chi_sim"
                    null -> detectDominantLanguage(firstPass) ?: "rus"
                }
                rawLines += recognizeWithLanguage(
                    dataPath = dataPath,
                    languageSpec = precisionLanguage,
                    preparedImages = preparedImages,
                    originalWidth = original.width,
                    originalHeight = original.height,
                    fullPassSet = true,
                )
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

    private fun recognizeWithMlKit(
        bitmap: Bitmap,
        sourceLanguage: Language?,
    ): List<RawOcrLine> {
        val inputImage = InputImage.fromBitmap(bitmap, 0)
        val recognizers = mutableListOf<Pair<TextRecognizer, OcrSource>>()

        recognizers += TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) to
            OcrSource.MLKIT_LATIN
        if (sourceLanguage == null || sourceLanguage == Language.CHINESE) {
            recognizers += TextRecognition.getClient(
                ChineseTextRecognizerOptions.Builder().build(),
            ) to OcrSource.MLKIT_CHINESE
        }

        // Start recognizers before awaiting them. AUTO can therefore run Latin
        // and Chinese ML Kit work in parallel instead of serially.
        val tasks = recognizers.map { (recognizer, source) ->
            Triple(recognizer, source, recognizer.process(inputImage))
        }

        return try {
            buildList {
                tasks.forEach { (_, source, task) ->
                    val result = runCatching { Tasks.await(task) }.getOrNull() ?: return@forEach
                    result.textBlocks.forEach { block ->
                        block.lines.forEach { line ->
                            val rect = line.boundingBox
                            val value = line.text.trim()
                            if (
                                rect != null &&
                                value.isMeaningfulOcrText() &&
                                rect.width() >= MIN_BOX_PIXELS &&
                                rect.height() >= MIN_BOX_PIXELS
                            ) {
                                add(
                                    RawOcrLine(
                                        text = value,
                                        rect = Rect(rect),
                                        confidence = MLKIT_DEFAULT_CONFIDENCE,
                                        source = source,
                                    ),
                                )
                            }
                        }
                    }
                }
            }
        } finally {
            recognizers.forEach { (recognizer, _) -> recognizer.close() }
        }
    }

    private fun recognizeWithLanguage(
        dataPath: File,
        languageSpec: String,
        preparedImages: List<PreparedImage>,
        originalWidth: Int,
        originalHeight: Int,
        fullPassSet: Boolean = true,
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

            // Keep a colour pass because global thresholding can erase pale or
            // decorative lettering. Grayscale AUTO handles normal paragraphs,
            // while the binary sparse pass catches small high-contrast fragments.
            val passes = if (fullPassSet) {
                listOf(
                    preparedImages[0] to TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT,
                    preparedImages[1] to TessBaseAPI.PageSegMode.PSM_AUTO,
                    preparedImages[2] to TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT,
                    preparedImages[3] to TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT,
                )
            } else {
                listOf(
                    preparedImages[0] to TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT,
                    preparedImages[1] to TessBaseAPI.PageSegMode.PSM_AUTO,
                )
            }

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
            minConfidence = RAW_MIN_CONFIDENCE,
        )
        val words = collectIteratorLevel(
            tess = tess,
            level = TessBaseAPI.PageIteratorLevel.RIL_WORD,
            imageScale = imageScale,
            originalWidth = originalWidth,
            originalHeight = originalHeight,
            minConfidence = WORD_MIN_CONFIDENCE,
        )

        // Always keep word-level candidates as well. Large isolated words on
        // packaging (AQUA, SPRAY, 99%) can disappear from TEXTLINE even when
        // Tesseract has a good word box. Suppress only words already represented
        // by an equivalent text line.
        val extraWords = words.filter { word ->
            val normalizedWord = normalizedText(word.text)
            lines.none { line ->
                normalizedWord.isNotEmpty() &&
                    normalizedText(line.text).contains(normalizedWord) &&
                    rectContainmentOverlap(line.rect, word.rect) >= WORD_INSIDE_LINE_OVERLAP
            }
        }
        return lines + extraWords
    }

    private fun collectIteratorLevel(
        tess: TessBaseAPI,
        level: Int,
        imageScale: Float,
        originalWidth: Int,
        originalHeight: Int,
        minConfidence: Float,
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
                    confidence >= minConfidence
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
                            source = OcrSource.TESSERACT,
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
        val invertedBinary = makeHighContrastGrayscale(scaled, binary = true, invert = true)

        return listOf(
            PreparedImage(scaled, actualScale),
            PreparedImage(enhanced, actualScale),
            PreparedImage(binary, actualScale),
            PreparedImage(invertedBinary, actualScale),
        )
    }

    private fun makeHighContrastGrayscale(
        source: Bitmap,
        binary: Boolean,
        invert: Boolean = false,
    ): Bitmap {
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
            var value = if (binary) {
                if (stretched >= otsu) 255 else 0
            } else {
                stretched
            }
            if (invert) value = 255 - value
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
            .sortedByDescending(::sourceAwareScore)
            .forEach { candidate ->
                val duplicate = accepted.any { existing ->
                    val iou = rectIou(existing.rect, candidate.rect)
                    val containment = rectContainmentOverlap(existing.rect, candidate.rect)
                    val normalizedExisting = normalizedText(existing.text)
                    val normalizedCandidate = normalizedText(candidate.text)
                    val sameText = normalizedExisting.isNotEmpty() &&
                        normalizedExisting == normalizedCandidate
                    val sameScript = dominantScript(existing.text) == dominantScript(candidate.text)
                    val nestedAlternative = containment >= RAW_CONTAINMENT_DUPLICATE &&
                        sameScript &&
                        dominantScript(candidate.text) != OcrScript.OTHER

                    iou >= DUPLICATE_IOU ||
                        nestedAlternative ||
                        (
                            sameText &&
                                centerDistance(existing.rect, candidate.rect) <=
                                max(existing.rect.height(), candidate.rect.height()) * 1.5f
                            )
                }
                if (!duplicate) accepted += candidate
            }
        return accepted.sortedWith(compareBy<RawOcrLine> { it.rect.top }.thenBy { it.rect.left })
    }

    private fun sourceAwareScore(line: RawOcrLine): Double {
        val bonus = when (dominantScript(line.text)) {
            OcrScript.CYRILLIC -> if (line.source == OcrSource.TESSERACT) 22.0 else 0.0
            OcrScript.LATIN -> when (line.source) {
                OcrSource.MLKIT_LATIN -> 24.0
                OcrSource.TESSERACT -> 3.0
                else -> 0.0
            }
            OcrScript.HAN -> when (line.source) {
                OcrSource.MLKIT_CHINESE -> 24.0
                OcrSource.TESSERACT -> 6.0
                else -> 0.0
            }
            OcrScript.OTHER -> 0.0
        }
        val lengthBonus = min(16, line.text.count(Char::isLetterOrDigit)) * 0.35
        return line.confidence + bonus + lengthBonus
    }

    private fun dominantScript(text: String): OcrScript {
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
        val maxCount = max(cyrillic, max(latin, han))
        if (maxCount == 0) return OcrScript.OTHER
        return when (maxCount) {
            cyrillic -> OcrScript.CYRILLIC
            han -> OcrScript.HAN
            else -> OcrScript.LATIN
        }
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

        val ordered = lines.sortedWith(
            compareBy<RawOcrLine> { it.rect.top }.thenBy { it.rect.left },
        )
        val groups = mutableListOf<MutableBlock>()

        ordered.forEach { line ->
            val bestGroup = groups
                .asSequence()
                .filter { group ->
                    val lastLine = group.lines.maxByOrNull { it.rect.bottom } ?: return@filter false
                    canJoinLines(
                        previous = lastLine,
                        next = line,
                        currentBlock = group.rect,
                        currentLineCount = group.lines.size,
                        imageHeight = imageHeight,
                    )
                }
                .minByOrNull { group ->
                    val lastLine = group.lines.maxByOrNull { it.rect.bottom }!!
                    verticalGap(lastLine.rect, line.rect)
                }

            if (bestGroup == null) {
                groups += MutableBlock(mutableListOf(line), Rect(line.rect))
            } else {
                bestGroup.lines += line
                bestGroup.rect.union(line.rect)
            }
        }

        val minWidth = max(MIN_BOX_PIXELS, (imageWidth * MIN_BLOCK_WIDTH_FRACTION).roundToInt())
        val minHeight = max(MIN_BOX_PIXELS, (imageHeight * MIN_BLOCK_HEIGHT_FRACTION).roundToInt())
        val tinyWidth = (imageWidth * TINY_BLOCK_WIDTH_FRACTION).roundToInt()
        val tinyHeight = (imageHeight * TINY_BLOCK_HEIGHT_FRACTION).roundToInt()

        val candidates = groups
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
                val alphanumeric = text.count(Char::isLetterOrDigit)
                val looksLikeTinyNoise =
                    rect.width() < tinyWidth &&
                    rect.height() < tinyHeight &&
                    alphanumeric < TINY_BLOCK_MIN_CHARS

                if (
                    !looksLikeTinyNoise &&
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

        return postProcessBlocks(
            blocks = candidates,
            imageWidth = imageWidth,
            imageHeight = imageHeight,
        )
    }

    private fun postProcessBlocks(
        blocks: List<CameraOcrBlock>,
        imageWidth: Int,
        imageHeight: Int,
    ): List<CameraOcrBlock> {
        val filtered = blocks.filterNot { block ->
            looksLikeBarcode(block.text) ||
                looksLikeOversizedNoise(block, imageWidth, imageHeight)
        }
        val withoutOverlaps = removeOverlappingBlocks(filtered, imageWidth, imageHeight)
        return mergeParagraphBlocks(withoutOverlaps, imageHeight)
            .filterNot { block ->
                looksLikeBarcode(block.text) ||
                    looksLikeOversizedNoise(block, imageWidth, imageHeight)
            }
            .sortedWith(compareBy<CameraOcrBlock> { it.rect.top }.thenBy { it.rect.left })
    }

    private fun looksLikeBarcode(text: String): Boolean {
        val digits = text.count(Char::isDigit)
        val letters = text.count(Char::isLetter)
        val alphanumeric = digits + letters
        if (alphanumeric == 0) return true

        // Long almost-numeric strings are normally barcode digits / serial noise.
        return digits >= BARCODE_MIN_DIGITS &&
            letters <= BARCODE_MAX_LETTERS &&
            digits.toFloat() / alphanumeric >= BARCODE_DIGIT_RATIO
    }

    private fun looksLikeOversizedNoise(
        block: CameraOcrBlock,
        imageWidth: Int,
        imageHeight: Int,
    ): Boolean {
        if (imageWidth <= 0 || imageHeight <= 0) return false
        val alphanumeric = block.text.count(Char::isLetterOrDigit)
        val widthFraction = block.rect.width().toFloat() / imageWidth
        val heightFraction = block.rect.height().toFloat() / imageHeight
        val areaFraction = block.rect.width().toDouble() * block.rect.height().toDouble() /
            (imageWidth.toDouble() * imageHeight.toDouble())

        // This rejects the large empty frame seen above the product in AUTO mode,
        // while keeping real paragraphs that contain enough recognized characters.
        if (areaFraction >= HUGE_BLOCK_AREA_FRACTION && alphanumeric < HUGE_BLOCK_MIN_CHARS) {
            return true
        }
        return widthFraction >= WIDE_NOISE_WIDTH_FRACTION &&
            heightFraction >= WIDE_NOISE_HEIGHT_FRACTION &&
            alphanumeric < WIDE_NOISE_MIN_CHARS
    }

    private fun removeOverlappingBlocks(
        blocks: List<CameraOcrBlock>,
        imageWidth: Int,
        imageHeight: Int,
    ): List<CameraOcrBlock> {
        val accepted = mutableListOf<CameraOcrBlock>()
        blocks
            .sortedByDescending { blockScore(it, imageWidth, imageHeight) }
            .forEach { candidate ->
                val conflict = accepted.any { existing ->
                    val containment = rectContainmentOverlap(existing.rect, candidate.rect)
                    val iou = rectIou(existing.rect, candidate.rect)
                    iou >= BLOCK_DUPLICATE_IOU ||
                        containment >= BLOCK_CONTAINMENT_OVERLAP
                }
                if (!conflict) accepted += candidate
            }
        return accepted.sortedWith(compareBy<CameraOcrBlock> { it.rect.top }.thenBy { it.rect.left })
    }

    private fun blockScore(
        block: CameraOcrBlock,
        imageWidth: Int,
        imageHeight: Int,
    ): Double {
        val characters = block.text.count(Char::isLetterOrDigit).coerceAtMost(60)
        val imageArea = imageWidth.toDouble() * imageHeight.toDouble()
        val areaFraction = if (imageArea > 0.0) {
            block.rect.width().toDouble() * block.rect.height().toDouble() / imageArea
        } else {
            0.0
        }
        return block.confidence + characters * BLOCK_CHARACTER_SCORE - areaFraction * BLOCK_AREA_PENALTY
    }

    private fun rectContainmentOverlap(a: Rect, b: Rect): Float {
        val intersectionWidth = overlapLength(a.left, a.right, b.left, b.right)
        val intersectionHeight = overlapLength(a.top, a.bottom, b.top, b.bottom)
        val intersection = intersectionWidth.toLong() * intersectionHeight.toLong()
        if (intersection <= 0L) return 0f
        val smallerArea = min(
            a.width().toLong() * a.height().toLong(),
            b.width().toLong() * b.height().toLong(),
        )
        return if (smallerArea > 0L) intersection.toFloat() / smallerArea else 0f
    }

    private fun mergeParagraphBlocks(
        blocks: List<CameraOcrBlock>,
        imageHeight: Int,
    ): List<CameraOcrBlock> {
        if (blocks.size < 2) return blocks
        val result = mutableListOf<CameraOcrBlock>()

        blocks
            .sortedWith(compareBy<CameraOcrBlock> { it.rect.top }.thenBy { it.rect.left })
            .forEach { block ->
                val previous = result.lastOrNull()
                if (previous != null && canMergeParagraphBlocks(previous, block, imageHeight)) {
                    val mergedRect = Rect(previous.rect).apply { union(block.rect) }
                    val previousChars = previous.text.count(Char::isLetterOrDigit).coerceAtLeast(1)
                    val blockChars = block.text.count(Char::isLetterOrDigit).coerceAtLeast(1)
                    val mergedConfidence =
                        (previous.confidence * previousChars + block.confidence * blockChars) /
                            (previousChars + blockChars)
                    result[result.lastIndex] = CameraOcrBlock(
                        text = previous.text.trimEnd() + "\n" + block.text.trimStart(),
                        rect = mergedRect,
                        confidence = mergedConfidence,
                    )
                } else {
                    result += block
                }
            }
        return result
    }

    private fun canMergeParagraphBlocks(
        previous: CameraOcrBlock,
        next: CameraOcrBlock,
        imageHeight: Int,
    ): Boolean {
        val previousLines = previous.text.count { it == '\n' } + 1
        val nextLines = next.text.count { it == '\n' } + 1
        if (previousLines + nextLines > MAX_PARAGRAPH_LINES) return false

        val previousHeightPerLine = previous.rect.height().toFloat() / previousLines.coerceAtLeast(1)
        val nextHeightPerLine = next.rect.height().toFloat() / nextLines.coerceAtLeast(1)
        val heightRatio = max(previousHeightPerLine, nextHeightPerLine) /
            min(previousHeightPerLine, nextHeightPerLine).coerceAtLeast(1f)
        if (heightRatio > PARAGRAPH_MAX_HEIGHT_RATIO) return false

        val gap = next.rect.top - previous.rect.bottom
        if (gap < 0 || gap > max(previousHeightPerLine, nextHeightPerLine) * PARAGRAPH_MAX_GAP_FACTOR) {
            return false
        }

        val combinedHeight = next.rect.bottom - previous.rect.top
        if (combinedHeight > imageHeight * PARAGRAPH_MAX_HEIGHT_FRACTION) return false

        val overlap = overlapLength(previous.rect.left, previous.rect.right, next.rect.left, next.rect.right)
        val overlapRatio = overlap.toFloat() /
            min(previous.rect.width(), next.rect.width()).coerceAtLeast(1)
        val leftAligned = abs(previous.rect.left - next.rect.left) <=
            max(previousHeightPerLine, nextHeightPerLine) * PARAGRAPH_LEFT_ALIGNMENT_FACTOR

        return overlapRatio >= PARAGRAPH_MIN_HORIZONTAL_OVERLAP || leftAligned
    }

    private fun canJoinLines(
        previous: RawOcrLine,
        next: RawOcrLine,
        currentBlock: Rect,
        currentLineCount: Int,
        imageHeight: Int,
    ): Boolean {
        if (currentLineCount >= MAX_LINES_PER_BLOCK) return false

        val previousHeight = previous.rect.height().coerceAtLeast(1)
        val nextHeight = next.rect.height().coerceAtLeast(1)
        val minHeight = min(previousHeight, nextHeight).toFloat()
        val maxHeight = max(previousHeight, nextHeight).toFloat()
        val heightRatio = maxHeight / minHeight

        // Large headings, percentages and normal paragraph text should not be
        // swallowed into one giant overlay block.
        if (heightRatio > MAX_LINE_HEIGHT_RATIO) return false

        val gap = verticalGap(previous.rect, next.rect)
        val typicalHeight = max(previousHeight, nextHeight)
        if (gap < -typicalHeight * MAX_LINE_OVERLAP_FACTOR) return false
        if (gap > typicalHeight * MAX_VERTICAL_GAP_FACTOR) return false

        val prospectiveTop = min(currentBlock.top, next.rect.top)
        val prospectiveBottom = max(currentBlock.bottom, next.rect.bottom)
        if (prospectiveBottom - prospectiveTop > imageHeight * MAX_BLOCK_HEIGHT_FRACTION) {
            return false
        }

        val horizontalOverlap = overlapLength(
            previous.rect.left,
            previous.rect.right,
            next.rect.left,
            next.rect.right,
        )
        val overlapRatio = horizontalOverlap.toFloat() /
            min(previous.rect.width(), next.rect.width()).coerceAtLeast(1)
        val alignedLeft = abs(previous.rect.left - next.rect.left) <= typicalHeight * LEFT_ALIGNMENT_FACTOR
        val alignedRight = abs(previous.rect.right - next.rect.right) <= typicalHeight * LEFT_ALIGNMENT_FACTOR
        val centersClose = abs(
            (previous.rect.left + previous.rect.right) / 2f -
                (next.rect.left + next.rect.right) / 2f,
        ) <= max(previous.rect.width(), next.rect.width()) * CENTER_ALIGNMENT_FACTOR

        return overlapRatio >= MIN_HORIZONTAL_OVERLAP ||
            alignedLeft ||
            alignedRight ||
            centersClose
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
        // New directory forces existing installations to replace the previous
        // tessdata_fast files with the more accurate tessdata_best models.
        val dataPath = File(context.filesDir, "camera_ocr_best_v1").apply { mkdirs() }
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

    private fun Language?.toTessLanguageSpec(): String = when (this) {
        Language.RUSSIAN -> "rus+eng"
        Language.ENGLISH -> "eng+rus"
        Language.CHINESE -> "chi_sim+eng"
        null -> "rus+eng"
    }

    private fun Language.fallbackTessLanguages(): List<String> = when (this) {
        Language.RUSSIAN -> listOf("rus", "eng")
        Language.ENGLISH -> listOf("eng", "rus")
        Language.CHINESE -> listOf("chi_sim", "eng")
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

    private const val MLKIT_DEFAULT_CONFIDENCE = 82f
    private const val RAW_MIN_CONFIDENCE = 10f
    private const val WORD_MIN_CONFIDENCE = 24f
    private const val WORD_INSIDE_LINE_OVERLAP = 0.84f
    private const val FINAL_MIN_CONFIDENCE = 20f
    private const val MIN_ALPHANUMERIC_CHARS = 2
    private const val MIN_BOX_PIXELS = 6
    private const val LONG_TEXT_OVERRIDE = 14
    private const val DUPLICATE_IOU = 0.42f
    private const val RAW_CONTAINMENT_DUPLICATE = 0.82f
    private const val MIN_HORIZONTAL_OVERLAP = 0.18f
    private const val MAX_VERTICAL_GAP_FACTOR = 0.72f
    private const val LEFT_ALIGNMENT_FACTOR = 0.85f
    private const val CENTER_ALIGNMENT_FACTOR = 0.22f
    private const val MAX_LINE_HEIGHT_RATIO = 1.55f
    private const val MAX_LINE_OVERLAP_FACTOR = 0.35f
    private const val MAX_BLOCK_HEIGHT_FRACTION = 0.16f
    private const val MAX_LINES_PER_BLOCK = 5
    private const val TINY_BLOCK_WIDTH_FRACTION = 0.05f
    private const val TINY_BLOCK_HEIGHT_FRACTION = 0.016f
    private const val TINY_BLOCK_MIN_CHARS = 5
    private const val MIN_BLOCK_WIDTH_FRACTION = 0.012f
    private const val MIN_BLOCK_HEIGHT_FRACTION = 0.004f

    private const val BARCODE_MIN_DIGITS = 6
    private const val BARCODE_MAX_LETTERS = 3
    private const val BARCODE_DIGIT_RATIO = 0.62f
    private const val HUGE_BLOCK_AREA_FRACTION = 0.10
    private const val HUGE_BLOCK_MIN_CHARS = 18
    private const val WIDE_NOISE_WIDTH_FRACTION = 0.82f
    private const val WIDE_NOISE_HEIGHT_FRACTION = 0.07f
    private const val WIDE_NOISE_MIN_CHARS = 16
    private const val BLOCK_DUPLICATE_IOU = 0.52f
    private const val BLOCK_CONTAINMENT_OVERLAP = 0.88f
    private const val BLOCK_CHARACTER_SCORE = 0.75
    private const val BLOCK_AREA_PENALTY = 70.0
    private const val MAX_PARAGRAPH_LINES = 6
    private const val PARAGRAPH_MAX_HEIGHT_RATIO = 1.32f
    private const val PARAGRAPH_MAX_GAP_FACTOR = 0.70f
    private const val PARAGRAPH_MAX_HEIGHT_FRACTION = 0.20f
    private const val PARAGRAPH_LEFT_ALIGNMENT_FACTOR = 1.4f
    private const val PARAGRAPH_MIN_HORIZONTAL_OVERLAP = 0.55f
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
