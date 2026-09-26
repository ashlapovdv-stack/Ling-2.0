from pathlib import Path

ocr_path = Path('app/src/main/java/com/ling20/translator/CameraOcr.kt')
text = ocr_path.read_text()
start = text.index('    private fun groupLinesIntoBlocks(')
end = text.index('    private fun verticalGap(', start)
replacement = r'''    private fun groupLinesIntoBlocks(
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

'''
text = text[:start] + replacement + text[end:]
text = text.replace('    private const val MAX_VERTICAL_GAP_FACTOR = 1.25f\n    private const val LEFT_ALIGNMENT_FACTOR = 1.5f\n', '''    private const val MAX_VERTICAL_GAP_FACTOR = 0.72f
    private const val LEFT_ALIGNMENT_FACTOR = 0.85f
    private const val CENTER_ALIGNMENT_FACTOR = 0.22f
    private const val MAX_LINE_HEIGHT_RATIO = 1.75f
    private const val MAX_LINE_OVERLAP_FACTOR = 0.35f
    private const val MAX_BLOCK_HEIGHT_FRACTION = 0.16f
    private const val MAX_LINES_PER_BLOCK = 5
    private const val TINY_BLOCK_WIDTH_FRACTION = 0.035f
    private const val TINY_BLOCK_HEIGHT_FRACTION = 0.012f
    private const val TINY_BLOCK_MIN_CHARS = 4
''')
ocr_path.write_text(text)

build_path = Path('app/build.gradle.kts')
build = build_path.read_text()
build = build.replace('versionCode = 17', 'versionCode = 18')
build = build.replace('versionName = "0.1.15.1"', 'versionName = "0.1.15.2"')
build_path.write_text(build)
