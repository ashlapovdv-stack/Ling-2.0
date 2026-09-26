from pathlib import Path

ocr_path = Path('app/src/main/java/com/ling20/translator/CameraOcr.kt')
text = ocr_path.read_text()

old = '''        return groups
            .mapNotNull { group ->'''
new = '''        val candidates = groups
            .mapNotNull { group ->'''
if old not in text:
    raise SystemExit('groups return anchor not found')
text = text.replace(old, new, 1)

old = '''            .sortedWith(compareBy<CameraOcrBlock> { it.rect.top }.thenBy { it.rect.left })
    }

    private fun canJoinLines('''
new = '''            .sortedWith(compareBy<CameraOcrBlock> { it.rect.top }.thenBy { it.rect.left })

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
                        text = previous.text.trimEnd() + "\\n" + block.text.trimStart(),
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
        val previousLines = previous.text.count { it == '\\n' } + 1
        val nextLines = next.text.count { it == '\\n' } + 1
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

    private fun canJoinLines('''
if old not in text:
    raise SystemExit('post-group anchor not found')
text = text.replace(old, new, 1)

old = '''    private const val MIN_BLOCK_HEIGHT_FRACTION = 0.004f
}'''
new = '''    private const val MIN_BLOCK_HEIGHT_FRACTION = 0.004f

    private const val BARCODE_MIN_DIGITS = 8
    private const val BARCODE_MAX_LETTERS = 1
    private const val BARCODE_DIGIT_RATIO = 0.78f
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
    private const val PARAGRAPH_MAX_HEIGHT_RATIO = 1.45f
    private const val PARAGRAPH_MAX_GAP_FACTOR = 0.70f
    private const val PARAGRAPH_MAX_HEIGHT_FRACTION = 0.20f
    private const val PARAGRAPH_LEFT_ALIGNMENT_FACTOR = 1.4f
    private const val PARAGRAPH_MIN_HORIZONTAL_OVERLAP = 0.55f
}'''
if old not in text:
    raise SystemExit('constants anchor not found')
text = text.replace(old, new, 1)

ocr_path.write_text(text)

gradle = Path('app/build.gradle.kts')
g = gradle.read_text()
g = g.replace('versionCode = 18', 'versionCode = 19', 1)
g = g.replace('versionName = "0.1.15.2"', 'versionName = "0.1.15.3"', 1)
gradle.write_text(g)
