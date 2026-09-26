from pathlib import Path

ocr_path = Path('app/src/main/java/com/ling20/translator/CameraOcr.kt')
gradle_path = Path('app/build.gradle.kts')

text = ocr_path.read_text()

old = '''        val primaryLanguage = sourceLanguage.toTessLanguage()\n        val allRequiredLanguages = if (sourceLanguage == null) {\n            setOf("rus", "eng", "chi_sim")\n        } else {\n            setOf(primaryLanguage)\n        }\n        val dataPath = prepareTessData(context, allRequiredLanguages)\n'''
new = '''        // OCR is deliberately multilingual even when the user selects a source\n        // language. Product labels frequently mix Russian/Chinese with English\n        // brand names, numbers and short Latin phrases. The selected language is\n        // still placed first so Tesseract gives it priority.\n        val primaryLanguage = sourceLanguage.toTessLanguageSpec()\n        val dataPath = prepareTessData(context, setOf("rus", "eng", "chi_sim"))\n'''
assert old in text
text = text.replace(old, new, 1)

old = '''            // AUTO gets a second language-specific attempt. It noticeably helps\n            // labels/signs where one script dominates but the combined model is\n            // distracted by decorative fonts or mixed text.\n            if (sourceLanguage == null) {\n                val dominant = detectDominantLanguage(rawLines)\n                val fallbackLanguages = if (isWeakResult(rawLines)) {\n                    listOf("rus", "eng", "chi_sim")\n                } else {\n                    dominant?.let(::listOf).orEmpty()\n                }\n\n                fallbackLanguages\n                    .filter { it != primaryLanguage }\n                    .forEach { language ->\n                        rawLines += recognizeWithLanguage(\n                            dataPath = dataPath,\n                            languageSpec = language,\n                            preparedImages = preparedImages,\n                            originalWidth = original.width,\n                            originalHeight = original.height,\n                        )\n                    }\n            }\n'''
new = '''            // If the combined pass is weak, retry individual scripts. AUTO can\n            // also use the dominant detected script as a precision pass.\n            val fallbackLanguages = when {\n                sourceLanguage == null && isWeakResult(rawLines) ->\n                    listOf("rus", "eng", "chi_sim")\n                sourceLanguage == null ->\n                    detectDominantLanguage(rawLines)?.let(::listOf).orEmpty()\n                isWeakResult(rawLines) ->\n                    sourceLanguage.fallbackTessLanguages()\n                else -> emptyList()\n            }\n\n            fallbackLanguages.forEach { language ->\n                rawLines += recognizeWithLanguage(\n                    dataPath = dataPath,\n                    languageSpec = language,\n                    preparedImages = preparedImages,\n                    originalWidth = original.width,\n                    originalHeight = original.height,\n                )\n            }\n'''
assert old in text
text = text.replace(old, new, 1)

old = '''            // AUTO works best for ordinary paragraphs. SPARSE_TEXT is much\n            // better for product labels, signs and large isolated headings.\n            val passes = listOf(\n                preparedImages[0] to TessBaseAPI.PageSegMode.PSM_AUTO,\n                preparedImages[1] to TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT,\n            )\n'''
new = '''            // Keep a colour pass because global thresholding can erase pale or\n            // decorative lettering. Grayscale AUTO handles normal paragraphs,\n            // while the binary sparse pass catches small high-contrast fragments.\n            val passes = listOf(\n                preparedImages[0] to TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT,\n                preparedImages[1] to TessBaseAPI.PageSegMode.PSM_AUTO,\n                preparedImages[2] to TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT,\n            )\n'''
assert old in text
text = text.replace(old, new, 1)

old = '''        val enhanced = makeHighContrastGrayscale(scaled, binary = false)\n        val binary = makeHighContrastGrayscale(scaled, binary = true)\n\n        if (scaled !== original && scaled !== enhanced && scaled !== binary) {\n            scaled.recycle()\n        }\n\n        return listOf(\n            PreparedImage(enhanced, actualScale),\n            PreparedImage(binary, actualScale),\n        )\n'''
new = '''        val enhanced = makeHighContrastGrayscale(scaled, binary = false)\n        val binary = makeHighContrastGrayscale(scaled, binary = true)\n\n        return listOf(\n            PreparedImage(scaled, actualScale),\n            PreparedImage(enhanced, actualScale),\n            PreparedImage(binary, actualScale),\n        )\n'''
assert old in text
text = text.replace(old, new, 1)

old = '''    private fun prepareTessData(context: Context, languageCodes: Set<String>): File {\n        val dataPath = File(context.filesDir, "camera_ocr").apply { mkdirs() }\n        val tessDataDir = File(dataPath, "tessdata").apply { mkdirs() }\n        languageCodes.forEach { code ->\n            val fileName = "$code.traineddata"\n            val destination = File(tessDataDir, fileName)\n            if (!destination.exists() || destination.length() == 0L) {\n                context.assets.open("tessdata/$fileName").use { input ->\n                    destination.outputStream().use { output -> input.copyTo(output) }\n                }\n            }\n        }\n        return dataPath\n    }\n\n    private fun Language?.toTessLanguage(): String = when (this) {\n        Language.RUSSIAN -> "rus"\n        Language.ENGLISH -> "eng"\n        Language.CHINESE -> "chi_sim"\n        null -> "rus+eng+chi_sim"\n    }\n'''
new = '''    private fun prepareTessData(context: Context, languageCodes: Set<String>): File {\n        // New directory forces existing installations to replace the previous\n        // tessdata_fast files with the more accurate tessdata_best models.\n        val dataPath = File(context.filesDir, "camera_ocr_best_v1").apply { mkdirs() }\n        val tessDataDir = File(dataPath, "tessdata").apply { mkdirs() }\n        languageCodes.forEach { code ->\n            val fileName = "$code.traineddata"\n            val destination = File(tessDataDir, fileName)\n            if (!destination.exists() || destination.length() == 0L) {\n                context.assets.open("tessdata/$fileName").use { input ->\n                    destination.outputStream().use { output -> input.copyTo(output) }\n                }\n            }\n        }\n        return dataPath\n    }\n\n    private fun Language?.toTessLanguageSpec(): String = when (this) {\n        Language.RUSSIAN -> "rus+eng"\n        Language.ENGLISH -> "eng+rus"\n        Language.CHINESE -> "chi_sim+eng"\n        null -> "rus+eng+chi_sim"\n    }\n\n    private fun Language.fallbackTessLanguages(): List<String> = when (this) {\n        Language.RUSSIAN -> listOf("rus", "eng")\n        Language.ENGLISH -> listOf("eng", "rus")\n        Language.CHINESE -> listOf("chi_sim", "eng")\n    }\n'''
assert old in text
text = text.replace(old, new, 1)

text = text.replace('private const val RAW_MIN_CONFIDENCE = 14f', 'private const val RAW_MIN_CONFIDENCE = 10f', 1)
text = text.replace('private const val FINAL_MIN_CONFIDENCE = 24f', 'private const val FINAL_MIN_CONFIDENCE = 20f', 1)
ocr_path.write_text(text)

gradle = gradle_path.read_text()
assert 'versionCode = 19' in gradle and 'versionName = "0.1.15.3"' in gradle
gradle = gradle.replace('versionCode = 19', 'versionCode = 20', 1)
gradle = gradle.replace('versionName = "0.1.15.3"', 'versionName = "0.1.15.4"', 1)
gradle_path.write_text(gradle)
