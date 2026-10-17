package com.zai.zcode.feature.sessions

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 会话本地状态（docs/ui-design.md Phase B）：pinned 与 lastSeenActivityAt（未读派生）。
 * 这两个字段是 Android 端本地事实（wire 上没有/桌面另有来源），DataStore 持久化，
 * key = sessionId。
 */
private val Context.sessionLocalDataStore by preferencesDataStore(name = "session_local_state")

@Singleton
class SessionLocalStateStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val pinnedKey = stringSetPreferencesKey("pinned_session_ids")
    private val seenPrefix = "seen_"

    data class LocalState(val pinned: Boolean = false, val lastSeenActivityAt: Long = 0L)

    /** sessionId → 本地状态（响应式）。 */
    val states: Flow<Map<String, LocalState>> = context.sessionLocalDataStore.data.map { prefs ->
        val pinned = prefs[pinnedKey] ?: emptySet()
        buildMap {
            for (key in prefs.asMap().keys) {
                if (key.name.startsWith(seenPrefix)) {
                    val id = key.name.removePrefix(seenPrefix)
                    put(id, LocalState(pinned = id in pinned, lastSeenActivityAt = prefs[key] ?: 0L))
                }
            }
            // 已 pin 但还没记过 seen 的会话也要有状态
            for (id in pinned) putIfAbsent(id, LocalState(pinned = true))
        }
    }

    suspend fun setPinned(sessionId: String, pinned: Boolean) {
        context.sessionLocalDataStore.edit { prefs ->
            val cur = prefs[pinnedKey] ?: emptySet()
            prefs[pinnedKey] = if (pinned) cur + sessionId else cur - sessionId
        }
    }

    /** 打开会话时记录已读水位（= 当前 lastActivityAt）。 */
    suspend fun markSeen(sessionId: String, lastActivityAt: Long) {
        context.sessionLocalDataStore.edit { prefs ->
            prefs[longPreferencesKey(seenPrefix + sessionId)] = lastActivityAt
        }
    }
}
