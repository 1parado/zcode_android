package com.zai.zcode.feature.uuremote

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * UU 远程接入协议（对齐 netease-uuyc/uuremote-integration-guide）：
 *
 * 绑定：uuremote://external/device/authorize?callback=<enc>&nonce=<uuid>
 *   成功回跳 callback?anonymous_device_id=<enc>&nonce=...
 *   失败回跳 callback?error=<reason>
 * 远控：uuremote://external/device/control?anonymous_device_id=<id>&window_type=<0|1>[&callback=<enc>]
 *   小窗(1)+callback 回跳 status=success | status=failure&reason=<...>；全屏(0) 不回调。
 *
 * 约束：同一时刻只处理一个第三方请求（并发被 superseded）；Android 11+ 需 <queries>。
 */

/** 绑定失败/远控失败的 reason（协议封闭集合）。 */
enum class UuErrorReason(val label: String) {
    NO_DEVICE("未检测到 UU 远程，请先安装"),
    USER_CANCEL("已取消"),
    TIMEOUT("请求超时，请重试"),
    NOT_LOGINED("UU 远程未登录"),
    SUPERSEDED("有其他请求正在进行"),
    LOGOUT("UU 账号已退出登录"),
    BUSY("对端正忙"),
    DEVICE_OFFLINE("电脑不在线"),
    DEVICE_NOT_FOUND("UU 账号已变更，请重新绑定"),
    PIP_UNSUPPORTED("当前设备不支持小窗"),
    LOGIN_TIMEOUT("登录超时"),
    NETWORK_UNAVAILABLE("网络不可用"),
    NOT_ALLOWED("未被授权"),
    CONNECT_TIMEOUT("连接超时"),
    CONNECT_FAILED("连接失败"),
    INVALID_REQUEST("请求无效"),
    INTERNAL_ERROR("内部错误，请重试"),
    ;

    companion object {
        fun from(raw: String?): UuErrorReason? =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
    }
}

sealed class UuBindResult {
    data class Success(val deviceId: String) : UuBindResult()
    data class Error(val reason: UuErrorReason) : UuBindResult()
    object Cancelled : UuBindResult()
}

sealed class UuControlResult {
    object Success : UuControlResult()
    data class Error(val reason: UuErrorReason) : UuControlResult()
    object Cancelled : UuControlResult()
}

/** 回调路由：MainActivity.onNewIntent 转发，按 host 分发到 pending Deferred。 */
class UuRemoteCallbackRouter {

    private var bindDeferred: kotlinx.coroutines.CompletableDeferred<UuBindResult>? = null
    private var bindNonce: String? = null
    private var controlDeferred: kotlinx.coroutines.CompletableDeferred<UuControlResult>? = null

    /** 发起绑定前调用；返回挂起的 Deferred。 */
    fun expectBind(nonce: String): kotlinx.coroutines.CompletableDeferred<UuBindResult> {
        // 修复依据（P2-8，评审 2026-10-17）：协议约束"同一时刻只处理一个请求"；
        // 直接覆盖会让前一个调用方等满 3min 超时。覆盖前先把旧 Deferred 以
        // SUPERSEDED 完成，让等待方立即返回。
        bindDeferred?.complete(UuBindResult.Error(UuErrorReason.SUPERSEDED))
        val d = kotlinx.coroutines.CompletableDeferred<UuBindResult>()
        bindDeferred = d
        bindNonce = nonce
        return d
    }

    /** 发起小窗远控前调用。 */
    fun expectControl(): kotlinx.coroutines.CompletableDeferred<UuControlResult> {
        // 修复依据（P2-8）：同 expectBind，覆盖前完成旧请求
        controlDeferred?.complete(UuControlResult.Error(UuErrorReason.SUPERSEDED))
        val d = kotlinx.coroutines.CompletableDeferred<UuControlResult>()
        controlDeferred = d
        return d
    }

    /** 深链入口；返回 true 表示本路由已消费。 */
    fun onNewIntent(intent: Intent?): Boolean {
        val uri = intent?.data ?: return false
        if (uri.scheme != CALLBACK_SCHEME) return false
        return when (uri.host) {
            "bind" -> { handleBind(uri); true }
            "control" -> { handleControl(uri); true }
            else -> false
        }
    }

    private fun handleBind(uri: Uri) {
        val d = bindDeferred ?: return
        val nonce = uri.getQueryParameter("nonce")
        if (nonce != null && nonce != bindNonce) return // nonce 校验防串扰
        bindDeferred = null
        val deviceId = uri.getQueryParameter("anonymous_device_id")
        val error = uri.getQueryParameter("error")
        when {
            deviceId != null -> d.complete(UuBindResult.Success(deviceId))
            error != null -> d.complete(
                UuBindResult.Error(UuErrorReason.from(error) ?: UuErrorReason.INTERNAL_ERROR)
            )
            else -> d.complete(UuBindResult.Cancelled)
        }
    }

    private fun handleControl(uri: Uri) {
        val d = controlDeferred ?: return
        controlDeferred = null
        when (uri.getQueryParameter("status")) {
            "success" -> d.complete(UuControlResult.Success)
            "failure" -> d.complete(
                UuControlResult.Error(
                    UuErrorReason.from(uri.getQueryParameter("reason")) ?: UuErrorReason.INTERNAL_ERROR
                )
            )
            else -> d.complete(UuControlResult.Cancelled)
        }
    }

    companion object {
        const val CALLBACK_SCHEME = "zcode"
    }
}

/** 深链 callback 地址（AndroidManifest intent-filter 与此对应）。 */
object UuRemoteLinks {
    const val BIND_CALLBACK = "zcode://uuremote/bind"
    const val CONTROL_CALLBACK = "zcode://uuremote/control"
    const val AUTHORIZE_URL = "uuremote://external/device/authorize"
    const val CONTROL_URL = "uuremote://external/device/control"
}

/**
 * 检测 UU 远程是否安装。
 *
 * 修复依据（真机：安装后仍显示未安装）：华为等厂商 ROM 对无 authority 的
 * scheme URI（uuremote://external）resolveActivity 可能误报 null——而带 path 的
 * 协议 URI（官方 guide 2.1 的 authorize 地址）实测能解析到 DeepLinkActivity。
 * 双路径检测：协议 URI 优先；都解析不到时按官方 guide §1.5 的建议直接查
 * 正式包名 com.netease.uuremote（guide 明确「包名可用于安装引导」，检测兜底
 * 用包名存在性比误报"未安装"更不伤体验——误报会让用户卡在引导页无法进入）。
 */
object UuRemoteInstallationChecker {
    private const val UU_PACKAGE = "com.netease.uuremote"

    fun isInstalled(context: Context): Boolean {
        // 1) 官方协议 URI（带 path，与实际拉起用的 intent 同形）
        for (uri in listOf(
            "uuremote://external/device/authorize",
            "uuremote://external/device/control",
            "uuremote://external",
        )) {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri))
            if (intent.resolveActivity(context.packageManager) != null) return true
        }
        // 2) 包名兜底（guide §1.5：正式包名 com.netease.uuremote）
        return runCatching {
            context.packageManager.getPackageInfo(UU_PACKAGE, 0)
            true
        }.getOrDefault(false)
    }

    /** 引导到应用市场（优先精确包名的市场详情页，失败退搜索页）。 */
    fun openMarket(context: Context) {
        val detail = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("market://details?id=$UU_PACKAGE"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { context.startActivity(detail) }.isFailure) {
            runCatching {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=UU远程"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
    }
}
