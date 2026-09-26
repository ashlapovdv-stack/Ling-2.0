from pathlib import Path

ocr_path = Path('app/src/main/java/com/ling20/translator/CameraOcr.kt')
gradle_path = Path('app/build.gradle.kts')

text = ocr_path.read_text()

old_import = 'import com.googlecode.tesseract.android.TessBaseAPI\n'
new_import = '''import com.google.android.gms.tasks.Tasks\nimport com.google.mlkit.vision.common.InputImage\nimport com.google.mlkit.vision.text.TextRecognition\nimport com.google.mlkit.vision.text.TextRecognizer\nimport com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions\nimport com.google.mlkit.vision.text.latin.TextRecognizerOptions\nimport com.googlecode.tesseract.android.TessBaseAPI\n'''
assert old_import in text
text = text.replace(old_import, new_import, 1)

old = '''            val rawLines = mutableListOf<RawOcrLine>()\n            rawLines += recognizeWithLanguage(\n                dataPath = dataPath,\n                languageSpec = primaryLanguage,\n                preparedImages = preparedImages,\n                originalWidth = original.width,\n                originalHeight = original.height,\n            )\n'''
new = '''            val rawLines = mutableListOf<RawOcrLine>()\n\n            // Hybrid OCR: Tesseract remains the primary recognizer for Russian\n            // and mixed Cyrillic labels. Bundled ML Kit contributes stronger\n            // Latin/Chinese detections, especially large decorative headings.\n            rawLines += recognizeWithMlKit(\n                bitmap = original,\n                sourceLanguage = sourceLanguage,\n            )\n            rawLines += recognizeWithLanguage(\n                dataPath = dataPath,\n                languageSpec = primaryLanguage,\n                preparedImages = preparedImages,\n                originalWidth = original.width,\n                originalHeight = original.height,\n            )\n'''
assert old in text
text = text.replace(old, new, 1)

marker = '''    private fun recognizeWithLanguage(\n'''
assert marker in text
method = '''    private fun recognizeWithMlKit(\n        bitmap: Bitmap,\n        sourceLanguage: Language?,\n    ): List<RawOcrLine> {\n        val inputImage = InputImage.fromBitmap(bitmap, 0)\n        val recognizers = mutableListOf<TextRecognizer>()\n\n        // Latin is useful even when Russian is selected because product labels\n        // often contain English brand names and headings.\n        recognizers += TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)\n        if (sourceLanguage == null || sourceLanguage == Language.CHINESE) {\n            recognizers += TextRecognition.getClient(\n                ChineseTextRecognizerOptions.Builder().build(),\n            )\n        }\n\n        return try {\n            buildList {\n                recognizers.forEach { recognizer ->\n                    val result = runCatching {\n                        Tasks.await(recognizer.process(inputImage))\n                    }.getOrNull() ?: return@forEach\n\n                    result.textBlocks.forEach { block ->\n                        block.lines.forEach { line ->\n                            val rect = line.boundingBox\n                            val value = line.text.trim()\n                            if (\n                                rect != null &&\n                                value.isMeaningfulOcrText() &&\n                                rect.width() >= MIN_BOX_PIXELS &&\n                                rect.height() >= MIN_BOX_PIXELS\n                            ) {\n                                add(\n                                    RawOcrLine(\n                                        text = value,\n                                        rect = Rect(rect),\n                                        confidence = MLKIT_DEFAULT_CONFIDENCE,\n                                    ),\n                                )\n                            }\n                        }\n                    }\n                }\n            }\n        } finally {\n            recognizers.forEach(TextRecognizer::close)\n        }\n    }\n\n'''
text = text.replace(marker, method + marker, 1)

# Add confidence constant next to existing OCR thresholds.
old_const = '    private const val RAW_MIN_CONFIDENCE = 10f\n'
new_const = '    private const val MLKIT_DEFAULT_CONFIDENCE = 68f\n    private const val RAW_MIN_CONFIDENCE = 10f\n'
assert old_const in text
text = text.replace(old_const, new_const, 1)

ocr_path.write_text(text)

gradle = gradle_path.read_text()
assert 'versionCode = 21' in gradle and 'versionName = "0.1.15.5"' in gradle
gradle = gradle.replace('versionCode = 21', 'versionCode = 22', 1)
gradle = gradle.replace('versionName = "0.1.15.5"', 'versionName = "0.1.15.6"', 1)

old_dep = '''    implementation("com.github.adaptech-cz.Tesseract4Android:tesseract4android:4.9.0")\n\n    debugImplementation'''
new_dep = '''    implementation("com.github.adaptech-cz.Tesseract4Android:tesseract4android:4.9.0")\n\n    // Bundled ML Kit models are packaged in the APK and work without network.\n    // Latin complements Tesseract on decorative English headings; Chinese is\n    // enabled for Auto/Chinese camera OCR.\n    implementation("com.google.mlkit:text-recognition:16.0.1")\n    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")\n\n    debugImplementation'''
assert old_dep in gradle
gradle = gradle.replace(old_dep, new_dep, 1)
gradle_path.write_text(gradle)
