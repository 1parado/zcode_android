package com.zai.zcode.feature.uuremote

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import java.util.UUID

/**
 * UU 远程客户端：authorize 绑定 + control 拉起。
 * 只依赖 Android framework，与 ZCode 协议层完全正交（决策 B：视觉通道）。
 */
class UuRemoteClient(
    private val router: UuRemoteCallbackRouter,
    private val store: UuRemoteDeviceStore,
) {

    /** 发起绑定；挂起直到 UU 回跳或超时（本地 3min 容灾，仅兜底）。 */
    suspend fun authorize(activity: Activity): UuBindResult {
        val nonce = UUID.randomUUID().toString()
        val deferred = router.expectBind(nonce)
        val uri = Uri.parse(UuRemoteLinks.AUTHORIZE_URL).buildUpon()
            .appendQueryParameter("callback", UuRemoteLinks.BIND_CALLBACK)
            .appendQueryParameter("nonce", nonce)
            .build()
        val ok = launch(activity, uri)
        if (!ok) return UuBindResult.Error(UuErrorReason.NO_DEVICE)
        return try {
            withTimeout(BIND_TIMEOUT_MS) { deferred.await() }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            UuBindResult.Error(UuErrorReason.TIMEOUT)
        }
    }

    /** 小窗远控（window_type=1，带 callback）。挂起直到回跳或超时。 */
    suspend fun startPipControl(activity: Activity, deviceId: String): UuControlResult {
        val deferred = router.expectControl()
        val uri = Uri.parse(UuRemoteLinks.CONTROL_URL).buildUpon()
            .appendQueryParameter("anonymous_device_id", deviceId)
            .appendQueryParameter("window_type", "1")
            .appendQueryParameter("callback", UuRemoteLinks.CONTROL_CALLBACK)
            .build()
        val ok = launch(activity, uri)
        if (!ok) return UuControlResult.Error(UuErrorReason.NO_DEVICE)
        return try {
            withTimeout(CONTROL_TIMEOUT_MS) { deferred.await() }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            UuControlResult.Error(UuErrorReason.TIMEOUT)
        }
    }

    /** 全屏远控（window_type=0，无回调，直接拉起）。 */
    fun startFullscreenControl(activity: Activity, deviceId: String): Boolean {
        val uri = Uri.parse(UuRemoteLinks.CONTROL_URL).buildUpon()
            .appendQueryParameter("anonymous_device_id", deviceId)
            .appendQueryParameter("window_type", "0")
            .build()
        return launch(activity, uri)
    }

    private fun launch(context: Context, uri: Uri): Boolean = runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }.isSuccess

    private companion object {
        const val BIND_TIMEOUT_MS = 3 * 60_000L
        const val CONTROL_TIMEOUT_MS = 4 * 60_000L
    }
}
