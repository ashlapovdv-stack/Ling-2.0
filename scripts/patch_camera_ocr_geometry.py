from pathlib import Path

ocr_path = Path('app/src/main/java/com/ling20/translator/CameraOcr.kt')
screen_path = Path('app/src/main/java/com/ling20/translator/CameraModeScreen.kt')
gradle_path = Path('app/build.gradle.kts')

ocr = ocr_path.read_text()
screen = screen_path.read_text()
gradle = gradle_path.read_text()


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise SystemExit(f'Pattern not found: {label}')
    return text.replace(old, new, 1)

ocr = replace_once(
    ocr,
    '''data class CameraOcrBlock(
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
''',
    '''data class CameraOcrBlock(
    val text: String,
    val rect: Rect,
    val confidence: Float,
)

data class CameraOcrWord(
    val text: String,
    val rect: Rect,
    val confidence: Float,
)

data class CameraOcrLine(
    val text: String,
    val rect: Rect,
    val confidence: Float,
    val words: List<CameraOcrWord>,
)

data class CameraOcrResult(
    val imageWidth: Int,
    val imageHeight: Int,
    val fullText: String,
    val blocks: List<CameraOcrBlock>,
    val lines: List<CameraOcrLine>,
    val words: List<CameraOcrWord>,
    val languageSpec: String,
)
''',
    'public OCR hierarchy',
)

ocr = replace_once(
    ocr,
    '''private enum class OcrScript {
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
''',
    '''private enum class OcrScript {
    CYRILLIC,
    LATIN,
    HAN,
    OTHER,
}

private enum class OcrLevel {
    LINE,
    WORD,
}

private data class RawOcrLine(
    val text: String,
    val rect: Rect,
    val confidence: Float,
    val source: OcrSource,
    val level: OcrLevel,
)
''',
    'raw OCR level',
)

ocr = replace_once(
    ocr,
    '''            val deduplicated = deduplicateLines(rawLines)
            val blocks = groupLinesIntoBlocks(
                lines = deduplicated,
                imageWidth = original.width,
                imageHeight = original.height,
            )

            CameraOcrResult(
                imageWidth = original.width,
                imageHeight = original.height,
                fullText = blocks.joinToString("\\n") { it.text }.trim(),
                blocks = blocks,
                languageSpec = primaryLanguage,
            )
''',
    '''            val deduplicated = deduplicateLines(rawLines)
            val wordCandidates = deduplicated.filter { it.level == OcrLevel.WORD }
            val baseLineCandidates = deduplicated.filter { it.level == OcrLevel.LINE }
            val lineCandidates = addOrphanWordsAsLines(baseLineCandidates, wordCandidates)
            val words = wordCandidates
                .sortedWith(compareBy<RawOcrLine> { it.rect.top }.thenBy { it.rect.left })
                .map { CameraOcrWord(it.text, Rect(it.rect), it.confidence) }
            val lines = buildStructuredLines(lineCandidates, words)
            val blocks = groupLinesIntoBlocks(
                lines = lineCandidates,
                imageWidth = original.width,
                imageHeight = original.height,
            )

            CameraOcrResult(
                imageWidth = original.width,
                imageHeight = original.height,
                fullText = lines.joinToString("\\n") { it.text }.trim(),
                blocks = blocks,
                lines = lines,
                words = words,
                languageSpec = primaryLanguage,
            )
''',
    'structured OCR result',
)

ocr = replace_once(
    ocr,
    '''                                    RawOcrLine(
                                        text = value,
                                        rect = Rect(rect),
                                        confidence = MLKIT_DEFAULT_CONFIDENCE,
                                        source = source,
                                    ),
                                )
                            }
''',
    '''                                    RawOcrLine(
                                        text = value,
                                        rect = Rect(rect),
                                        confidence = MLKIT_DEFAULT_CONFIDENCE,
                                        source = source,
                                        level = OcrLevel.LINE,
                                    ),
                                )
                            }

                            line.elements.forEach { element ->
                                val wordRect = element.boundingBox
                                val wordText = element.text.trim()
                                if (
                                    wordRect != null &&
                                    wordText.isMeaningfulOcrText() &&
                                    wordRect.width() >= MIN_BOX_PIXELS &&
                                    wordRect.height() >= MIN_BOX_PIXELS
                                ) {
                                    add(
                                        RawOcrLine(
                                            text = wordText,
                                            rect = Rect(wordRect),
                                            confidence = MLKIT_DEFAULT_CONFIDENCE,
                                            source = source,
                                            level = OcrLevel.WORD,
                                        ),
                                    )
                                }
                            }
''',
    'ML Kit lines and words',
)

ocr = replace_once(
    ocr,
    '''        val lines = collectIteratorLevel(
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
''',
    '''        val lines = collectIteratorLevel(
            tess = tess,
            level = TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE,
            ocrLevel = OcrLevel.LINE,
            imageScale = imageScale,
            originalWidth = originalWidth,
            originalHeight = originalHeight,
            minConfidence = RAW_MIN_CONFIDENCE,
        )
        val words = collectIteratorLevel(
            tess = tess,
            level = TessBaseAPI.PageIteratorLevel.RIL_WORD,
            ocrLevel = OcrLevel.WORD,
            imageScale = imageScale,
            originalWidth = originalWidth,
            originalHeight = originalHeight,
            minConfidence = WORD_MIN_CONFIDENCE,
        )

        // Keep hierarchy intact. Lines are used to build translation blocks,
        // while words retain their own coordinates for precise future overlays.
        return lines + words
''',
    'Tesseract hierarchy',
)

ocr = replace_once(
    ocr,
    '''    private fun collectIteratorLevel(
        tess: TessBaseAPI,
        level: Int,
        imageScale: Float,
''',
    '''    private fun collectIteratorLevel(
        tess: TessBaseAPI,
        level: Int,
        ocrLevel: OcrLevel,
        imageScale: Float,
''',
    'iterator level parameter',
)

ocr = replace_once(
    ocr,
    '''                            confidence = confidence,
                            source = OcrSource.TESSERACT,
                        )
''',
    '''                            confidence = confidence,
                            source = OcrSource.TESSERACT,
                            level = ocrLevel,
                        )
''',
    'Tesseract raw level',
)

ocr = replace_once(
    ocr,
    '''                val duplicate = accepted.any { existing ->
                    val iou = rectIou(existing.rect, candidate.rect)
''',
    '''                val duplicate = accepted.any { existing ->
                    if (existing.level != candidate.level) return@any false
                    val iou = rectIou(existing.rect, candidate.rect)
''',
    'dedupe by level',
)

insert_marker = '''    private fun groupLinesIntoBlocks(
'''
if insert_marker not in ocr:
    raise SystemExit('Pattern not found: hierarchy helpers marker')
helpers = '''    private fun addOrphanWordsAsLines(
        lines: List<RawOcrLine>,
        words: List<RawOcrLine>,
    ): List<RawOcrLine> {
        val synthetic = words.filter { word ->
            lines.none { line -> wordBelongsToLine(line.rect, word.rect) }
        }.map { word ->
            word.copy(level = OcrLevel.LINE)
        }
        return deduplicateLines(lines + synthetic)
            .filter { it.level == OcrLevel.LINE }
            .sortedWith(compareBy<RawOcrLine> { it.rect.top }.thenBy { it.rect.left })
    }

    private fun buildStructuredLines(
        lines: List<RawOcrLine>,
        words: List<CameraOcrWord>,
    ): List<CameraOcrLine> = lines
        .sortedWith(compareBy<RawOcrLine> { it.rect.top }.thenBy { it.rect.left })
        .map { line ->
            val lineWords = words
                .filter { word -> wordBelongsToLine(line.rect, word.rect) }
                .sortedBy { it.rect.left }
            CameraOcrLine(
                text = line.text,
                rect = Rect(line.rect),
                confidence = line.confidence,
                words = lineWords,
            )
        }

    private fun wordBelongsToLine(line: Rect, word: Rect): Boolean {
        val containment = rectContainmentOverlap(line, word)
        if (containment >= WORD_TO_LINE_CONTAINMENT) return true

        val verticalOverlap = overlapLength(line.top, line.bottom, word.top, word.bottom).toFloat() /
            min(line.height(), word.height()).coerceAtLeast(1)
        if (verticalOverlap < WORD_TO_LINE_VERTICAL_OVERLAP) return false

        val wordCenterX = (word.left + word.right) / 2f
        val margin = line.height().coerceAtLeast(1) * WORD_TO_LINE_HORIZONTAL_MARGIN
        return wordCenterX >= line.left - margin && wordCenterX <= line.right + margin
    }

'''
ocr = ocr.replace(insert_marker, helpers + insert_marker, 1)

ocr = replace_once(
    ocr,
    '''    private fun isWeakResult(lines: List<RawOcrLine>): Boolean {
        val characters = lines.sumOf { it.text.count(Char::isLetterOrDigit) }
        val averageConfidence = lines.map { it.confidence }.average().takeUnless { it.isNaN() } ?: 0.0
        return lines.size < 3 || characters < 24 || averageConfidence < 38.0
    }
''',
    '''    private fun isWeakResult(lines: List<RawOcrLine>): Boolean {
        val lineCandidates = lines.filter { it.level == OcrLevel.LINE }.ifEmpty { lines }
        val characters = lineCandidates.sumOf { it.text.count(Char::isLetterOrDigit) }
        val averageConfidence = lineCandidates.map { it.confidence }.average()
            .takeUnless { it.isNaN() } ?: 0.0
        return lineCandidates.size < 3 || characters < 24 || averageConfidence < 38.0
    }
''',
    'weak result uses lines',
)

ocr = replace_once(
    ocr,
    '''    private const val WORD_INSIDE_LINE_OVERLAP = 0.84f
''',
    '''    private const val WORD_INSIDE_LINE_OVERLAP = 0.84f
    private const val WORD_TO_LINE_CONTAINMENT = 0.58f
    private const val WORD_TO_LINE_VERTICAL_OVERLAP = 0.55f
    private const val WORD_TO_LINE_HORIZONTAL_MARGIN = 0.65f
''',
    'word to line constants',
)

marker = '''@Composable
internal fun CameraOcrOverlay(
'''
idx = ocr.find(marker)
if idx < 0:
    raise SystemExit('Pattern not found: overlay marker')
ocr = ocr[:idx] + '''@Composable
internal fun CameraOcrOverlay(
    result: CameraOcrResult,
    modifier: Modifier = Modifier,
) {
    val lineStrokeWidth = 2.dp
    val wordStrokeWidth = 1.dp
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

        fun drawOcrRect(rect: Rect, color: Color, stroke: Float) {
            drawRect(
                color = color,
                topLeft = Offset(
                    x = offsetX + rect.left * scale,
                    y = offsetY + rect.top * scale,
                ),
                size = Size(
                    width = rect.width() * scale,
                    height = rect.height() * scale,
                ),
                style = Stroke(width = stroke),
            )
        }

        // Thin amber boxes show exact word coordinates. Cyan boxes show complete
        // text lines. Translation blocks are kept internally but are no longer
        // used for geometry debugging because they intentionally span paragraphs.
        result.words.forEach { word ->
            drawOcrRect(
                rect = word.rect,
                color = Color(0xB8FFE082),
                stroke = wordStrokeWidth.toPx(),
            )
        }
        result.lines.forEach { line ->
            drawOcrRect(
                rect = line.rect,
                color = Color(0xFF55D6FF),
                stroke = lineStrokeWidth.toPx(),
            )
        }
    }
}
'''

screen = replace_once(
    screen,
    '''                if (result.blocks.isEmpty()) {
                    ocrError = "Текстовые блоки не найдены. Попробуйте приблизить текст или улучшить освещение."
                }
''',
    '''                if (result.lines.isEmpty() && result.words.isEmpty()) {
                    ocrError = "Строки и слова не найдены. Попробуйте приблизить текст или улучшить освещение."
                }
''',
    'camera OCR empty result',
)

screen = replace_once(
    screen,
    '''        result != null -> "OCR: найдено ${result.blocks.size} текстовых блоков"
''',
    '''        result != null -> "OCR: ${result.lines.size} строк • ${result.words.size} слов"
''',
    'camera OCR status',
)

gradle = replace_once(gradle, 'versionCode = 23', 'versionCode = 24', 'version code')
gradle = replace_once(gradle, 'versionName = "0.1.15.7"', 'versionName = "0.1.15.8"', 'version name')

ocr_path.write_text(ocr)
screen_path.write_text(screen)
gradle_path.write_text(gradle)
