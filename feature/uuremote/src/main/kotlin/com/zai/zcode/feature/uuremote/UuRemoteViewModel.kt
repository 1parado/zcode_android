package com.zai.zcode.feature.uuremote

import android.app.Activity
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * UU 远程统一状态机（UI 唯一观察点）：
 * NotInstalled → NotBound → Binding → Bound → Connecting → (远控中由 UU 呈现)
 * 任意失败态带 rebindNeeded 标记决定重试/重绑按钮。
 */
sealed class UuRemoteUiState {
    object NotInstalled : UuRemoteUiState()
    object NotBound : UuRemoteUiState()
    object Binding : UuRemoteUiState()
    data class Bound(val deviceId: String) : UuRemoteUiState()
    object Connecting : UuRemoteUiState()
    data class Failed(val message: String, val rebindNeeded: Boolean) : UuRemoteUiState()
}

@HiltViewModel
class UuRemoteViewModel @Inject constructor(
    private val client: UuRemoteClient,
    private val store: UuRemoteDeviceStore,
) : ViewModel() {

    private val _state = MutableStateFlow<UuRemoteUiState>(UuRemoteUiState.NotBound)
    val state: StateFlow<UuRemoteUiState> = _state.asStateFlow()

    /**
     * 「重新检测」点击反馈（用户报「点了没反应」）：之前结果仍是未安装时 StateFlow
     * 同值不去重也发不出去，UI 零反馈。加一个自增事件流，每次点击都发——UI 收到后
     * 可提示「未检测到 UU 远程」；状态真变了则正常走状态机。
     */
    private val _recheckEvents = MutableStateFlow(0)
    val recheckEvents: StateFlow<Int> = _recheckEvents.asStateFlow()

    fun onScreenResumed(isInstalled: Boolean) {
        if (!isInstalled) {
            _state.value = UuRemoteUiState.NotInstalled
            _recheckEvents.value++
            return
        }
        val device = store.get()
        _state.value = if (device != null) UuRemoteUiState.Bound(device.deviceId)
        else UuRemoteUiState.NotBound
    }

    /** 入口按钮主逻辑（设计文档 §3.4）。 */
    fun onWatchClicked(activity: Activity) {
        when (val s = _state.value) {
            UuRemoteUiState.NotInstalled -> Unit // UI 层先弹引导安装
            UuRemoteUiState.NotBound, is UuRemoteUiState.Failed -> bind(activity)
            is UuRemoteUiState.Bound -> connect(activity, s.deviceId)
            UuRemoteUiState.Binding, UuRemoteUiState.Connecting -> Unit // 进行中防抖
        }
    }

    fun installGuide(context: Context) = UuRemoteInstallationChecker.openMarket(context)

    /** device_not_found → 清旧 ID 重新绑定。 */
    fun rebind(activity: Activity) {
        store.clear()
        bind(activity)
    }

    private fun bind(activity: Activity) {
        _state.value = UuRemoteUiState.Binding
        viewModelScope.launch {
            when (val r = client.authorize(activity)) {
                is UuBindResult.Success -> {
                    store.save(r.deviceId)
                    _state.value = UuRemoteUiState.Bound(r.deviceId)
                }
                is UuBindResult.Error -> _state.value = UuRemoteUiState.Failed(
                    r.reason.label,
                    rebindNeeded = r.reason == UuErrorReason.DEVICE_NOT_FOUND,
                )
                UuBindResult.Cancelled -> _state.value = UuRemoteUiState.NotBound
            }
        }
    }

    private fun connect(activity: Activity, deviceId: String) {
        _state.value = UuRemoteUiState.Connecting
        viewModelScope.launch {
            when (val r = client.startPipControl(activity, deviceId)) {
                UuControlResult.Success -> {
                    // 小窗由 UU 自身渲染，状态回 Bound 待下次
                    _state.value = UuRemoteUiState.Bound(deviceId)
                }
                is UuControlResult.Error -> {
                    if (r.reason == UuErrorReason.DEVICE_NOT_FOUND) store.clear()
                    _state.value = UuRemoteUiState.Failed(
                        r.reason.label,
                        rebindNeeded = r.reason == UuErrorReason.DEVICE_NOT_FOUND,
                    )
                }
                UuControlResult.Cancelled -> _state.value = UuRemoteUiState.Bound(deviceId)
            }
        }
    }
}

/** 深链路由 App 单例：MainActivity.onNewIntent 转发深链到此。 */
@Singleton
class UuRemoteDeepLinkHub @Inject constructor() {
    val router = UuRemoteCallbackRouter()
}

@dagger.Module
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
object UuRemoteModule {
    @dagger.Provides
    @Singleton
    fun router(hub: UuRemoteDeepLinkHub): UuRemoteCallbackRouter = hub.router

    @dagger.Provides
    @Singleton
    fun store(@ApplicationContext context: Context): UuRemoteDeviceStore = UuRemoteDeviceStore(context)

    @dagger.Provides
    @Singleton
    fun client(
        router: UuRemoteCallbackRouter,
        store: UuRemoteDeviceStore,
    ): UuRemoteClient = UuRemoteClient(router, store)
}
