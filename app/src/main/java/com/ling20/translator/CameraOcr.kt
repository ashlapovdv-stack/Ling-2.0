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
import kotlin.math.min

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

internal object CameraOcrEngine {
    suspend fun recognize(
        context: Context,
        uriString: String,
        sourceLanguage: Language?,
    ): CameraOcrResult = withContext(Dispatchers.Default) {
        val bitmap = loadCameraBitmap(context, uriString)
        val languageSpec = when (sourceLanguage) {
            Language.RUSSIAN -> "rus"
            Language.ENGLISH -> "eng"
            Language.CHINESE -> "chi_sim"
            null -> "rus+eng+chi_sim"
        }
        val dataPath = prepareTessData(context, languageSpec)
        val tess = TessBaseAPI()

        try {
            check(
                tess.init(
                    dataPath.absolutePath,
                    languageSpec,
                    TessBaseAPI.OEM_LSTM_ONLY,
                ),
            ) { "Не удалось запустить офлайн OCR." }

            tess.setPageSegMode(TessBaseAPI.PageSegMode.PSM_AUTO)
            tess.setImage(bitmap)
            val fullText = tess.getUTF8Text().orEmpty().trim()
            val blocks = mutableListOf<CameraOcrBlock>()
            val iterator = tess.resultIterator

            if (iterator != null && fullText.isNotBlank()) {
                try {
                    iterator.begin()
                    do {
                        val text = iterator
                            .getUTF8Text(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE)
                            ?.trim()
                            .orEmpty()
                        val rect = iterator.getBoundingRect(
                            TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE,
                        )
                        val confidence = iterator.confidence(
                            TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE,
                        )

                        if (
                            text.isNotBlank() &&
                            rect != null &&
                            rect.width() > 2 &&
                            rect.height() > 2 &&
                            confidence >= MIN_CONFIDENCE
                        ) {
                            blocks += CameraOcrBlock(
                                text = text,
                                rect = Rect(rect),
                                confidence = confidence,
                            )
                        }
                    } while (iterator.next(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE))
                } finally {
                    iterator.delete()
                }
            }

            CameraOcrResult(
                imageWidth = bitmap.width,
                imageHeight = bitmap.height,
                fullText = fullText,
                blocks = blocks,
                languageSpec = languageSpec,
            )
        } finally {
            tess.recycle()
            bitmap.recycle()
        }
    }

    private fun prepareTessData(context: Context, languageSpec: String): File {
        val dataPath = File(context.filesDir, "camera_ocr").apply { mkdirs() }
        val tessDataDir = File(dataPath, "tessdata").apply { mkdirs() }
        languageSpec.split('+').forEach { code ->
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

    private const val MIN_CONFIDENCE = 28f
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
