package com.ling20.translator

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private enum class CameraSourceLanguage(
    val language: Language?,
    val title: String,
    val symbol: String,
) {
    AUTO(null, "Авто", "🌐"),
    RUSSIAN(Language.RUSSIAN, Language.RUSSIAN.displayName, "🇷🇺"),
    ENGLISH(Language.ENGLISH, Language.ENGLISH.displayName, "🇬🇧"),
    CHINESE(Language.CHINESE, Language.CHINESE.displayName, "🇨🇳");

    companion object {
        fun from(language: Language?): CameraSourceLanguage = when (language) {
            Language.RUSSIAN -> RUSSIAN
            Language.ENGLISH -> ENGLISH
            Language.CHINESE -> CHINESE
            null -> AUTO
        }
    }
}

private val CameraOverlay = Color(0xC91B2736)
private val CameraOverlayStrong = Color(0xE01B2736)

@Composable
internal fun CameraModeScreen(
    defaultSource: Language?,
    defaultTarget: Language,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var sourceName by rememberSaveable(defaultSource, defaultTarget) {
        mutableStateOf(
            if (defaultSource == defaultTarget) CameraSourceLanguage.AUTO.name
            else CameraSourceLanguage.from(defaultSource).name,
        )
    }
    var targetName by rememberSaveable(defaultTarget) { mutableStateOf(defaultTarget.name) }
    var selectedImageUri by rememberSaveable { mutableStateOf<String?>(null) }
    var cameraError by rememberSaveable { mutableStateOf<String?>(null) }
    var torchEnabled by rememberSaveable { mutableStateOf(false) }
    var boundCamera by remember { mutableStateOf<Camera?>(null) }
    var hasCameraPermission by remember {
        mutableStateOf(
            context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED,
        )
    }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }

    val source = CameraSourceLanguage.valueOf(sourceName)
    val target = Language.valueOf(targetName)

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasCameraPermission = granted
        cameraError = if (granted) null else "Для съёмки нужно разрешить доступ к камере."
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri != null) {
            selectedImageUri = uri.toString()
            cameraError = null
        }
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        when {
            selectedImageUri != null -> {
                SelectedCameraImage(selectedImageUri!!)
            }

            hasCameraPermission -> {
                LiveCameraPreview(
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
            }

            else -> {
                CameraPermissionState(
                    onRequestPermission = {
                        permissionLauncher.launch(Manifest.permission.CAMERA)
                    },
                )
            }
        }

        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(132.dp)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Black.copy(alpha = 0.58f), Color.Transparent),
                    ),
                ),
        )

        CameraLanguageRow(
            source = source,
            target = target,
            onSourceSelected = { selected ->
                sourceName = selected.name
                if (selected.language == target) {
                    targetName = Language.entries.first { it != selected.language }.name
                }
            },
            onTargetSelected = { selected ->
                targetName = selected.name
                if (source.language == selected) {
                    sourceName = CameraSourceLanguage.AUTO.name
                }
            },
            onSwap = {
                val explicitSource = source.language
                if (explicitSource != null && explicitSource != target) {
                    sourceName = CameraSourceLanguage.from(target).name
                    targetName = explicitSource.name
                }
            },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(horizontal = 16.dp, vertical = 14.dp),
        )

        if (cameraError != null) {
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

        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(170.dp)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.68f)),
                    ),
                ),
        )

        CameraControls(
            hasCameraPermission = hasCameraPermission,
            captureReady = imageCapture != null,
            imageSelected = selectedImageUri != null,
            onGallery = { galleryLauncher.launch("image/*") },
            onCapture = {
                if (selectedImageUri != null) {
                    selectedImageUri = null
                    cameraError = null
                } else {
                    captureCameraPhoto(
                        context = context,
                        imageCapture = imageCapture,
                        onImageSelected = { uri ->
                            selectedImageUri = uri?.toString()
                            cameraError = null
                        },
                        onError = { cameraError = it },
                    )
                }
            },
            torchEnabled = torchEnabled,
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
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 24.dp, vertical = 18.dp),
        )
    }
}

@Composable
private fun CameraPermissionState(onRequestPermission: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Default.CameraAlt,
            contentDescription = null,
            modifier = Modifier.size(52.dp),
            tint = Color.White,
        )
        Spacer(Modifier.height(14.dp))
        Text(
            "Нужен доступ к камере",
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(12.dp))
        Button(onClick = onRequestPermission) {
            Text("Разрешить")
        }
    }
}

@Composable
private fun CameraControls(
    hasCameraPermission: Boolean,
    captureReady: Boolean,
    imageSelected: Boolean,
    torchEnabled: Boolean,
    torchAvailable: Boolean,
    onGallery: () -> Unit,
    onCapture: () -> Unit,
    onToggleTorch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        CameraOverlayAction(
            icon = Icons.Default.PhotoLibrary,
            label = "Галерея",
            contentDescription = "Выбрать изображение из галереи",
            onClick = onGallery,
        )

        Surface(
            modifier = Modifier
                .size(82.dp)
                .clickable(
                    enabled = imageSelected || (hasCameraPermission && captureReady),
                    onClick = onCapture,
                ),
            shape = CircleShape,
            color = Color.Transparent,
            border = BorderStroke(
                4.dp,
                if (imageSelected || (hasCameraPermission && captureReady)) {
                    Color.White
                } else {
                    Color.White.copy(alpha = 0.38f)
                },
            ),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Surface(
                    modifier = Modifier.size(64.dp),
                    shape = CircleShape,
                    color = if (imageSelected || (hasCameraPermission && captureReady)) {
                        Color.White
                    } else {
                        Color.White.copy(alpha = 0.42f)
                    },
                ) {}
            }
        }

        CameraOverlayAction(
            icon = if (torchEnabled) Icons.Default.FlashOff else Icons.Default.FlashOn,
            label = "Фонарик",
            contentDescription = if (torchEnabled) "Выключить фонарик" else "Включить фонарик",
            enabled = torchAvailable,
            active = torchEnabled,
            onClick = onToggleTorch,
        )
    }
}

@Composable
private fun CameraOverlayAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    contentDescription: String,
    enabled: Boolean = true,
    active: Boolean = false,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Surface(
            modifier = Modifier
                .size(54.dp)
                .clickable(enabled = enabled, onClick = onClick),
            shape = CircleShape,
            color = when {
                !enabled -> CameraOverlay.copy(alpha = 0.45f)
                active -> MaterialTheme.colorScheme.primary.copy(alpha = 0.92f)
                else -> CameraOverlay
            },
            contentColor = Color.White,
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.28f)),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    icon,
                    contentDescription = contentDescription,
                    modifier = Modifier.size(27.dp),
                )
            }
        }
        Text(
            label,
            color = Color.White,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun LiveCameraPreview(
    lifecycleOwner: androidx.lifecycle.LifecycleOwner,
    onCaptureReady: (ImageCapture?) -> Unit,
    onCameraReady: (Camera?) -> Unit,
    onError: (String) -> Unit,
) {
    val context = LocalContext.current
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }

    DisposableEffect(lifecycleOwner, previewView) {
        onCaptureReady(null)
        onCameraReady(null)
        val future = ProcessCameraProvider.getInstance(context)
        val listener = Runnable {
            runCatching {
                val cameraProvider = future.get()
                val selector = CameraSelector.DEFAULT_BACK_CAMERA
                check(cameraProvider.hasCamera(selector)) {
                    "Задняя камера недоступна на этом устройстве."
                }

                val preview = Preview.Builder().build().also {
                    it.surfaceProvider = previewView.surfaceProvider
                }
                val capture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()

                cameraProvider.unbindAll()
                val camera = cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    selector,
                    preview,
                    capture,
                )
                onCaptureReady(capture)
                onCameraReady(camera)
            }.onFailure { throwable ->
                onCaptureReady(null)
                onCameraReady(null)
                onError(throwable.message ?: "Не удалось открыть камеру.")
            }
        }
        future.addListener(listener, context.mainExecutor)

        onDispose {
            onCaptureReady(null)
            onCameraReady(null)
            if (future.isDone) {
                runCatching { future.get().unbindAll() }
            }
        }
    }

    AndroidView(
        factory = { previewView },
        modifier = Modifier.fillMaxSize(),
    )
}

private fun captureCameraPhoto(
    context: Context,
    imageCapture: ImageCapture?,
    onImageSelected: (Uri?) -> Unit,
    onError: (String) -> Unit,
) {
    val capture = imageCapture ?: return
    val directory = File(context.cacheDir, "camera").apply { mkdirs() }
    val photo = File(directory, "ling-${System.currentTimeMillis()}.jpg")
    val options = ImageCapture.OutputFileOptions.Builder(photo).build()

    capture.takePicture(
        options,
        context.mainExecutor,
        object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                onImageSelected(Uri.fromFile(photo))
            }

            override fun onError(exception: ImageCaptureException) {
                onError(exception.message ?: "Не удалось сохранить снимок.")
            }
        },
    )
}

@Composable
private fun SelectedCameraImage(uriString: String) {
    val context = LocalContext.current
    val bitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(
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

    val image = bitmap
    if (image != null) {
        Image(
            bitmap = image,
            contentDescription = "Выбранное изображение",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit,
        )
    } else {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Text("Загружаю изображение…", color = Color.White)
        }
    }
}

@Composable
private fun CameraLanguageRow(
    source: CameraSourceLanguage,
    target: Language,
    onSourceSelected: (CameraSourceLanguage) -> Unit,
    onTargetSelected: (Language) -> Unit,
    onSwap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        CameraSourcePicker(
            source = source,
            onSelected = onSourceSelected,
            modifier = Modifier.weight(1f),
        )

        Surface(
            modifier = Modifier
                .size(42.dp)
                .clickable(enabled = source.language != null, onClick = onSwap),
            shape = CircleShape,
            color = CameraOverlayStrong,
            contentColor = if (source.language != null) Color.White else Color.White.copy(alpha = 0.42f),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.16f)),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Default.SwapHoriz,
                    contentDescription = "Поменять языки",
                    modifier = Modifier.size(23.dp),
                )
            }
        }

        CameraTargetPicker(
            language = target,
            onSelected = onTargetSelected,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun CameraSourcePicker(
    source: CameraSourceLanguage,
    onSelected: (CameraSourceLanguage) -> Unit,
    modifier: Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        CameraLanguageSurface(
            symbol = source.symbol,
            title = source.title,
            onClick = { expanded = true },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            CameraSourceLanguage.entries.forEach { item ->
                DropdownMenuItem(
                    text = {
                        Text(
                            if (item == CameraSourceLanguage.AUTO) {
                                "${item.symbol}  Автоопределение"
                            } else {
                                "${item.symbol}  ${item.title}"
                            },
                        )
                    },
                    onClick = {
                        expanded = false
                        onSelected(item)
                    },
                )
            }
        }
    }
}

@Composable
private fun CameraTargetPicker(
    language: Language,
    onSelected: (Language) -> Unit,
    modifier: Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        CameraLanguageSurface(
            symbol = language.cameraFlag(),
            title = language.displayName,
            onClick = { expanded = true },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            Language.entries.forEach { item ->
                DropdownMenuItem(
                    text = { Text("${item.cameraFlag()}  ${item.displayName}") },
                    onClick = {
                        expanded = false
                        onSelected(item)
                    },
                )
            }
        }
    }
}

@Composable
private fun CameraLanguageSurface(
    symbol: String,
    title: String,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(50.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        color = CameraOverlayStrong,
        contentColor = Color.White,
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.16f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 15.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(symbol, style = MaterialTheme.typography.titleMedium)
            Text(
                title,
                modifier = Modifier
                    .padding(start = 7.dp)
                    .weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Icon(
                Icons.Default.ExpandMore,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = Color.White.copy(alpha = 0.72f),
            )
        }
    }
}

private fun Language.cameraFlag(): String = when (this) {
    Language.RUSSIAN -> "🇷🇺"
    Language.ENGLISH -> "🇬🇧"
    Language.CHINESE -> "🇨🇳"
}
