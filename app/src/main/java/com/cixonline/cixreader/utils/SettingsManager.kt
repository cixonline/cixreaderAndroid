package com.cixonline.cixreader.utils

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SettingsManager(context: Context) {
    private val masterKey by lazy {
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
    }

    private val sharedPreferences by lazy {
        EncryptedSharedPreferences.create(
            context,
            "cix_secure_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private val _fontSizeFlow = MutableStateFlow(getFontSize())
    val fontSizeFlow: StateFlow<Float> = _fontSizeFlow.asStateFlow()

    fun saveCredentials(username: String, password: String) {
        sharedPreferences.edit()
            .putString("username", username)
            .putString("password", password)
            .apply()
    }

    fun getCredentials(): Pair<String?, String?> {
        return try {
            val username = sharedPreferences.getString("username", null)
            val password = sharedPreferences.getString("password", null)
            Pair(username, password)
        } catch (e: Exception) {
            Pair(null, null)
        }
    }

    fun clearCredentials() {
        sharedPreferences.edit()
            .remove("username")
            .remove("password")
            .apply()
    }

    fun saveFontSize(size: Float) {
        sharedPreferences.edit()
            .putFloat("font_size_multiplier", size)
            .apply()
        _fontSizeFlow.value = size
    }

    fun getFontSize(): Float {
        return sharedPreferences.getFloat("font_size_multiplier", 1.0f)
    }
}
