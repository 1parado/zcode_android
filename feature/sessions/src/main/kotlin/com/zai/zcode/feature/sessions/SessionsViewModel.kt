package com.zai.zcode.feature.sessions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zai.zcode.core.network.ZcodeConnection
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SessionsViewModel @Inject constructor(
    // 修复依据（P2-1，评审 2026-10-17）：删除 ConnectionFacade 双层包装，直接注入单例连接
    private val connection: ZcodeConnection,
) : ViewModel() {

    private val repository = SessionsRepository(viewModelScope, connection)

    val sessions: StateFlow<List<SessionsRepository.SessionItem>> =
        repository.sessions.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val connectionState: StateFlow<ZcodeConnection.State> =
        connection.state

    init {
        repository.start()
        // 修复依据（P2-5）：workspace 由 ZcodeConnection.currentWorkspace 单一派生，
        // 这里只消费不再自行推导（多 workspace 选择器后续版本）
        viewModelScope.launch {
            connection.currentWorkspace.collect { ws ->
                if (ws != null) repository.setWorkspace(ws.path)
            }
        }
    }

    /** 创建中/失败提示（UI 局部状态）。 */
    var creating by androidx.compose.runtime.mutableStateOf(false)
        private set
    var createError by androidx.compose.runtime.mutableStateOf<String?>(null)
        private set

    /**
     * Android 端创建会话（用户报"应该支持在 Android 上进行会话创建"）。
     * 成功后直接打开新会话；创建成功的新会话会随 sessions-index 增量出现在列表里。
     */
    fun createSession(onCreated: (sessionId: String) -> Unit) {
        if (creating) return
        creating = true
        createError = null
        viewModelScope.launch {
            val id = connection.createSession(firstInput = null)
            creating = false
            if (id != null) {
                onCreated(id)
            } else {
                createError = "创建会话失败，请确认电脑端在线后重试"
            }
        }
    }

    fun dismissCreateError() {
        createError = null
    }
}
