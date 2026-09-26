from pathlib import Path

path = Path('app/src/main/java/com/ling20/translator/CameraModeScreen.kt')
text = path.read_text()


def replace_once(old: str, new: str):
    global text
    if old not in text:
        raise SystemExit(f'Pattern not found:\n{old[:500]}')
    text = text.replace(old, new, 1)


replace_once(
    'import androidx.compose.material3.Button\n',
    'import androidx.compose.material3.Button\nimport androidx.compose.material3.CircularProgressIndicator\n',
)

replace_once(
    '    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }\n\n'
    '    val source = CameraSourceLanguage.valueOf(sourceName)\n',
    '    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }\n'
    '    var ocrResult by remember { mutableStateOf<CameraOcrResult?>(null) }\n'
    '    var ocrRunning by remember { mutableStateOf(false) }\n'
    '    var ocrError by rememberSaveable { mutableStateOf<String?>(null) }\n\n'
    '    val source = CameraSourceLanguage.valueOf(sourceName)\n',
)

replace_once(
    '    LaunchedEffect(Unit) {\n'
    '        if (!hasCameraPermission) permissionLauncher.launch(Manifest.permission.CAMERA)\n'
    '    }\n\n'
    '    Box(\n',
    '    LaunchedEffect(Unit) {\n'
    '        if (!hasCameraPermission) permissionLauncher.launch(Manifest.permission.CAMERA)\n'
    '    }\n\n'
    '    LaunchedEffect(selectedImageUri, source.language) {\n'
    '        val uri = selectedImageUri\n'
    '        if (uri == null) {\n'
    '            ocrResult = null\n'
    '            ocrRunning = false\n'
    '            ocrError = null\n'
    '        } else {\n'
    '            ocrResult = null\n'
    '            ocrRunning = true\n'
    '            ocrError = null\n'
    '            runCatching {\n'
    '                CameraOcrEngine.recognize(\n'
    '                    context = context.applicationContext,\n'
    '                    uriString = uri,\n'
    '                    sourceLanguage = source.language,\n'
    '                )\n'
    '            }.onSuccess { result ->\n'
    '                ocrResult = result\n'
    '                if (result.blocks.isEmpty()) {\n'
    '                    ocrError = "Текстовые блоки не найдены. Попробуйте приблизить текст или улучшить освещение."\n'
    '                }\n'
    '            }.onFailure { error ->\n'
    '                ocrError = "Ошибка OCR: ${error.message ?: "неизвестная ошибка"}"\n'
    '            }\n'
    '            ocrRunning = false\n'
    '        }\n'
    '    }\n\n'
    '    Box(\n',
)

replace_once(
    '            selectedImageUri != null -> {\n'
    '                SelectedCameraImage(selectedImageUri!!)\n'
    '            }\n',
    '            selectedImageUri != null -> {\n'
    '                SelectedCameraImage(selectedImageUri!!)\n'
    '                ocrResult?.let { result ->\n'
    '                    CameraOcrOverlay(\n'
    '                        result = result,\n'
    '                        modifier = Modifier.fillMaxSize(),\n'
    '                    )\n'
    '                }\n'
    '            }\n',
)

marker = '''        if (cameraError != null) {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 78.dp, start = 18.dp, end = 18.dp),
                shape = RoundedCornerShape(14.dp),
                color = Color(0xD9B42318),
                contentColor = Color.White,
            ) {
                Text(
                    cameraError!!,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
'''
addition = marker + '''
        if (selectedImageUri != null && cameraError == null) {
            CameraOcrStatus(
                running = ocrRunning,
                result = ocrResult,
                error = ocrError,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 78.dp, start = 18.dp, end = 18.dp),
            )
        }
'''
replace_once(marker, addition)

insert_before = '''@Composable
private fun CameraPermissionState(onRequestPermission: () -> Unit) {
'''
status_fn = '''@Composable
private fun CameraOcrStatus(
    running: Boolean,
    result: CameraOcrResult?,
    error: String?,
    modifier: Modifier = Modifier,
) {
    val message = when {
        running -> "Распознаю текст…"
        error != null -> error
        result != null -> "OCR: найдено ${result.blocks.size} текстовых блоков"
        else -> return
    }
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = if (error != null) Color(0xD9B42318) else CameraOverlayStrong,
        contentColor = Color.White,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            if (running) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = Color.White,
                )
            }
            Text(message, style = MaterialTheme.typography.bodySmall)
        }
    }
}

'''
replace_once(insert_before, status_fn + insert_before)

old_image_loader = '''    val bitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(
        initialValue = null,
        key1 = uriString,
    ) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val uri = Uri.parse(uriString)
                val source = if (uri.scheme == "file") {
                    ImageDecoder.createSource(File(requireNotNull(uri.path)))
                } else {
                    ImageDecoder.createSource(context.contentResolver, uri)
                }
                ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }.asImageBitmap()
            }.getOrNull()
        }
    }
'''
new_image_loader = '''    val bitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(
        initialValue = null,
        key1 = uriString,
    ) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                loadCameraBitmap(context, uriString).asImageBitmap()
            }.getOrNull()
        }
    }
'''
replace_once(old_image_loader, new_image_loader)

# Remove imports no longer needed by this screen.
text = text.replace('import android.graphics.ImageDecoder\n', '')
text = text.replace('import kotlinx.coroutines.Dispatchers\n', 'import kotlinx.coroutines.Dispatchers\n')
text = text.replace('import java.io.File\n', 'import java.io.File\n')

path.write_text(text)
