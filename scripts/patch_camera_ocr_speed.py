from pathlib import Path

ocr_path = Path('app/src/main/java/com/ling20/translator/CameraOcr.kt')
gradle_path = Path('app/build.gradle.kts')
text = ocr_path.read_text()

old = '''private data class RawOcrLine(
    val text: String,
    val rect: Rect,
    val confidence: Float,
)
'''
new = '''private enum class OcrSource {
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
'''
assert old in text
text = text.replace(old, new, 1)

start = text.index('        // OCR is deliberately multilingual even when the user selects a source')
end = text.index('            val deduplicated = deduplicateLines(rawLines)', start)
old_block = text[start:end]
new_block = '''        // Keep the common path deliberately small. ML Kit handles Latin (and
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

'''
text = text[:start] + new_block + text[end:]

fn_start = text.index('    private fun recognizeWithMlKit(')
fn_end = text.index('    private fun recognizeWithLanguage(', fn_start)
new_mlkit = '''    private fun recognizeWithMlKit(
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

'''
text = text[:fn_start] + new_mlkit + text[fn_end:]

old = '''                        result += RawOcrLine(
                            text = text,
                            rect = rect,
                            confidence = confidence,
                        )
'''
new = '''                        result += RawOcrLine(
                            text = text,
                            rect = rect,
                            confidence = confidence,
                            source = OcrSource.TESSERACT,
                        )
'''
assert old in text
text = text.replace(old, new, 1)

fn_start = text.index('    private fun deduplicateLines(')
fn_end = text.index('    private fun groupLinesIntoBlocks(', fn_start)
new_dedupe = '''    private fun deduplicateLines(lines: List<RawOcrLine>): List<RawOcrLine> {
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
                char in '\\u0400'..'\\u04FF' -> cyrillic++
                char in '\\u4E00'..'\\u9FFF' -> han++
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

'''
text = text[:fn_start] + new_dedupe + text[fn_end:]

text = text.replace('''        Language.CHINESE -> "chi_sim+eng"
        null -> "rus+eng+chi_sim"
''', '''        Language.CHINESE -> "chi_sim+eng"
        null -> "rus+eng"
''', 1)

text = text.replace('private const val MLKIT_DEFAULT_CONFIDENCE = 68f', 'private const val MLKIT_DEFAULT_CONFIDENCE = 82f', 1)
text = text.replace('private const val DUPLICATE_IOU = 0.46f', 'private const val DUPLICATE_IOU = 0.42f\n    private const val RAW_CONTAINMENT_DUPLICATE = 0.82f', 1)
text = text.replace('private const val MAX_LINE_HEIGHT_RATIO = 1.75f', 'private const val MAX_LINE_HEIGHT_RATIO = 1.55f', 1)
text = text.replace('private const val TINY_BLOCK_WIDTH_FRACTION = 0.035f', 'private const val TINY_BLOCK_WIDTH_FRACTION = 0.05f', 1)
text = text.replace('private const val TINY_BLOCK_HEIGHT_FRACTION = 0.012f', 'private const val TINY_BLOCK_HEIGHT_FRACTION = 0.016f', 1)
text = text.replace('private const val TINY_BLOCK_MIN_CHARS = 4', 'private const val TINY_BLOCK_MIN_CHARS = 5', 1)
text = text.replace('private const val BARCODE_MIN_DIGITS = 8', 'private const val BARCODE_MIN_DIGITS = 6', 1)
text = text.replace('private const val BARCODE_MAX_LETTERS = 1', 'private const val BARCODE_MAX_LETTERS = 3', 1)
text = text.replace('private const val BARCODE_DIGIT_RATIO = 0.78f', 'private const val BARCODE_DIGIT_RATIO = 0.62f', 1)
text = text.replace('private const val PARAGRAPH_MAX_HEIGHT_RATIO = 1.45f', 'private const val PARAGRAPH_MAX_HEIGHT_RATIO = 1.32f', 1)

ocr_path.write_text(text)

gradle = gradle_path.read_text()
assert 'versionCode = 22' in gradle and 'versionName = "0.1.15.6"' in gradle
gradle = gradle.replace('versionCode = 22', 'versionCode = 23', 1)
gradle = gradle.replace('versionName = "0.1.15.6"', 'versionName = "0.1.15.7"', 1)
gradle_path.write_text(gradle)
