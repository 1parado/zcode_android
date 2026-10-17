package com.zai.zcode.feature.connect

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zai.zcode.core.network.ServerInfo
import com.zai.zcode.core.network.ZcodeConnection
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import javax.inject.Inject
import javax.inject.Singleton

/** 连接页面 UI 状态。 */
data class ConnectUiState(
    val serverUrl: String = "",
    val token: String = "",
    val workspacePath: String = "",
    val connecting: Boolean = false,
    val error: String? = null,
    val serverInfo: ServerInfo? = null,
    /**
     * 本次表单发起的连接已成功（边沿信号，评审 P1-C：导航不能由"当前是 Ready"驱动，
     * 否则从 Sessions 点设置进来瞬间被弹回；由 connect() 置位、UI 消费后复位）。
     */
    val justConnected: Boolean = false,
)

/**
 * 连接 ViewModel：表单 → [ZcodeConnection.connect]；观察状态机渲染进度/错误。
 * 连接成功后把 serverUrl/workspacePath 写回持久层，下次启动自动重连。
 */
@HiltViewModel
class ConnectViewModel @Inject constructor(
    private val connection: ZcodeConnection,
    private val settings: ConnectionSettingsStore,
    private val tokenStore: SecureTokenStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ConnectUiState())
    val uiState: StateFlow<ConnectUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            settings.config.collect { cfg ->
                if (!_uiState.value.connecting && _uiState.value.serverUrl.isEmpty()) {
                    _uiState.value = _uiState.value.copy(
                        serverUrl = cfg.serverUrl,
                        workspacePath = cfg.workspacePath,
                        token = tokenStore.getToken() ?: "",
                    )
                }
            }
        }
        viewModelScope.launch {
            connection.state.collect { state ->
                val s = _uiState.value
                _uiState.value = when (state) {
                    is ZcodeConnection.State.Ready -> s.copy(
                        connecting = false,
                        error = null,
                        serverInfo = state.serverInfo,
                        // 修复依据（P1-C）：只在本次表单发起连接期间到达 Ready 才置位，
                        // 重进设置页（state 本来就是 Ready）不再触发导航
                        justConnected = s.connecting,
                    )
                    is ZcodeConnection.State.Failed -> s.copy(
                        connecting = false,
                        error = state.reason,
                    )
                    is ZcodeConnection.State.Discovering,
                    is ZcodeConnection.State.ConnectingWs,
                    is ZcodeConnection.State.Handshaking,
                    -> s.copy(connecting = true, error = null)
                    ZcodeConnection.State.Disconnected -> s.copy(connecting = false)
                }
            }
        }
    }

    fun connect() {
        val s = _uiState.value
        val url = s.serverUrl.trim()
        if (url.isEmpty()) {
            _uiState.value = s.copy(error = "请填写服务器地址")
            return
        }
        _uiState.value = s.copy(connecting = true, error = null)
        connection.connect(url, s.token.trim().ifEmpty { null })
        viewModelScope.launch {
            settings.save(
                ConnectionConfig(
                    serverUrl = url,
                    workspacePath = s.workspacePath,
                    hasToken = s.token.isNotBlank(),
                )
            )
            tokenStore.saveToken(s.token.trim())
        }
    }

    fun disconnect() {
        // 修复依据(用户报障"扫码后一直自动重传"):失败后 wantConnected 仍为 true,
        // connection 会按退避无限重连;用户改地址/放弃时必须显式停掉重连,
        // 且清空上一次的错误,避免新表单残留旧失败原因。
        connection.disconnect()
        _uiState.value = _uiState.value.copy(connecting = false, error = null)
    }

    /** UI 消费 justConnected 边沿后复位（P1-C）。 */
    fun consumeJustConnected() {
        _uiState.value = _uiState.value.copy(justConnected = false)
    }

    fun update(f: (ConnectUiState) -> ConnectUiState) {
        _uiState.value = f(_uiState.value)
    }
}

/**
 * ConnectionHolder / ConnectionFacade 已下沉到 core/network
 * （feature/chat、feature/sessions 也需要消费，放 connect 会造成模块环）。
 */
