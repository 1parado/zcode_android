package com.zai.zcode.feature.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zai.zcode.feature.connect.ConnectionFacade
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ChatViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    connectionFacade: ConnectionFacade,
    private val clientIds: ClientIdProvider,
) : ViewModel() {

    val sessionId: String = savedStateHandle.get<String>("sessionId").orEmpty()
    private val workspacePath: String =
        connectionFacade.connection.serverInfo.value?.workspaces?.firstOrNull()?.path ?: ""

    private val repository = ChatRepository(
        scope = viewModelScope,
        connection = connectionFacade.connection,
        sessionId = sessionId,
        workspacePath = workspacePath,
        clientId = clientIds.clientId,
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
                // 发送失败：回滚草稿让用户重试（乐观清空的补偿）
                if (draft.isEmpty()) draft = text
            }
        }
    }

    fun stop() {
        viewModelScope.launch { repository.stop() }
    }
}

/** clientId 桥（connect 模块提供单例，chat 模块只依赖接口语义）。 */
interface ClientIdProvider { val clientId: String }
