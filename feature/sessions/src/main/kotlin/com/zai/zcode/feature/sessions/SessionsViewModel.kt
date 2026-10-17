package com.zai.zcode.feature.sessions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zai.zcode.core.network.ZcodeConnection
import com.zai.zcode.feature.connect.ConnectionFacade
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SessionsViewModel @Inject constructor(
    connectionFacade: ConnectionFacade,
) : ViewModel() {

    private val repository = SessionsRepository(viewModelScope, connectionFacade.connection)

    val sessions: StateFlow<List<SessionsRepository.SessionItem>> =
        repository.sessions.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val connectionState: StateFlow<ZcodeConnection.State> =
        connectionFacade.connection.state

    init {
        repository.start()
        // workspace 选择：Phase 1 取 server-info 第一个 workspace（多 workspace 选择器后续版本）
        viewModelScope.launch {
            connectionFacade.connection.serverInfo.collect { info ->
                val ws = info?.workspaces?.firstOrNull()?.path
                if (ws != null) repository.setWorkspace(ws)
            }
        }
    }
}
