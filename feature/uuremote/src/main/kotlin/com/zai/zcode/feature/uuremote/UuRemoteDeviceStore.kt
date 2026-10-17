package com.zai.zcode.feature.uuremote

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** 已绑定设备（anonymous_device_id 与 UU 账号绑定，切账号后失效需重绑）。 */
data class StoredDevice(val deviceId: String, val boundAt: Long)

/**
 * 绑定设备 ID 加密存储。device_not_found 时 UI 层应调用 [clear] 触发重绑。
 */
class UuRemoteDeviceStore(context: Context) {

    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "zcode_uu_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun get(): StoredDevice? {
        val id = prefs.getString(KEY_DEVICE_ID, null) ?: return null
        return StoredDevice(id, prefs.getLong(KEY_BOUND_AT, 0L))
    }

    fun save(deviceId: String) {
        prefs.edit()
            .putString(KEY_DEVICE_ID, deviceId)
            .putLong(KEY_BOUND_AT, System.currentTimeMillis())
            .apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val KEY_DEVICE_ID = "anonymous_device_id"
        const val KEY_BOUND_AT = "bound_at"
    }
}
