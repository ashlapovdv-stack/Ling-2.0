from pathlib import Path

ocr_path = Path('app/src/main/java/com/ling20/translator/CameraOcr.kt')
gradle_path = Path('app/build.gradle.kts')

ocr = ocr_path.read_text()
gradle = gradle_path.read_text()


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise SystemExit(f'Pattern not found: {label}')
    return text.replace(old, new, 1)

ocr = replace_once(
    ocr,
    '''            val deduplicated = deduplicateLines(rawLines)
            val wordCandidates = deduplicated.filter { it.level == OcrLevel.WORD }
            val baseLineCandidates = deduplicated.filter { it.level == OcrLevel.LINE }
            val lineCandidates = addOrphanWordsAsLines(baseLineCandidates, wordCandidates)
            val words = wordCandidates
                .sortedWith(compareBy<RawOcrLine> { it.rect.top }.thenBy { it.rect.left })
                .map { CameraOcrWord(it.text, Rect(it.rect), it.confidence) }
''',
    '''            val deduplicated = deduplicateLines(rawLines)
            val wordCandidates = deduplicated
                .filter { it.level == OcrLevel.WORD }
                .filterNot { looksLikeBarcode(it.text) }
                .filterNot { looksLikeWordNoise(it) }
            val baseLineCandidates = deduplicated
                .filter { it.level == OcrLevel.LINE }
                .filterNot { looksLikeBarcode(it.text) }
            val lineCandidates = rebuildLinesFromWords(
                words = wordCandidates,
                fallbackLines = baseLineCandidates,
                imageWidth = original.width,
            )
            val words = wordCandidates
                .sortedWith(compareBy<RawOcrLine> { it.rect.top }.thenBy { it.rect.left })
                .map { CameraOcrWord(it.text, Rect(it.rect), it.confidence) }
''',
    'recognize uses rebuilt lines',
)

start = ocr.find('    private fun addOrphanWordsAsLines(')
end = ocr.find('    private fun groupLinesIntoBlocks(', start)
if start < 0 or end < 0:
    raise SystemExit('Could not find old line hierarchy helpers')

helpers = r'''    private data class MutableWordRow(
        val words: MutableList<RawOcrLine>,
        var rect: Rect,
    )

    private fun rebuildLinesFromWords(
        words: List<RawOcrLine>,
        fallbackLines: List<RawOcrLine>,
        imageWidth: Int,
    ): List<RawOcrLine> {
        if (words.isEmpty()) return deduplicatePhysicalLines(fallbackLines)

        val rows = mutableListOf<MutableWordRow>()
        words
            .sortedWith(
                compareBy<RawOcrLine> { (it.rect.top + it.rect.bottom) / 2 }
                    .thenBy { it.rect.left },
            )
            .forEach { word ->
                val row = rows
                    .asSequence()
                    .filter { candidate -> canJoinWordRow(candidate, word, imageWidth) }
                    .minByOrNull { candidate ->
                        abs(rectCenterY(candidate.rect) - rectCenterY(word.rect))
                    }

                if (row == null) {
                    rows += MutableWordRow(mutableListOf(word), Rect(word.rect))
                } else {
                    row.words += word
                    row.rect.union(word.rect)
                }
            }

        val rebuilt = rows.mapNotNull { row ->
            val orderedWords = row.words
                .sortedBy { it.rect.left }
                .filterNot { looksLikeBarcode(it.text) }
            if (orderedWords.isEmpty()) return@mapNotNull null

            val text = joinWordTexts(orderedWords)
            if (!text.isMeaningfulOcrText() || looksLikeBarcode(text)) return@mapNotNull null

            val rect = Rect(orderedWords.first().rect)
            orderedWords.drop(1).forEach { rect.union(it.rect) }
            val charWeight = orderedWords.sumOf { it.text.count(Char::isLetterOrDigit) }
                .coerceAtLeast(1)
            val confidence = orderedWords.sumOf {
                it.confidence * it.text.count(Char::isLetterOrDigit).coerceAtLeast(1)
            } / charWeight
            val bestSource = orderedWords.maxByOrNull(::sourceAwareScore)?.source
                ?: OcrSource.TESSERACT

            RawOcrLine(
                text = text,
                rect = rect,
                confidence = confidence,
                source = bestSource,
                level = OcrLevel.LINE,
            )
        }

        // Raw OCR line boxes are now only a safety net. Keep one only when no
        // word-derived line already represents the same physical text row.
        val fallbacks = fallbackLines.filter { fallback ->
            !looksLikeBarcode(fallback.text) && rebuilt.none { rebuiltLine ->
                samePhysicalLine(rebuiltLine.rect, fallback.rect)
            }
        }

        return deduplicatePhysicalLines(rebuilt + fallbacks)
            .sortedWith(compareBy<RawOcrLine> { it.rect.top }.thenBy { it.rect.left })
    }

    private fun canJoinWordRow(
        row: MutableWordRow,
        word: RawOcrLine,
        imageWidth: Int,
    ): Boolean {
        val rowHeight = row.rect.height().coerceAtLeast(1)
        val wordHeight = word.rect.height().coerceAtLeast(1)
        val heightRatio = max(rowHeight, wordHeight).toFloat() /
            min(rowHeight, wordHeight).coerceAtLeast(1)
        if (heightRatio > WORD_ROW_MAX_HEIGHT_RATIO) return false

        val verticalOverlap = overlapLength(
            row.rect.top,
            row.rect.bottom,
            word.rect.top,
            word.rect.bottom,
        ).toFloat() / min(rowHeight, wordHeight).coerceAtLeast(1)
        val centerDelta = abs(rectCenterY(row.rect) - rectCenterY(word.rect))
        val sameBaseline = centerDelta <= max(rowHeight, wordHeight) * WORD_ROW_CENTER_FACTOR
        if (verticalOverlap < WORD_ROW_MIN_VERTICAL_OVERLAP && !sameBaseline) return false

        val gap = horizontalGap(row.rect, word.rect)
        val maxGap = max(rowHeight, wordHeight) * WORD_ROW_MAX_GAP_FACTOR
        if (gap > maxGap) return false

        val prospectiveWidth = max(row.rect.right, word.rect.right) - min(row.rect.left, word.rect.left)
        if (prospectiveWidth > imageWidth * WORD_ROW_MAX_WIDTH_FRACTION) return false

        return true
    }

    private fun deduplicatePhysicalLines(lines: List<RawOcrLine>): List<RawOcrLine> {
        val accepted = mutableListOf<RawOcrLine>()
        lines.sortedByDescending(::sourceAwareScore).forEach { candidate ->
            val duplicateIndex = accepted.indexOfFirst { existing ->
                samePhysicalLine(existing.rect, candidate.rect)
            }
            if (duplicateIndex < 0) {
                accepted += candidate
            } else {
                val existing = accepted[duplicateIndex]
                val better = chooseBetterPhysicalLine(existing, candidate)
                accepted[duplicateIndex] = better
            }
        }
        return accepted
    }

    private fun chooseBetterPhysicalLine(a: RawOcrLine, b: RawOcrLine): RawOcrLine {
        val aChars = a.text.count(Char::isLetterOrDigit)
        val bChars = b.text.count(Char::isLetterOrDigit)
        return when {
            bChars >= aChars + 3 && sourceAwareScore(b) >= sourceAwareScore(a) - 12.0 -> b
            aChars >= bChars + 3 && sourceAwareScore(a) >= sourceAwareScore(b) - 12.0 -> a
            sourceAwareScore(b) > sourceAwareScore(a) -> b
            else -> a
        }
    }

    private fun samePhysicalLine(a: Rect, b: Rect): Boolean {
        val minHeight = min(a.height(), b.height()).coerceAtLeast(1)
        val verticalOverlap = overlapLength(a.top, a.bottom, b.top, b.bottom).toFloat() / minHeight
        if (verticalOverlap < PHYSICAL_LINE_MIN_VERTICAL_OVERLAP) return false

        val horizontalOverlap = overlapLength(a.left, a.right, b.left, b.right).toFloat() /
            min(a.width(), b.width()).coerceAtLeast(1)
        val containment = rectContainmentOverlap(a, b)
        val centerDelta = abs(rectCenterY(a) - rectCenterY(b))
        val centersAligned = centerDelta <= max(a.height(), b.height()) * PHYSICAL_LINE_CENTER_FACTOR

        return centersAligned &&
            (horizontalOverlap >= PHYSICAL_LINE_MIN_HORIZONTAL_OVERLAP ||
                containment >= PHYSICAL_LINE_MIN_CONTAINMENT)
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
                text = if (lineWords.isNotEmpty()) joinCameraWordTexts(lineWords) else line.text,
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

    private fun joinWordTexts(words: List<RawOcrLine>): String {
        val builder = StringBuilder()
        words.forEach { word -> appendOcrToken(builder, word.text) }
        return builder.toString().trim()
    }

    private fun joinCameraWordTexts(words: List<CameraOcrWord>): String {
        val builder = StringBuilder()
        words.forEach { word -> appendOcrToken(builder, word.text) }
        return builder.toString().trim()
    }

    private fun appendOcrToken(builder: StringBuilder, token: String) {
        val clean = token.trim()
        if (clean.isEmpty()) return
        val previous = builder.lastOrNull()
        val first = clean.firstOrNull()
        val needsSpace = builder.isNotEmpty() &&
            !(previous?.isHanCharacter() == true && first?.isHanCharacter() == true) &&
            first !in charArrayOf(',', '.', ':', ';', '!', '?', '%', ')', ']', '}', '，', '。', '：', '；', '！', '？')
        if (needsSpace) builder.append(' ')
        builder.append(clean)
    }

    private fun Char.isHanCharacter(): Boolean = this in '\u4E00'..'\u9FFF'

    private fun looksLikeWordNoise(word: RawOcrLine): Boolean {
        val alphanumeric = word.text.count(Char::isLetterOrDigit)
        if (alphanumeric == 0) return true
        val thin = word.rect.width() <= MIN_BOX_PIXELS * 2 || word.rect.height() <= MIN_BOX_PIXELS
        return thin && alphanumeric <= 2 && word.confidence < WORD_NOISE_CONFIDENCE
    }

    private fun rectCenterY(rect: Rect): Float = (rect.top + rect.bottom) / 2f

    private fun horizontalGap(a: Rect, b: Rect): Int = when {
        b.left >= a.right -> b.left - a.right
        a.left >= b.right -> a.left - b.right
        else -> 0
    }

'''
ocr = ocr[:start] + helpers + ocr[end:]

needle = '''    private const val WORD_TO_LINE_HORIZONTAL_MARGIN = 0.65f
'''
addition = '''    private const val WORD_TO_LINE_HORIZONTAL_MARGIN = 0.65f
    private const val WORD_ROW_MIN_VERTICAL_OVERLAP = 0.46f
    private const val WORD_ROW_CENTER_FACTOR = 0.40f
    private const val WORD_ROW_MAX_HEIGHT_RATIO = 1.95f
    private const val WORD_ROW_MAX_GAP_FACTOR = 2.7f
    private const val WORD_ROW_MAX_WIDTH_FRACTION = 0.92f
    private const val PHYSICAL_LINE_MIN_VERTICAL_OVERLAP = 0.68f
    private const val PHYSICAL_LINE_MIN_HORIZONTAL_OVERLAP = 0.44f
    private const val PHYSICAL_LINE_MIN_CONTAINMENT = 0.76f
    private const val PHYSICAL_LINE_CENTER_FACTOR = 0.34f
    private const val WORD_NOISE_CONFIDENCE = 52f
'''
ocr = replace_once(ocr, needle, addition, 'line reconstruction constants')

gradle = replace_once(gradle, 'versionCode = 24', 'versionCode = 25', 'version code')
gradle = replace_once(gradle, 'versionName = "0.1.15.8"', 'versionName = "0.1.15.9"', 'version name')

ocr_path.write_text(ocr)
gradle_path.write_text(gradle)
