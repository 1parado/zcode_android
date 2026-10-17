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

    fun disconnect() = connection.disconnect()

    fun update(f: (ConnectUiState) -> ConnectUiState) {
        _uiState.value = f(_uiState.value)
    }
}

/**
 * 单例连接宿主：App 范围内共享一条 [ZcodeConnection]（Hilt 提供）。
 * 之所以做成独立类而非直接 @Provides：connection 需要 appScope + clientId 等运行期参数。
 */
@Singleton
class ConnectionHolder @Inject constructor(
    val connection: ZcodeConnection,
)

/** 供 ViewModel 消费的只读转发（避免 feature 直接拿 holder）。 */
class ConnectionFacade @Inject constructor(holder: ConnectionHolder) {
    val connection: ZcodeConnection get() = holder.connection
}
