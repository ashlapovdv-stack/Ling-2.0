from pathlib import Path

ocr_path = Path('app/src/main/java/com/ling20/translator/CameraOcr.kt')
gradle_path = Path('app/build.gradle.kts')

text = ocr_path.read_text()

old = '''            val fallbackLanguages = when {\n                sourceLanguage == null && isWeakResult(rawLines) ->\n                    listOf("rus", "eng", "chi_sim")\n                sourceLanguage == null ->\n                    detectDominantLanguage(rawLines)?.let(::listOf).orEmpty()\n                isWeakResult(rawLines) ->\n                    sourceLanguage.fallbackTessLanguages()\n                else -> emptyList()\n            }\n\n            fallbackLanguages.forEach { language ->\n                rawLines += recognizeWithLanguage(\n                    dataPath = dataPath,\n                    languageSpec = language,\n                    preparedImages = preparedImages,\n                    originalWidth = original.width,\n                    originalHeight = original.height,\n                )\n            }\n'''
new = '''            val fallbackLanguages = when {\n                sourceLanguage == null && isWeakResult(rawLines) ->\n                    listOf("rus", "eng", "chi_sim")\n                sourceLanguage == null ->\n                    detectDominantLanguage(rawLines)?.let(::listOf).orEmpty()\n                else -> sourceLanguage.fallbackTessLanguages()\n            }\n\n            // A mixed model is good at ordinary labels, but a dedicated language\n            // pass often recovers stylised headings or brand names. Run those\n            // precision passes even when the mixed result already looks strong.\n            fallbackLanguages.forEach { language ->\n                rawLines += recognizeWithLanguage(\n                    dataPath = dataPath,\n                    languageSpec = language,\n                    preparedImages = preparedImages,\n                    originalWidth = original.width,\n                    originalHeight = original.height,\n                    fullPassSet = false,\n                )\n            }\n'''
assert old in text
text = text.replace(old, new, 1)

old = '''    private fun recognizeWithLanguage(\n        dataPath: File,\n        languageSpec: String,\n        preparedImages: List<PreparedImage>,\n        originalWidth: Int,\n        originalHeight: Int,\n    ): List<RawOcrLine> {\n'''
new = '''    private fun recognizeWithLanguage(\n        dataPath: File,\n        languageSpec: String,\n        preparedImages: List<PreparedImage>,\n        originalWidth: Int,\n        originalHeight: Int,\n        fullPassSet: Boolean = true,\n    ): List<RawOcrLine> {\n'''
assert old in text
text = text.replace(old, new, 1)

old = '''            val passes = listOf(\n                preparedImages[0] to TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT,\n                preparedImages[1] to TessBaseAPI.PageSegMode.PSM_AUTO,\n                preparedImages[2] to TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT,\n            )\n'''
new = '''            val passes = if (fullPassSet) {\n                listOf(\n                    preparedImages[0] to TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT,\n                    preparedImages[1] to TessBaseAPI.PageSegMode.PSM_AUTO,\n                    preparedImages[2] to TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT,\n                    preparedImages[3] to TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT,\n                )\n            } else {\n                listOf(\n                    preparedImages[0] to TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT,\n                    preparedImages[1] to TessBaseAPI.PageSegMode.PSM_AUTO,\n                )\n            }\n'''
assert old in text
text = text.replace(old, new, 1)

old = '''        val lines = collectIteratorLevel(\n            tess = tess,\n            level = TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE,\n            imageScale = imageScale,\n            originalWidth = originalWidth,\n            originalHeight = originalHeight,\n        )\n        if (lines.isNotEmpty()) return lines\n\n        // Some sparse/decorative labels expose only word-level boxes.\n        return collectIteratorLevel(\n            tess = tess,\n            level = TessBaseAPI.PageIteratorLevel.RIL_WORD,\n            imageScale = imageScale,\n            originalWidth = originalWidth,\n            originalHeight = originalHeight,\n        )\n'''
new = '''        val lines = collectIteratorLevel(\n            tess = tess,\n            level = TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE,\n            imageScale = imageScale,\n            originalWidth = originalWidth,\n            originalHeight = originalHeight,\n            minConfidence = RAW_MIN_CONFIDENCE,\n        )\n        val words = collectIteratorLevel(\n            tess = tess,\n            level = TessBaseAPI.PageIteratorLevel.RIL_WORD,\n            imageScale = imageScale,\n            originalWidth = originalWidth,\n            originalHeight = originalHeight,\n            minConfidence = WORD_MIN_CONFIDENCE,\n        )\n\n        // Always keep word-level candidates as well. Large isolated words on\n        // packaging (AQUA, SPRAY, 99%) can disappear from TEXTLINE even when\n        // Tesseract has a good word box. Suppress only words already represented\n        // by an equivalent text line.\n        val extraWords = words.filter { word ->\n            val normalizedWord = normalizedText(word.text)\n            lines.none { line ->\n                normalizedWord.isNotEmpty() &&\n                    normalizedText(line.text).contains(normalizedWord) &&\n                    rectContainmentOverlap(line.rect, word.rect) >= WORD_INSIDE_LINE_OVERLAP\n            }\n        }\n        return lines + extraWords\n'''
assert old in text
text = text.replace(old, new, 1)

old = '''    private fun collectIteratorLevel(\n        tess: TessBaseAPI,\n        level: Int,\n        imageScale: Float,\n        originalWidth: Int,\n        originalHeight: Int,\n    ): List<RawOcrLine> {\n'''
new = '''    private fun collectIteratorLevel(\n        tess: TessBaseAPI,\n        level: Int,\n        imageScale: Float,\n        originalWidth: Int,\n        originalHeight: Int,\n        minConfidence: Float,\n    ): List<RawOcrLine> {\n'''
assert old in text
text = text.replace(old, new, 1)
text = text.replace('                    confidence >= RAW_MIN_CONFIDENCE\n', '                    confidence >= minConfidence\n', 1)

old = '''        val enhanced = makeHighContrastGrayscale(scaled, binary = false)\n        val binary = makeHighContrastGrayscale(scaled, binary = true)\n\n        return listOf(\n            PreparedImage(scaled, actualScale),\n            PreparedImage(enhanced, actualScale),\n            PreparedImage(binary, actualScale),\n        )\n    }\n\n    private fun makeHighContrastGrayscale(source: Bitmap, binary: Boolean): Bitmap {\n'''
new = '''        val enhanced = makeHighContrastGrayscale(scaled, binary = false)\n        val binary = makeHighContrastGrayscale(scaled, binary = true)\n        val invertedBinary = makeHighContrastGrayscale(scaled, binary = true, invert = true)\n\n        return listOf(\n            PreparedImage(scaled, actualScale),\n            PreparedImage(enhanced, actualScale),\n            PreparedImage(binary, actualScale),\n            PreparedImage(invertedBinary, actualScale),\n        )\n    }\n\n    private fun makeHighContrastGrayscale(\n        source: Bitmap,\n        binary: Boolean,\n        invert: Boolean = false,\n    ): Bitmap {\n'''
assert old in text
text = text.replace(old, new, 1)

old = '''            val value = if (binary) {\n                if (stretched >= otsu) 255 else 0\n            } else {\n                stretched\n            }\n            output[index] = (0xFF shl 24) or (value shl 16) or (value shl 8) or value\n'''
new = '''            var value = if (binary) {\n                if (stretched >= otsu) 255 else 0\n            } else {\n                stretched\n            }\n            if (invert) value = 255 - value\n            output[index] = (0xFF shl 24) or (value shl 16) or (value shl 8) or value\n'''
assert old in text
text = text.replace(old, new, 1)

text = text.replace('private const val RAW_MIN_CONFIDENCE = 10f', 'private const val RAW_MIN_CONFIDENCE = 10f\n    private const val WORD_MIN_CONFIDENCE = 24f\n    private const val WORD_INSIDE_LINE_OVERLAP = 0.84f', 1)
ocr_path.write_text(text)

gradle = gradle_path.read_text()
assert 'versionCode = 20' in gradle and 'versionName = "0.1.15.4"' in gradle
gradle = gradle.replace('versionCode = 20', 'versionCode = 21', 1)
gradle = gradle.replace('versionName = "0.1.15.4"', 'versionName = "0.1.15.5"', 1)
gradle_path.write_text(gradle)
