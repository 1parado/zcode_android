package com.zai.zcode.feature.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zai.zcode.core.common.ClientIdProvider
import com.zai.zcode.core.network.ZcodeConnection
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ChatViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    // 修复依据（P2-1，评审 2026-10-17）：删除 ConnectionFacade 双层包装，直接注入单例连接
    private val connection: ZcodeConnection,
    private val clientIds: ClientIdProvider,
) : ViewModel() {

    val sessionId: String = savedStateHandle.get<String>("sessionId").orEmpty()
    // 修复依据（P2-5）：workspace 由 ZcodeConnection.currentWorkspace 单一派生，feature 不再自推
    private val workspace = connection.currentWorkspace.value
        ?: connection.serverInfo.value?.workspaces?.firstOrNull()
    private val workspacePath: String = workspace?.path ?: ""

    private val repository = ChatRepository(
        scope = viewModelScope,
        connection = connection,
        sessionId = sessionId,
        workspacePath = workspacePath,
        clientId = clientIds.clientId,
        // 修复依据（真机 v0.2.15）：远程链路 identity 必须贯穿到会话订阅/命令，
        // 否则服务端 workspaceKey 与帧 fan-out 目标错位（AGENTS.md「Workspace Identity」）
        workspaceIdentity = workspace?.workspaceIdentity,
    )

    val ui get() = repository.ui

    /** 草稿与发送态（UI 局部状态，不入协议层）。 */
    var draft by mutableStateOf("")
        private set
    var sending by mutableStateOf(false)
        private set

    init {
        repository.start()
    }

    fun onDraftChange(v: String) { draft = v }

    fun send() {
        val text = draft.trim()
        if (text.isEmpty() || sending) return
        sending = true
        draft = ""
        viewModelScope.launch {
            val ok = repository.sendText(text)
            sending = false
            if (!ok) {
                // 修复依据（P3，评审 2026-10-17）：发送期间用户若已输入新文本，直接覆盖会
                // 静默丢新草稿；改为把失败文本换行拼回尾部，两者都保留
                draft = if (draft.isEmpty()) text else "$draft\n$text"
            }
        }
    }

    fun stop() {
        viewModelScope.launch { repository.stop() }
    }
}
