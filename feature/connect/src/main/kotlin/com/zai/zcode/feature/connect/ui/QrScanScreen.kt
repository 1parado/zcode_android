package com.zai.zcode.feature.connect.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 扫码配对页：全屏相机预览 + MLKit QR 识别。
 * 识别到内容后回调 [onScanned] 并自动关闭（消费一次，防止连扫）。
 * 相机权限在页内请求：拒绝时给文案，不闪退。
 */
@Composable
fun QrScanScreen(
    onClose: () -> Unit,
    onScanned: (String) -> Unit,
) {
    // 修复依据(run 39017131812):LocalContext.current 是 @Composable,不能在 remember{} 内调用,先在外层取
    val context = LocalContext.current
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var permissionDenied by remember { mutableStateOf(false) }
    var cameraErrorText by remember { mutableStateOf<String?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasPermission = granted
        permissionDenied = !granted
    }
    LaunchedEffect(Unit) {
        if (!hasPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    BackHandler(onBack = onClose)

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (hasPermission) {
            QrCameraPreview(
                onScanned = { raw ->
                    onScanned(raw)
                    onClose()
                },
                onCameraError = { message ->
                    // 相机打开失败（被占用/无摄像头）：展示错误并保持可退出
                    cameraErrorText = message
                },
            )
        }
        // 顶栏
        Surface(
            modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth().statusBarsPadding(),
            color = Color.Black.copy(alpha = 0.55f),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("扫码配对", style = MaterialTheme.typography.titleSmall, color = Color.White)
                    Text(
                        "对准电脑端 ZCode 显示的配对二维码",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.7f),
                    )
                }
                TextButton(onClick = onClose) { Text("关闭", color = Color.White) }
            }
        }
        // 取景框
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .size(240.dp)
                .background(Color.Transparent, RoundedCornerShape(16.dp)),
        )
        if (permissionDenied || cameraErrorText != null) {
            Surface(
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(16.dp),
                color = MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(12.dp),
            ) {
                Text(
                    text = cameraErrorText ?: "相机权限被拒绝，请在系统设置中允许 ZCode 使用相机后重试",
                    modifier = Modifier.padding(14.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun QrCameraPreview(
    onScanned: (String) -> Unit,
    onCameraError: (String) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scanner = remember {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
        )
    }
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    val consumed = remember { AtomicBoolean(false) }
    var provider by remember { mutableStateOf<ProcessCameraProvider?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            runCatching { provider?.unbindAll() }
            scanner.close()
            cameraExecutor.shutdown()
        }
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            PreviewView(ctx).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                val future = ProcessCameraProvider.getInstance(ctx)
                future.addListener({
                    val p = runCatching { future.get() }.getOrElse {
                        onCameraError(it.message ?: "相机初始化失败")
                        return@addListener
                    }
                    provider = p
                    val preview = Preview.Builder().build().also { it.surfaceProvider = this.surfaceProvider }
                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                        .also {
                            it.setAnalyzer(cameraExecutor) { proxy ->
                                analyzeQr(scanner, proxy, consumed) { raw ->
                                    // 回主线程回调，避免在 analyzer 线程触发重组
                                    post { onScanned(raw) }
                                }
                            }
                        }
                    runCatching {
                        p.unbindAll()
                        p.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                    }.onFailure { onCameraError(it.message ?: "相机启动失败") }
                }, ContextCompat.getMainExecutor(ctx))
            }
        },
    )
}

private fun analyzeQr(
    scanner: BarcodeScanner,
    proxy: ImageProxy,
    consumed: AtomicBoolean,
    onScanned: (String) -> Unit,
) {
    if (consumed.get()) { proxy.close(); return }
    val media = proxy.image
    if (media == null) { proxy.close(); return }
    val image = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
    scanner.process(image)
        .addOnSuccessListener { codes ->
            val raw = codes.firstNotNullOfOrNull { it.rawValue?.takeIf { v -> v.isNotBlank() } }
            if (raw != null && consumed.compareAndSet(false, true)) onScanned(raw)
        }
        .addOnCompleteListener { proxy.close() }
}
