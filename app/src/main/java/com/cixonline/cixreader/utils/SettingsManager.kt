package com.cixonline.cixreader.utils

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

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
}
