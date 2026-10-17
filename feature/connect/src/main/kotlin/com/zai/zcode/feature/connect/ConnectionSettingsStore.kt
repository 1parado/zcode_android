package com.zai.zcode.feature.connect

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 连接配置持久化（DataStore）。
 * 安全边界：serverUrl 非敏感可明文；authToken 属凭据 —— 仅存于 EncryptedSharedPreferences
 * （见 [SecureTokenStore]），DataStore 只存"是否已配置 token"标记，避免凭据进普通存储。
 */
private val Context.dataStore by preferencesDataStore(name = "zcode_connection")

data class ConnectionConfig(
    val serverUrl: String = "",
    val workspacePath: String = "",
    val hasToken: Boolean = false,
)

class ConnectionSettingsStore(private val context: Context) {

    private val KEY_URL = stringPreferencesKey("server_url")
    private val KEY_WORKSPACE = stringPreferencesKey("workspace_path")
    // 修复依据（P3，评审 2026-10-17）：布尔标记用 booleanPreferencesKey，不再存 "1"/"0" 字符串
    private val KEY_HAS_TOKEN = booleanPreferencesKey("has_token")

    val config: Flow<ConnectionConfig> = context.dataStore.data.map { prefs ->
        ConnectionConfig(
            serverUrl = prefs[KEY_URL] ?: "",
            workspacePath = prefs[KEY_WORKSPACE] ?: "",
            hasToken = prefs[KEY_HAS_TOKEN] ?: false,
        )
    }

    suspend fun save(config: ConnectionConfig) {
        context.dataStore.edit { prefs ->
            prefs[KEY_URL] = config.serverUrl
            prefs[KEY_WORKSPACE] = config.workspacePath
            prefs[KEY_HAS_TOKEN] = config.hasToken
        }
    }
}
