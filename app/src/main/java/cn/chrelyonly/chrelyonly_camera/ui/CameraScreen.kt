package cn.chrelyonly.chrelyonly_camera.ui

import android.Manifest
import android.graphics.Bitmap
import android.graphics.Matrix
import android.widget.Toast
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import cn.chrelyonly.chrelyonly_camera.model.WatermarkConfig
import cn.chrelyonly.chrelyonly_camera.utils.WatermarkUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun CameraScreen(viewModel: CameraViewModel = viewModel()) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val permissionsState = rememberMultiplePermissionsState(
        permissions = listOf(
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
    )

    LaunchedEffect(Unit) {
        if (!permissionsState.allPermissionsGranted) {
            permissionsState.launchMultiplePermissionRequest()
        }
    }

    LaunchedEffect(permissionsState.allPermissionsGranted) {
        if (permissionsState.allPermissionsGranted) {
            viewModel.refreshLocation()
            viewModel.refreshWeather()
        }
    }

    if (!permissionsState.allPermissionsGranted) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(24.dp)
            ) {
                Text(
                    text = "莓莓水印相机需要相机与定位权限以正常工作",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = { permissionsState.launchMultiplePermissionRequest() },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White),
                    shape = RoundedCornerShape(24.dp)
                ) {
                    Text("立即授权", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            }
        }
        return
    }

    val watermarkConfig by viewModel.watermarkConfig.collectAsState()
    val locationText by viewModel.locationText.collectAsState()
    val customLocationText by viewModel.customLocationText.collectAsState()
    val flashMode by viewModel.flashMode.collectAsState()
    val lensFacing by viewModel.lensFacing.collectAsState()
    val showGrid by viewModel.showGrid.collectAsState()
    val timerSeconds by viewModel.timerSeconds.collectAsState()
    val lastCapturedUri by viewModel.lastCapturedUri.collectAsState()

    var showSettingsDialog by remember { mutableStateOf(false) }
    var showLocationDialog by remember { mutableStateOf(false) }
    var currentTime by remember { mutableStateOf(SimpleDateFormat("yyyy-MM-dd HH:mm:ss 星期E", Locale.CHINA).format(Date())) }
    var zoomRatio by remember { mutableStateOf(1f) }
    var isCapturingAnimation by remember { mutableStateOf(false) }
    var countdownValue by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        while (true) {
            currentTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss 星期E", Locale.CHINA).format(Date())
            delay(1000)
        }
    }

    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var cameraControl by remember { mutableStateOf<CameraControl?>(null) }
    val previewView = remember { PreviewView(context) }

    LaunchedEffect(lensFacing, flashMode) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            val capture = ImageCapture.Builder()
                .setFlashMode(
                    when (flashMode) {
                        1 -> ImageCapture.FLASH_MODE_ON
                        2 -> ImageCapture.FLASH_MODE_AUTO
                        else -> ImageCapture.FLASH_MODE_OFF
                    }
                )
                .build()
            imageCapture = capture

            val selector = CameraSelector.Builder()
                .requireLensFacing(lensFacing)
                .build()

            try {
                cameraProvider.unbindAll()
                val camera = cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    selector,
                    preview,
                    capture
                )
                cameraControl = camera.cameraControl
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }, ContextCompat.getMainExecutor(context))
    }

    val triggerCapture = {
        imageCapture?.let { capture ->
            isCapturingAnimation = true
            capture.takePicture(
                cameraExecutor,
                object : ImageCapture.OnImageCapturedCallback() {
                    override fun onError(exception: ImageCaptureException) {
                        ContextCompat.getMainExecutor(context).execute {
                            Toast.makeText(context, "拍照失败: ${exception.message}", Toast.LENGTH_SHORT).show()
                        }
                    }

                    override fun onCaptureSuccess(image: ImageProxy) {
                        val bitmap = imageProxyToBitmap(image)
                        image.close()

                        if (bitmap != null) {
                            val finalBitmap = if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
                                val matrix = Matrix().apply { preScale(-1f, 1f) }
                                Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                            } else {
                                bitmap
                            }

                            val savedUri = WatermarkUtils.applyWatermarkAndSave(
                                context,
                                finalBitmap,
                                watermarkConfig,
                                locationText
                            )

                            ContextCompat.getMainExecutor(context).execute {
                                if (savedUri != null) {
                                    viewModel.setLastCapturedUri(savedUri)
                                    Toast.makeText(context, "莓莓水印相机照片已保存", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, "保存失败", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    }
                }
            )
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize()
        )

        if (countdownValue > 0) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.4f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "$countdownValue",
                    color = Color.Yellow,
                    fontSize = 90.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        if (isCapturingAnimation) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.White.copy(alpha = 0.8f))
            )
            LaunchedEffect(isCapturingAnimation) {
                delay(100)
                isCapturingAnimation = false
            }
        }

        if (showGrid) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val thirdWidth = size.width / 3f
                val thirdHeight = size.height / 3f
                drawLine(Color.White.copy(alpha = 0.3f), Offset(thirdWidth, 0f), Offset(thirdWidth, size.height), 1.5f)
                drawLine(Color.White.copy(alpha = 0.3f), Offset(thirdWidth * 2, 0f), Offset(thirdWidth * 2, size.height), 1.5f)
                drawLine(Color.White.copy(alpha = 0.3f), Offset(0f, thirdHeight), Offset(size.width, thirdHeight), 1.5f)
                drawLine(Color.White.copy(alpha = 0.3f), Offset(0f, thirdHeight * 2), Offset(size.width, thirdHeight * 2), 1.5f)
            }
        }

        val density = LocalDensity.current
        val configuration = LocalConfiguration.current
        val screenWidthPx = with(density) { configuration.screenWidthDp.dp.toPx() }
        val screenHeightPx = with(density) { configuration.screenHeightDp.dp.toPx() }

        val cardWidthPx = with(density) { 220.dp.toPx() }
        val cardHeightPx = with(density) { 150.dp.toPx() }

        val maxX = (screenWidthPx - cardWidthPx).coerceAtLeast(0f)
        val maxY = (screenHeightPx - cardHeightPx).coerceAtLeast(0f)

        val defaultX = 24f
        val defaultY = screenHeightPx - cardHeightPx - 140f

        var offsetX by remember {
            mutableStateOf(
                if (watermarkConfig.ratioX >= 0f) {
                    watermarkConfig.ratioX * maxX
                } else {
                    defaultX
                }
            )
        }
        var offsetY by remember {
            mutableStateOf(
                if (watermarkConfig.ratioY >= 0f) {
                    watermarkConfig.ratioY * maxY
                } else {
                    defaultY
                }
            )
        }

        LaunchedEffect(watermarkConfig.ratioX, watermarkConfig.ratioY) {
            if (watermarkConfig.ratioX >= 0f) {
                offsetX = watermarkConfig.ratioX * maxX
                offsetY = watermarkConfig.ratioY * maxY
            }
        }

        Box(
            modifier = Modifier.fillMaxSize()
        ) {
            Surface(
                color = Color.Black.copy(alpha = 0.5f),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .graphicsLayer {
                        translationX = offsetX
                        translationY = offsetY
                    }
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragEnd = {
                                val snappedX = offsetX.coerceIn(0f, maxX)
                                val snappedY = offsetY.coerceIn(0f, maxY)

                                offsetX = snappedX
                                offsetY = snappedY

                                val ratioX = if (maxX > 0f) (snappedX / maxX).coerceIn(0f, 1f) else 0.05f
                                val ratioY = if (maxY > 0f) (snappedY / maxY).coerceIn(0f, 1f) else 0.85f
                                viewModel.updateWatermarkDrag(ratioX, ratioY)
                            }
                        ) { change, dragAmount ->
                            change.consume()
                            offsetX = (offsetX + dragAmount.x).coerceIn(0f, maxX)
                            offsetY = (offsetY + dragAmount.y).coerceIn(0f, maxY)
                        }
                    }
                    .border(0.5.dp, Color.White.copy(alpha = 0.25f), RoundedCornerShape(12.dp))
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    if (watermarkConfig.showTime) {
                        Text(
                            text = "🕒 $currentTime",
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    if (watermarkConfig.showLocation) {
                        Text(
                            text = "📍 $locationText",
                            color = Color.White.copy(alpha = 0.95f),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (watermarkConfig.showWeather && watermarkConfig.weatherText.isNotEmpty()) {
                        Text(
                            text = watermarkConfig.weatherText,
                            color = Color.Cyan.copy(alpha = 0.95f),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    if (watermarkConfig.showCustomText && watermarkConfig.customText.isNotEmpty()) {
                        Text(
                            text = "🏷️ ${watermarkConfig.customText}",
                            color = Color.Yellow.copy(alpha = 0.95f),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 28.dp, start = 20.dp, end = 20.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                color = Color.Black.copy(alpha = 0.4f),
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.border(0.5.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(24.dp))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { viewModel.toggleFlash() },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = when (flashMode) {
                                1 -> Icons.Default.FlashOn
                                2 -> Icons.Default.FlashAuto
                                else -> Icons.Default.FlashOff
                            },
                            contentDescription = "Flash",
                            tint = if (flashMode > 0) Color.Yellow else Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    IconButton(
                        onClick = { viewModel.toggleGrid() },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.GridOn,
                            contentDescription = "Grid",
                            tint = if (showGrid) Color.Yellow else Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    IconButton(
                        onClick = { viewModel.toggleTimer() },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Timer,
                                contentDescription = "Timer",
                                tint = if (timerSeconds > 0) Color.Yellow else Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                            if (timerSeconds > 0) {
                                Text(
                                    text = "${timerSeconds}s",
                                    color = Color.Yellow,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.align(Alignment.BottomEnd)
                                )
                            }
                        }
                    }

                    IconButton(
                        onClick = { showSettingsDialog = true },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Tune,
                            contentDescription = "Watermark Settings",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    IconButton(
                        onClick = { showLocationDialog = true },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.LocationOn,
                            contentDescription = "Location Picker",
                            tint = if (!customLocationText.isNullOrEmpty()) Color.Green else Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(bottom = 120.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                color = Color.Black.copy(alpha = 0.5f),
                shape = RoundedCornerShape(16.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    listOf(0.5f, 1f, 2f).forEach { ratio ->
                        Text(
                            text = "${ratio}x",
                            color = if (zoomRatio == ratio) Color.Yellow else Color.White,
                            fontSize = 13.sp,
                            fontWeight = if (zoomRatio == ratio) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier
                                .clickable {
                                    zoomRatio = ratio
                                    cameraControl?.setZoomRatio(ratio)
                                }
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .background(Color.Black.copy(alpha = 0.35f))
                .padding(bottom = 24.dp, top = 16.dp, start = 32.dp, end = 32.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                GalleryThumbnail(lastCapturedUri = lastCapturedUri) {
                    lastCapturedUri?.let { uri ->
                        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, "image/*")
                            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        try {
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            Toast.makeText(context, "无法打开相册", Toast.LENGTH_SHORT).show()
                        }
                    } ?: Toast.makeText(context, "暂无照片", Toast.LENGTH_SHORT).show()
                }

                Box(
                    modifier = Modifier
                        .size(76.dp)
                        .clip(CircleShape)
                        .border(4.dp, Color.White, CircleShape)
                        .background(Color.Transparent)
                        .clickable {
                            if (countdownValue > 0) return@clickable
                            if (timerSeconds > 0) {
                                countdownValue = timerSeconds
                                CoroutineScope(Dispatchers.Main).launch {
                                    while (countdownValue > 0) {
                                        delay(1000)
                                        if (countdownValue > 1) {
                                            countdownValue--
                                        } else {
                                            countdownValue = 0
                                            triggerCapture()
                                        }
                                    }
                                }
                            } else {
                                triggerCapture()
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(62.dp)
                            .clip(CircleShape)
                            .background(Color.White)
                    )
                }

                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.4f))
                        .border(0.5.dp, Color.White.copy(alpha = 0.3f), CircleShape)
                        .clickable { viewModel.toggleCamera() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Cameraswitch,
                        contentDescription = "Switch Camera",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
    }

    if (showSettingsDialog) {
        WatermarkSettingsDialog(
            config = watermarkConfig,
            onDismiss = { showSettingsDialog = false },
            onSave = { newConfig ->
                viewModel.updateWatermarkConfig(newConfig)
                showSettingsDialog = false
            }
        )
    }

    if (showLocationDialog) {
        LocationPickerDialog(
            currentCustomLocation = customLocationText,
            onDismiss = { showLocationDialog = false },
            onConfirm = { customLoc ->
                viewModel.setCustomLocation(customLoc)
                showLocationDialog = false
                Toast.makeText(context, if (customLoc.isNullOrEmpty()) "已切换为实时GPS定位" else "已设定自定义定位", Toast.LENGTH_SHORT).show()
            },
            onRefreshGps = {
                viewModel.setCustomLocation(null)
                viewModel.refreshLocation()
                showLocationDialog = false
                Toast.makeText(context, "正在重新获取实时GPS定位...", Toast.LENGTH_SHORT).show()
            }
        )
    }
}

@Composable
fun LocationPickerDialog(
    currentCustomLocation: String?,
    onDismiss: () -> Unit,
    onConfirm: (String?) -> Unit,
    onRefreshGps: () -> Unit
) {
    var locationInput by remember { mutableStateOf(currentCustomLocation ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1C1C1E),
        titleContentColor = Color.White,
        textContentColor = Color.White,
        title = { Text("选择定位位置", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text("您可以输入自定义的地点名称（如：公司、景区、城市等），或使用实时 GPS 定位。", color = Color.LightGray, fontSize = 13.sp)

                OutlinedTextField(
                    value = locationInput,
                    onValueChange = { locationInput = it },
                    label = { Text("自定义定位地点", color = Color.LightGray) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF34C759),
                        unfocusedBorderColor = Color.Gray,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Button(
                    onClick = onRefreshGps,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2C2C2E)),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.MyLocation, contentDescription = "GPS", tint = Color.Green, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("使用实时GPS自动定位", color = Color.White)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(locationInput.ifEmpty { null }) },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF34C759))
            ) {
                Text("确定", color = Color.White, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = Color.LightGray)
            }
        }
    )
}

@Composable
fun GalleryThumbnail(lastCapturedUri: android.net.Uri?, onClick: () -> Unit) {
    val context = LocalContext.current
    var thumbnailBitmap by remember(lastCapturedUri) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(lastCapturedUri) {
        if (lastCapturedUri != null) {
            try {
                val bitmap = context.contentResolver.loadThumbnail(lastCapturedUri, android.util.Size(120, 120), null)
                thumbnailBitmap = bitmap
            } catch (e: Exception) {
                thumbnailBitmap = null
            }
        } else {
            thumbnailBitmap = null
        }
    }

    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(10.dp))
            .border(1.5.dp, Color.White.copy(alpha = 0.8f), RoundedCornerShape(10.dp))
            .background(Color.DarkGray)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (thumbnailBitmap != null) {
            Image(
                bitmap = thumbnailBitmap!!.asImageBitmap(),
                contentDescription = "Last Photo Thumbnail",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            Icon(Icons.Default.Photo, contentDescription = "Gallery", tint = Color.White, modifier = Modifier.size(24.dp))
        }
    }
}

fun imageProxyToBitmap(image: ImageProxy): Bitmap? {
    val planeProxy = image.planes[0]
    val buffer = planeProxy.buffer
    val bytes = ByteArray(buffer.remaining())
    buffer.get(bytes)
    val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

    val rotationDegrees = image.imageInfo.rotationDegrees
    if (rotationDegrees != 0) {
        val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }
    return bitmap
}

@Composable
fun WatermarkSettingsDialog(
    config: WatermarkConfig,
    onDismiss: () -> Unit,
    onSave: (WatermarkConfig) -> Unit
) {
    var showTime by remember { mutableStateOf(config.showTime) }
    var showLocation by remember { mutableStateOf(config.showLocation) }
    var showWeather by remember { mutableStateOf(config.showWeather) }
    var weatherText by remember { mutableStateOf(config.weatherText) }
    var showCustomText by remember { mutableStateOf(config.showCustomText) }
    var customText by remember { mutableStateOf(config.customText) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1C1C1E),
        titleContentColor = Color.White,
        textContentColor = Color.White,
        title = { Text("莓莓水印设置", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("显示时间与星期", color = Color.White)
                    Switch(
                        checked = showTime,
                        onCheckedChange = { showTime = it },
                        colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Color(0xFF34C759))
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("显示位置水印", color = Color.White)
                    Switch(
                        checked = showLocation,
                        onCheckedChange = { showLocation = it },
                        colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Color(0xFF34C759))
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("显示天气水印", color = Color.White)
                    Switch(
                        checked = showWeather,
                        onCheckedChange = { showWeather = it },
                        colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Color(0xFF34C759))
                    )
                }

                if (showWeather) {
                    OutlinedTextField(
                        value = weatherText,
                        onValueChange = { weatherText = it },
                        label = { Text("天气文字", color = Color.LightGray) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF34C759),
                            unfocusedBorderColor = Color.Gray,
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("显示自定义文字", color = Color.White)
                    Switch(
                        checked = showCustomText,
                        onCheckedChange = { showCustomText = it },
                        colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Color(0xFF34C759))
                    )
                }

                if (showCustomText) {
                    OutlinedTextField(
                        value = customText,
                        onValueChange = { customText = it },
                        label = { Text("自定义文字", color = Color.LightGray) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF34C759),
                            unfocusedBorderColor = Color.Gray,
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Button(
                    onClick = {
                        onSave(
                            WatermarkConfig(
                                showTime = showTime,
                                showLocation = showLocation,
                                showWeather = showWeather,
                                weatherText = weatherText,
                                showCustomText = showCustomText,
                                customText = customText,
                                ratioX = 0.05f,
                                ratioY = 0.85f,
                                isDragged = false
                            )
                        )
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2C2C2E)),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = "Reset", tint = Color.Green, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("恢复默认位置（左下角）", color = Color.White)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        WatermarkConfig(
                            showTime = showTime,
                            showLocation = showLocation,
                            showWeather = showWeather,
                            weatherText = weatherText,
                            showCustomText = showCustomText,
                            customText = customText,
                            ratioX = config.ratioX,
                            ratioY = config.ratioY,
                            isDragged = config.isDragged
                        )
                    )
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF34C759))
            ) {
                Text("完成", color = Color.White, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = Color.LightGray)
            }
        }
    )
}
