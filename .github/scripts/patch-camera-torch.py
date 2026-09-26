from pathlib import Path

camera_path = Path('app/src/main/java/com/ling20/translator/CameraModeScreen.kt')
text = camera_path.read_text()

def replace_once(old: str, new: str):
    global text
    if old not in text:
        raise SystemExit(f'Pattern not found:\n{old[:500]}')
    text = text.replace(old, new, 1)

replace_once(
    'import androidx.camera.core.CameraSelector\n',
    'import androidx.camera.core.Camera\nimport androidx.camera.core.CameraSelector\n',
)
replace_once(
    'import androidx.compose.material.icons.filled.Cameraswitch\n',
    'import androidx.compose.material.icons.filled.FlashOff\nimport androidx.compose.material.icons.filled.FlashOn\n',
)
replace_once(
'''private enum class CameraSourceLanguage(
    val language: Language?,
    val title: String,
) {
    AUTO(null, "Авто"),
    RUSSIAN(Language.RUSSIAN, Language.RUSSIAN.displayName),
    ENGLISH(Language.ENGLISH, Language.ENGLISH.displayName),
    CHINESE(Language.CHINESE, Language.CHINESE.displayName);
''',
'''private enum class CameraSourceLanguage(
    val language: Language?,
    val title: String,
    val symbol: String,
) {
    AUTO(null, "Авто", "🌐"),
    RUSSIAN(Language.RUSSIAN, Language.RUSSIAN.displayName, "🇷🇺"),
    ENGLISH(Language.ENGLISH, Language.ENGLISH.displayName, "🇬🇧"),
    CHINESE(Language.CHINESE, Language.CHINESE.displayName, "🇨🇳");
''',
)
replace_once(
'''    var cameraError by rememberSaveable { mutableStateOf<String?>(null) }
    var lensFacing by rememberSaveable { mutableStateOf(CameraSelector.LENS_FACING_BACK) }
    var hasCameraPermission by remember {
''',
'''    var cameraError by rememberSaveable { mutableStateOf<String?>(null) }
    var torchEnabled by rememberSaveable { mutableStateOf(false) }
    var boundCamera by remember { mutableStateOf<Camera?>(null) }
    var hasCameraPermission by remember {
''',
)
replace_once(
'''                LiveCameraPreview(
                    lifecycleOwner = lifecycleOwner,
                    lensFacing = lensFacing,
                    onCaptureReady = { imageCapture = it },
                    onError = {
                        imageCapture = null
                        cameraError = it
                    },
                )
''',
'''                LiveCameraPreview(
                    lifecycleOwner = lifecycleOwner,
                    onCaptureReady = { imageCapture = it },
                    onCameraReady = { camera ->
                        boundCamera = camera
                        if (camera == null) torchEnabled = false
                    },
                    onError = {
                        imageCapture = null
                        boundCamera = null
                        torchEnabled = false
                        cameraError = it
                    },
                )
''',
)
replace_once(
'''            onSwitchCamera = {
                selectedImageUri = null
                imageCapture = null
                cameraError = null
                lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                    CameraSelector.LENS_FACING_FRONT
                } else {
                    CameraSelector.LENS_FACING_BACK
                }
            },
''',
'''            torchEnabled = torchEnabled,
            torchAvailable = selectedImageUri == null &&
                hasCameraPermission &&
                boundCamera?.cameraInfo?.hasFlashUnit() == true,
            onToggleTorch = {
                val camera = boundCamera
                if (camera != null && camera.cameraInfo.hasFlashUnit()) {
                    val next = !torchEnabled
                    camera.cameraControl.enableTorch(next)
                    torchEnabled = next
                    cameraError = null
                }
            },
''',
)
replace_once(
'''    imageSelected: Boolean,
    onGallery: () -> Unit,
    onCapture: () -> Unit,
    onSwitchCamera: () -> Unit,
    modifier: Modifier = Modifier,
) {
''',
'''    imageSelected: Boolean,
    torchEnabled: Boolean,
    torchAvailable: Boolean,
    onGallery: () -> Unit,
    onCapture: () -> Unit,
    onToggleTorch: () -> Unit,
    modifier: Modifier = Modifier,
) {
''',
)
replace_once(
'''        CameraOverlayAction(
            icon = Icons.Default.Cameraswitch,
            label = "Камера",
            contentDescription = "Переключить переднюю и заднюю камеру",
            enabled = hasCameraPermission,
            onClick = onSwitchCamera,
        )
''',
'''        CameraOverlayAction(
            icon = if (torchEnabled) Icons.Default.FlashOff else Icons.Default.FlashOn,
            label = "Фонарик",
            contentDescription = if (torchEnabled) "Выключить фонарик" else "Включить фонарик",
            enabled = torchAvailable,
            active = torchEnabled,
            onClick = onToggleTorch,
        )
''',
)
replace_once(
'''    contentDescription: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
''',
'''    contentDescription: String,
    enabled: Boolean = true,
    active: Boolean = false,
    onClick: () -> Unit,
) {
''',
)
replace_once(
'''            color = if (enabled) CameraOverlay else CameraOverlay.copy(alpha = 0.45f),
            contentColor = Color.White,
''',
'''            color = when {
                !enabled -> CameraOverlay.copy(alpha = 0.45f)
                active -> MaterialTheme.colorScheme.primary.copy(alpha = 0.92f)
                else -> CameraOverlay
            },
            contentColor = Color.White,
''',
)
replace_once(
'''private fun LiveCameraPreview(
    lifecycleOwner: androidx.lifecycle.LifecycleOwner,
    lensFacing: Int,
    onCaptureReady: (ImageCapture?) -> Unit,
    onError: (String) -> Unit,
) {
''',
'''private fun LiveCameraPreview(
    lifecycleOwner: androidx.lifecycle.LifecycleOwner,
    onCaptureReady: (ImageCapture?) -> Unit,
    onCameraReady: (Camera?) -> Unit,
    onError: (String) -> Unit,
) {
''',
)
replace_once(
'''    DisposableEffect(lifecycleOwner, previewView, lensFacing) {
        onCaptureReady(null)
''',
'''    DisposableEffect(lifecycleOwner, previewView) {
        onCaptureReady(null)
        onCameraReady(null)
''',
)
replace_once(
'''                val selector = CameraSelector.Builder()
                    .requireLensFacing(lensFacing)
                    .build()
                check(cameraProvider.hasCamera(selector)) {
                    "Выбранная камера недоступна на этом устройстве."
                }
''',
'''                val selector = CameraSelector.DEFAULT_BACK_CAMERA
                check(cameraProvider.hasCamera(selector)) {
                    "Задняя камера недоступна на этом устройстве."
                }
''',
)
replace_once(
'''                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    selector,
                    preview,
                    capture,
                )
                onCaptureReady(capture)
''',
'''                val camera = cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    selector,
                    preview,
                    capture,
                )
                onCaptureReady(capture)
                onCameraReady(camera)
''',
)
replace_once(
'''            }.onFailure { throwable ->
                onCaptureReady(null)
                onError(throwable.message ?: "Не удалось открыть камеру.")
''',
'''            }.onFailure { throwable ->
                onCaptureReady(null)
                onCameraReady(null)
                onError(throwable.message ?: "Не удалось открыть камеру.")
''',
)
replace_once(
'''        onDispose {
            onCaptureReady(null)
            if (future.isDone) {
''',
'''        onDispose {
            onCaptureReady(null)
            onCameraReady(null)
            if (future.isDone) {
''',
)
replace_once(
'''        CameraLanguageSurface(
            title = source.title,
            onClick = { expanded = true },
        )
''',
'''        CameraLanguageSurface(
            symbol = source.symbol,
            title = source.title,
            onClick = { expanded = true },
        )
''',
)
replace_once(
'''                            if (item == CameraSourceLanguage.AUTO) {
                                "Автоопределение"
                            } else {
                                item.title
                            },
''',
'''                            if (item == CameraSourceLanguage.AUTO) {
                                "${item.symbol}  Автоопределение"
                            } else {
                                "${item.symbol}  ${item.title}"
                            },
''',
)
replace_once(
'''        CameraLanguageSurface(
            title = language.displayName,
            onClick = { expanded = true },
        )
''',
'''        CameraLanguageSurface(
            symbol = language.cameraFlag(),
            title = language.displayName,
            onClick = { expanded = true },
        )
''',
)
replace_once(
'''                DropdownMenuItem(
                    text = { Text(item.displayName) },
''',
'''                DropdownMenuItem(
                    text = { Text("${item.cameraFlag()}  ${item.displayName}") },
''',
)
replace_once(
'''private fun CameraLanguageSurface(
    title: String,
    onClick: () -> Unit,
) {
''',
'''private fun CameraLanguageSurface(
    symbol: String,
    title: String,
    onClick: () -> Unit,
) {
''',
)
replace_once(
'''        Row(
            modifier = Modifier.padding(horizontal = 15.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title,
''',
'''        Row(
            modifier = Modifier.padding(horizontal = 15.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(symbol, style = MaterialTheme.typography.titleMedium)
            Text(
                title,
''',
)
replace_once(
'''                modifier = Modifier.weight(1f),
''',
'''                modifier = Modifier
                    .padding(start = 7.dp)
                    .weight(1f),
''',
)

if 'private fun Language.cameraFlag()' not in text:
    text = text.rstrip() + '''\n\nprivate fun Language.cameraFlag(): String = when (this) {
    Language.RUSSIAN -> "🇷🇺"
    Language.ENGLISH -> "🇬🇧"
    Language.CHINESE -> "🇨🇳"
}\n'''

camera_path.write_text(text)

gradle_path = Path('app/build.gradle.kts')
gradle = gradle_path.read_text()
if 'versionCode = 14' not in gradle or 'versionName = "0.1.13"' not in gradle:
    raise SystemExit('Unexpected current version')
gradle = gradle.replace('versionCode = 14', 'versionCode = 15', 1)
gradle = gradle.replace('versionName = "0.1.13"', 'versionName = "0.1.14"', 1)
gradle_path.write_text(gradle)
