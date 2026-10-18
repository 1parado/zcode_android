package com.zai.zcode.ui

import android.annotation.SuppressLint
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.zai.zcode.BuildConfig
import com.zai.zcode.core.design.LocalZcodeColors
import com.zai.zcode.core.design.ZcodeShape
import com.zai.zcode.core.design.ZcodeSpacing
import com.zai.zcode.core.design.ZcodeType
import com.zai.zcode.core.design.jellyClickable
import com.zai.zcode.R
import com.zai.zcode.remote.RemoteControlRecentLink
import com.zai.zcode.remote.RemoteControlUrlPolicy
import com.zai.zcode.remote.displaySubtitle
import com.zai.zcode.remote.displayTitle
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

@Composable
fun RemoteControlApp(
    remoteUrl: String?,
    recentLinks: List<RemoteControlRecentLink>,
    modifier: Modifier = Modifier,
    onOpenUrl: (String) -> Boolean,
    onCloseRemote: () -> Unit,
    onClearRecentLinks: () -> Unit,
) {
    val context = LocalContext.current
    var scannerOpen by rememberSaveable { mutableStateOf(false) }
    var launcherError by rememberSaveable { mutableStateOf<String?>(null) }
    val cameraPermissionDenied = stringResource(R.string.remote_scan_permission_denied)
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            launcherError = null
            scannerOpen = true
        } else {
            launcherError = cameraPermissionDenied
        }
    }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = LocalZcodeColors.current.base,
        contentColor = LocalZcodeColors.current.text,
    ) {
        if (scannerOpen) {
            RemoteControlQrScanner(
                onClose = {
                    scannerOpen = false
                },
                onScanned = { raw ->
                    val opened = onOpenUrl(raw)
                    if (opened) {
                        scannerOpen = false
                        launcherError = null
                    } else {
                        launcherError = context.getString(R.string.remote_scan_invalid)
                    }
                    opened
                },
            )
        } else if (remoteUrl == null) {
            RemoteControlLauncher(
                recentLinks = recentLinks,
                errorMessage = launcherError,
                onOpenUrl = { raw ->
                    val opened = onOpenUrl(raw)
                    if (opened) {
                        launcherError = null
                    }
                    opened
                },
                onScanQrCode = {
                    if (
                        ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.CAMERA,
                        ) == PackageManager.PERMISSION_GRANTED
                    ) {
                        launcherError = null
                        scannerOpen = true
                    } else {
                        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                    }
                },
                onClearRecentLinks = onClearRecentLinks,
            )
        } else {
            RemoteControlWebViewScreen(
                remoteUrl = remoteUrl,
                onCloseRemote = onCloseRemote,
            )
        }
    }
}

@Composable
private fun RemoteControlQrScanner(
    onClose: () -> Unit,
    onScanned: (String) -> Boolean,
) {
    var scanError by rememberSaveable { mutableStateOf<String?>(null) }
    val zc = LocalZcodeColors.current

    BackHandler(onBack = onClose)

    Box(modifier = Modifier.fillMaxSize()) {
        RemoteQrCameraPreview(
            onScanned = onScanned,
            onCameraError = {
                scanError = it
            },
        )
        // 修复依据（v0.2.26）：与 QrScanScreen(v0.2.24) 同语言——相机画面上浮黑色
        // 半透明压底保证可读，字阶走 ZcodeType，不再用 Material surface 色板。
        Surface(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .statusBarsPadding(),
            color = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.55f),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .padding(horizontal = ZcodeSpacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.remote_scan_title),
                        style = ZcodeType.uiMd,
                        color = androidx.compose.ui.graphics.Color.White,
                    )
                    Text(
                        text = stringResource(R.string.remote_scan_hint),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = ZcodeType.uiXs,
                        color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.7f),
                    )
                }
                TextButton(onClick = onClose) {
                    Text(stringResource(R.string.remote_close_button), color = androidx.compose.ui.graphics.Color.White)
                }
            }
        }
        scanError?.let { message ->
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(ZcodeSpacing.screenPadding),
                color = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.82f),
                shape = ZcodeShape.card,
            ) {
                Text(
                    text = message,
                    modifier = Modifier.padding(ZcodeSpacing.md),
                    style = ZcodeType.uiSm,
                    color = zc.error,
                )
            }
        }
    }
}

@Composable
private fun RemoteQrCameraPreview(
    onScanned: (String) -> Boolean,
    onCameraError: (String) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scanner = remember {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build(),
        )
    }
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    val consumed = remember { AtomicBoolean(false) }
    val disposed = remember { AtomicBoolean(false) }
    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    val invalidQrMessage = stringResource(R.string.remote_scan_invalid)

    DisposableEffect(Unit) {
        onDispose {
            disposed.set(true)
            cameraProvider?.unbindAll()
            scanner.close()
            cameraExecutor.shutdown()
        }
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { viewContext ->
            PreviewView(viewContext).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                bindRemoteQrCamera(
                    context = context,
                    lifecycleOwner = lifecycleOwner,
                    previewView = this,
                    scanner = scanner,
                    cameraExecutor = cameraExecutor,
                    consumed = consumed,
                    disposed = disposed,
                    onProviderReady = { provider ->
                        cameraProvider = provider
                    },
                    onScanned = { raw ->
                        val accepted = onScanned(raw)
                        if (!accepted) {
                            onCameraError(invalidQrMessage)
                            consumed.set(false)
                        }
                    },
                    onCameraError = onCameraError,
                )
            }
        },
    )
}

private fun bindRemoteQrCamera(
    context: Context,
    lifecycleOwner: LifecycleOwner,
    previewView: PreviewView,
    scanner: BarcodeScanner,
    cameraExecutor: ExecutorService,
    consumed: AtomicBoolean,
    disposed: AtomicBoolean,
    onProviderReady: (ProcessCameraProvider) -> Unit,
    onScanned: (String) -> Unit,
    onCameraError: (String) -> Unit,
) {
    val providerFuture = ProcessCameraProvider.getInstance(context)
    providerFuture.addListener(
        {
            val provider = runCatching { providerFuture.get() }
                .getOrElse { error ->
                    previewView.post {
                        onCameraError(error.message ?: context.getString(R.string.remote_scan_camera_failed))
                    }
                    return@addListener
                }
            if (disposed.get()) {
                provider.unbindAll()
                return@addListener
            }

            onProviderReady(provider)
            val preview = Preview.Builder().build().also {
                it.surfaceProvider = previewView.surfaceProvider
            }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { imageAnalysis ->
                    imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                        analyzeRemoteQrImage(
                            scanner = scanner,
                            imageProxy = imageProxy,
                            consumed = consumed,
                            previewView = previewView,
                            onScanned = onScanned,
                        )
                    }
                }

            runCatching {
                provider.unbindAll()
                provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis,
                )
            }.onFailure { error ->
                previewView.post {
                    onCameraError(error.message ?: context.getString(R.string.remote_scan_camera_failed))
                }
            }
        },
        ContextCompat.getMainExecutor(context),
    )
}

private fun analyzeRemoteQrImage(
    scanner: BarcodeScanner,
    imageProxy: ImageProxy,
    consumed: AtomicBoolean,
    previewView: PreviewView,
    onScanned: (String) -> Unit,
) {
    if (consumed.get()) {
        imageProxy.close()
        return
    }

    val mediaImage = imageProxy.image
    if (mediaImage == null) {
        imageProxy.close()
        return
    }

    val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
    scanner.process(image)
        .addOnSuccessListener { barcodes ->
            val rawValue = barcodes.firstNotNullOfOrNull { barcode ->
                barcode.rawValue?.takeIf { it.isNotBlank() }
            }
            if (rawValue != null && consumed.compareAndSet(false, true)) {
                previewView.post {
                    onScanned(rawValue)
                }
            }
        }
        .addOnCompleteListener {
            imageProxy.close()
        }
}

@Composable
private fun RemoteControlLauncher(
    recentLinks: List<RemoteControlRecentLink>,
    errorMessage: String?,
    onOpenUrl: (String) -> Boolean,
    onScanQrCode: () -> Unit,
    onClearRecentLinks: () -> Unit,
) {
    val context = LocalContext.current
    val zc = LocalZcodeColors.current
    var input by rememberSaveable { mutableStateOf("") }
    var localError by rememberSaveable { mutableStateOf<String?>(null) }
    val invalidUrlMessage = stringResource(R.string.remote_url_invalid)
    val visibleError = localError ?: errorMessage

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(ZcodeSpacing.xl),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.remote_launcher_title),
            style = ZcodeType.uiXl,
            color = zc.text,
        )
        Spacer(modifier = Modifier.height(ZcodeSpacing.sm))
        Text(
            text = stringResource(R.string.remote_launcher_description),
            style = ZcodeType.uiMd,
            color = zc.textSecondary,
        )
        Spacer(modifier = Modifier.height(ZcodeSpacing.xl))
        OutlinedTextField(
            value = input,
            onValueChange = {
                input = it
                localError = null
            },
            modifier = Modifier.fillMaxWidth(),
            singleLine = false,
            minLines = 2,
            shape = ZcodeShape.input,
            textStyle = ZcodeType.uiMd,
            label = { Text(stringResource(R.string.remote_url_label), style = ZcodeType.uiXs, color = zc.textTertiary) },
            placeholder = { Text(stringResource(R.string.remote_url_placeholder), style = ZcodeType.uiSm, color = zc.textTertiary) },
            supportingText = {
                visibleError?.let {
                    Text(it, style = ZcodeType.uiXs, color = zc.error)
                }
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = androidx.compose.ui.graphics.Color.Transparent,
                unfocusedBorderColor = androidx.compose.ui.graphics.Color.Transparent,
                focusedContainerColor = zc.input,
                unfocusedContainerColor = zc.input,
                focusedTextColor = zc.text,
                unfocusedTextColor = zc.text,
                cursorColor = zc.text,
            ),
        )
        Spacer(modifier = Modifier.height(ZcodeSpacing.md))
        PrimaryButton(label = stringResource(R.string.remote_open_button), onClick = {
            if (!onOpenUrl(input)) {
                localError = invalidUrlMessage
            }
        })
        Spacer(modifier = Modifier.height(ZcodeSpacing.sm))
        SecondaryButton(label = stringResource(R.string.remote_scan_button), onClick = onScanQrCode)
        if (recentLinks.isNotEmpty()) {
            Spacer(modifier = Modifier.height(ZcodeSpacing.xl))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.remote_recent_title),
                    modifier = Modifier.weight(1f),
                    style = ZcodeType.uiMd,
                    color = zc.text,
                )
                TextButton(onClick = onClearRecentLinks) {
                    Text(stringResource(R.string.remote_recent_clear), style = ZcodeType.uiSm, color = zc.textSecondary)
                }
            }
            recentLinks.forEach { link ->
                Spacer(modifier = Modifier.height(ZcodeSpacing.sm))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .jellyClickable(onClickLabel = link.displayTitle(), onClick = {
                            // Bugfix: 二维码链接很长，用户重启 App 后手输容易出错。
                            // 这里复用本地已校验过的历史记录，但打开前仍走 onOpenUrl 的白名单校验。
                            if (!onOpenUrl(link.url)) {
                                localError = invalidUrlMessage
                            }
                        })
                        .background(zc.card, ZcodeShape.card)
                        .padding(horizontal = ZcodeSpacing.md, vertical = ZcodeSpacing.sm),
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.Start,
                    ) {
                        Text(
                            text = link.displayTitle(),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = ZcodeType.uiMd,
                            color = zc.text,
                        )
                        Text(
                            text = link.displaySubtitle(context),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = ZcodeType.uiXs,
                            color = zc.textSecondary,
                        )
                    }
                }
            }
        }
    }
}

/** 主操作：中性反色填充（zc.text 底 + zc.base 前景），48dp 触控目标 + 果冻按压。 */
@Composable
private fun PrimaryButton(label: String, onClick: () -> Unit) {
    val zc = LocalZcodeColors.current
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .jellyClickable(onClick = onClick, onClickLabel = label)
            .background(zc.text, ZcodeShape.input)
            .padding(horizontal = ZcodeSpacing.lg, vertical = ZcodeSpacing.md),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = ZcodeType.uiMd, color = zc.base)
    }
}

/** 次级操作：卡底色 + 主文本，不与主操作抢视觉权重。 */
@Composable
private fun SecondaryButton(label: String, onClick: () -> Unit) {
    val zc = LocalZcodeColors.current
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .jellyClickable(onClick = onClick, onClickLabel = label)
            .background(zc.card, ZcodeShape.input)
            .padding(horizontal = ZcodeSpacing.lg, vertical = ZcodeSpacing.md),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = ZcodeType.uiMd, color = zc.text)
    }
}

@Composable
private fun RemoteControlWebViewScreen(
    remoteUrl: String,
    onCloseRemote: () -> Unit,
) {
    val context = LocalContext.current
    var webView by remember { mutableStateOf<WebView?>(null) }
    var progress by remember { mutableStateOf(0) }
    var isLoading by remember { mutableStateOf(true) }
    var pageError by remember { mutableStateOf<String?>(null) }

    BackHandler {
        val view = webView
        if (view != null && view.canGoBack()) {
            view.goBack()
        } else {
            onCloseRemote()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            webView?.destroy()
            webView = null
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        RemoteControlTopBar(
            remoteUrl = remoteUrl,
            isLoading = isLoading,
            onReload = {
                pageError = null
                webView?.reload()
            },
            onClose = onCloseRemote,
        )
        if (isLoading) {
            LinearProgressIndicator(
                progress = { (progress.coerceIn(0, 100) / 100f) },
                modifier = Modifier.fillMaxWidth(),
                color = LocalZcodeColors.current.queued,
                trackColor = LocalZcodeColors.current.input,
            )
        }
        Box(modifier = Modifier.fillMaxSize()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = {
                    createRemoteControlWebView(
                        context = context,
                        onLoadingChanged = { loading ->
                            isLoading = loading
                        },
                        onProgressChanged = { nextProgress ->
                            progress = nextProgress
                        },
                        onMainFrameError = { message ->
                            pageError = message
                        },
                    ).also { view ->
                        webView = view
                        view.loadUrl(remoteUrl)
                    }
                },
                update = { view ->
                    if (view.url != remoteUrl) {
                        pageError = null
                        view.loadUrl(remoteUrl)
                    }
                },
            )
            if (pageError != null) {
                RemoteControlErrorOverlay(
                    message = pageError.orEmpty(),
                    onRetry = {
                        pageError = null
                        webView?.reload()
                    },
                )
            }
        }
    }
}

@Composable
private fun RemoteControlTopBar(
    remoteUrl: String,
    isLoading: Boolean,
    onReload: () -> Unit,
    onClose: () -> Unit,
) {
    val zc = LocalZcodeColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .height(48.dp)
            .padding(horizontal = ZcodeSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.remote_screen_title),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = ZcodeType.uiSm,
                color = zc.text,
            )
            Text(
                text = remoteUrl,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = ZcodeType.uiXs,
                color = zc.textSecondary,
            )
        }
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = zc.textTertiary,
            )
            Spacer(modifier = Modifier.width(8.dp))
        }
        TextButton(onClick = onReload) {
            Text(stringResource(R.string.remote_reload_button), style = ZcodeType.uiSm, color = zc.textSecondary)
        }
        TextButton(onClick = onClose) {
            Text(stringResource(R.string.remote_close_button), style = ZcodeType.uiSm, color = zc.textSecondary)
        }
    }
}

@Composable
private fun RemoteControlErrorOverlay(
    message: String,
    onRetry: () -> Unit,
) {
    val zc = LocalZcodeColors.current
    Surface(
        modifier = Modifier
            .fillMaxSize()
            .padding(ZcodeSpacing.xl),
        color = zc.base,
    ) {
        Column(
            modifier = Modifier.padding(ZcodeSpacing.xl),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.Start,
        ) {
            Text(
                text = stringResource(R.string.remote_load_failed_title),
                style = ZcodeType.uiLg,
                color = zc.text,
            )
            Spacer(modifier = Modifier.height(ZcodeSpacing.sm))
            Text(
                text = message,
                style = ZcodeType.uiMd,
                color = zc.textSecondary,
            )
            Spacer(modifier = Modifier.height(ZcodeSpacing.lg))
            PrimaryButton(label = stringResource(R.string.remote_retry_button), onClick = onRetry)
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
private fun createRemoteControlWebView(
    context: android.content.Context,
    onLoadingChanged: (Boolean) -> Unit,
    onProgressChanged: (Int) -> Unit,
    onMainFrameError: (String) -> Unit,
): WebView {
    CookieManager.getInstance().setAcceptCookie(true)

    return WebView(context).apply {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        overScrollMode = WebView.OVER_SCROLL_NEVER
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            mediaPlaybackRequiresUserGesture = false
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            userAgentString = "$userAgentString ZCodeAndroid/${BuildConfig.VERSION_NAME}"
        }
        webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                onProgressChanged(newProgress)
            }
        }
        webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                onLoadingChanged(true)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                onLoadingChanged(false)
            }

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest,
            ): Boolean {
                if (!request.isForMainFrame) {
                    return false
                }

                val resolved = RemoteControlUrlPolicy.resolve(request.url)
                if (resolved != null) {
                    if (request.url.scheme == "zcode") {
                        view.loadUrl(resolved)
                        return true
                    }
                    return false
                }

                openExternalUrl(view.context, request.url)
                return true
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?,
            ) {
                if (request?.isForMainFrame != true) {
                    return
                }
                onLoadingChanged(false)
                onMainFrameError(error?.description?.toString().orEmpty())
            }
        }
    }
}

private fun openExternalUrl(context: android.content.Context, uri: Uri) {
    val intent = Intent(Intent.ACTION_VIEW, uri)
    runCatching {
        context.startActivity(intent)
    }
}
