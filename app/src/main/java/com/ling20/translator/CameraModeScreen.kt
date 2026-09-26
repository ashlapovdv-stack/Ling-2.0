package com.ling20.translator

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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

private val CameraScreenBackground = Color(0xFFF5F8FC)
private val CameraCardBorder = Color(0xFFE3E8EF)

@Composable
internal fun CameraModeScreen(
    defaultSource: Language?,
    defaultTarget: Language,
) {
    val context = LocalContext.current
    var sourceName by rememberSaveable {
        mutableStateOf(
            if (defaultSource == defaultTarget) CameraSourceLanguage.AUTO.name
            else CameraSourceLanguage.from(defaultSource).name,
        )
    }
    var targetName by rememberSaveable { mutableStateOf(defaultTarget.name) }
    var selectedImageUri by rememberSaveable { mutableStateOf<String?>(null) }
    var cameraError by rememberSaveable { mutableStateOf<String?>(null) }

    val source = CameraSourceLanguage.valueOf(sourceName)
    val target = Language.valueOf(targetName)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
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
        )

        CameraCaptureCard(
            selectedImageUri = selectedImageUri,
            error = cameraError,
            onImageSelected = { uri ->
                selectedImageUri = uri?.toString()
                cameraError = null
            },
            onError = { cameraError = it },
        )

        if (selectedImageUri != null) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                color = Color(0xFFEAF2FF),
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        "Фото готово",
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        "Следующий этап — офлайн OCR: распознавание RU / EN / 中文, редактирование текста и перевод.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Spacer(Modifier.height(2.dp))
    }
}

@Composable
private fun CameraCaptureCard(
    selectedImageUri: String?,
    error: String?,
    onImageSelected: (Uri?) -> Unit,
    onError: (String) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var hasCameraPermission by remember {
        mutableStateOf(
            context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED,
        )
    }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasCameraPermission = granted
        if (!granted) onError("Для съёмки нужно разрешить доступ к камере.")
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri != null) onImageSelected(uri)
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.75f),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    selectedImageUri != null -> {
                        SelectedCameraImage(selectedImageUri)
                    }

                    hasCameraPermission -> {
                        LiveCameraPreview(
                            lifecycleOwner = lifecycleOwner,
                            onCaptureReady = { imageCapture = it },
                            onError = onError,
                        )
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth(0.80f)
                                .aspectRatio(0.80f),
                            shape = RoundedCornerShape(22.dp),
                            color = Color.Transparent,
                            border = BorderStroke(2.dp, Color.White.copy(alpha = 0.78f)),
                        ) {}
                    }

                    else -> {
                        Surface(
                            modifier = Modifier.fillMaxSize(),
                            shape = RoundedCornerShape(20.dp),
                            color = Color(0xFFF0F4F9),
                        ) {
                            Column(
                                modifier = Modifier.padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                            ) {
                                Icon(
                                    Icons.Default.CameraAlt,
                                    contentDescription = null,
                                    modifier = Modifier.size(44.dp),
                                    tint = Color(0xFF667085),
                                )
                                Spacer(Modifier.height(12.dp))
                                Text(
                                    "Нужен доступ к камере",
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Spacer(Modifier.height(10.dp))
                                Button(onClick = {
                                    permissionLauncher.launch(Manifest.permission.CAMERA)
                                }) {
                                    Text("Разрешить")
                                }
                            }
                        }
                    }
                }
            }

            if (error != null) {
                Text(
                    error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 6.dp),
                )
            }

            if (selectedImageUri == null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    CameraRoundAction(
                        icon = Icons.Default.PhotoLibrary,
                        contentDescription = "Выбрать изображение из галереи",
                        onClick = { galleryLauncher.launch("image/*") },
                    )

                    Surface(
                        modifier = Modifier
                            .size(76.dp)
                            .clickable(enabled = hasCameraPermission && imageCapture != null) {
                                captureCameraPhoto(
                                    context = context,
                                    imageCapture = imageCapture,
                                    onImageSelected = onImageSelected,
                                    onError = onError,
                                )
                            },
                        shape = CircleShape,
                        color = if (hasCameraPermission && imageCapture != null) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            Color(0xFFD0D5DD)
                        },
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Surface(
                                modifier = Modifier.size(58.dp),
                                shape = CircleShape,
                                color = Color.White,
                            ) {}
                        }
                    }

                    Box(Modifier.size(48.dp))
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Button(
                        onClick = { onImageSelected(null) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                        Text("  Переснять")
                    }
                    CameraRoundAction(
                        icon = Icons.Default.PhotoLibrary,
                        contentDescription = "Выбрать другое изображение",
                        onClick = { galleryLauncher.launch("image/*") },
                    )
                }
            }
        }
    }
}

@Composable
private fun LiveCameraPreview(
    lifecycleOwner: androidx.lifecycle.LifecycleOwner,
    onCaptureReady: (ImageCapture) -> Unit,
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
        val future = ProcessCameraProvider.getInstance(context)
        val listener = Runnable {
            runCatching {
                val cameraProvider = future.get()
                val preview = Preview.Builder().build().also {
                    it.surfaceProvider = previewView.surfaceProvider
                }
                val capture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()

                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    capture,
                )
                onCaptureReady(capture)
            }.onFailure { throwable ->
                onError(throwable.message ?: "Не удалось открыть камеру.")
            }
        }
        future.addListener(listener, context.mainExecutor)

        onDispose {
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

    Surface(
        modifier = Modifier.fillMaxSize(),
        shape = RoundedCornerShape(20.dp),
        color = Color(0xFF101828),
    ) {
        val image = bitmap
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = "Выбранное изображение",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        } else {
            Box(contentAlignment = Alignment.Center) {
                Text("Загружаю изображение…", color = Color.White)
            }
        }
    }
}

@Composable
private fun CameraRoundAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .size(48.dp)
            .clickable(onClick = onClick),
        shape = CircleShape,
        color = Color(0xFFF0F4F9),
        contentColor = Color(0xFF667085),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = contentDescription)
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
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        CameraSourcePicker(
            source = source,
            onSelected = onSourceSelected,
            modifier = Modifier.weight(1f),
        )
        Surface(
            modifier = Modifier
                .size(46.dp)
                .clickable(enabled = source.language != null, onClick = onSwap),
            shape = CircleShape,
            color = Color(0xFFE5EEFC),
            contentColor = if (source.language != null) {
                MaterialTheme.colorScheme.primary
            } else {
                Color(0xFF98A2B3)
            },
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Default.SwapHoriz, contentDescription = "Поменять языки")
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
            .height(52.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(17.dp),
        color = Color.White,
        border = BorderStroke(1.dp, CameraCardBorder),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 13.dp),
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
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Icon(
                Icons.Default.ExpandMore,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun Language.cameraFlag(): String = when (this) {
    Language.RUSSIAN -> "🇷🇺"
    Language.ENGLISH -> "🇬🇧"
    Language.CHINESE -> "🇨🇳"
}
