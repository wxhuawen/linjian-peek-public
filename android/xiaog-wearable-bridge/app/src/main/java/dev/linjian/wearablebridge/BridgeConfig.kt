package dev.linjian.wearablebridge

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class BridgeConfig(context: Context) {
    private val prefs = EncryptedSharedPreferences.create(
        context, "wearable_bridge", MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )
    var endpoint: String get() = prefs.getString("endpoint", "") ?: ""; set(value) = prefs.edit().putString("endpoint", value.trim().trimEnd('/')).apply()
    var token: String get() = prefs.getString("token", "") ?: ""; set(value) = prefs.edit().putString("token", value.trim()).apply()
    var deviceName: String get() = prefs.getString("device_name", "小米手环7") ?: "小米手环7"; set(value) = prefs.edit().putString("device_name", value.trim()).apply()
    var model: String get() = prefs.getString("model", "xiaomi-band-7") ?: "xiaomi-band-7"; set(value) = prefs.edit().putString("model", value.trim()).apply()
}
