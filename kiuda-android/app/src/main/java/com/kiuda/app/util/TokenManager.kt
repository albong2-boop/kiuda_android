package com.kiuda.app.util

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

object TokenManager {
    private const val PREF_NAME = "kiuda_secure_prefs"
    private const val KEY_ACCESS = "access_token"
    private const val KEY_REFRESH = "refresh_token"
    private const val KEY_USERNAME = "username"
    private const val KEY_NAME = "name"
    private const val KEY_NICKNAME = "nickname"

    @Volatile
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs != null) return
        synchronized(this) {
            if (prefs != null) return
            try {
                val masterKey = MasterKey.Builder(context.applicationContext)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                prefs = EncryptedSharedPreferences.create(
                    context.applicationContext,
                    PREF_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            } catch (e: Exception) {
                // Fallback: plain SharedPreferences (에뮬레이터/일부 기기 암호화 실패 대비)
                prefs = context.applicationContext.getSharedPreferences(
                    PREF_NAME + "_fallback",
                    Context.MODE_PRIVATE
                )
            }
        }
    }

    private fun p(): SharedPreferences {
        return prefs
            ?: throw IllegalStateException("TokenManager not initialized. Call init() in Application.")
    }

    fun saveTokens(access: String, refresh: String?) {
        p().edit().apply {
            putString(KEY_ACCESS, access)
            if (refresh != null) putString(KEY_REFRESH, refresh)
            apply()
        }
    }

    fun getAccessToken(): String? = try {
        p().getString(KEY_ACCESS, null)
    } catch (_: Exception) {
        null
    }

    fun getRefreshToken(): String? = try {
        p().getString(KEY_REFRESH, null)
    } catch (_: Exception) {
        null
    }

    fun saveUserInfo(username: String?, name: String?, nickname: String?) {
        p().edit()
            .putString(KEY_USERNAME, username)
            .putString(KEY_NAME, name)
            .putString(KEY_NICKNAME, nickname)
            .apply()
    }

    fun getUsername(): String? = try {
        p().getString(KEY_USERNAME, null)
    } catch (_: Exception) {
        null
    }

    fun getName(): String? = try {
        p().getString(KEY_NAME, null)
    } catch (_: Exception) {
        null
    }

    fun getNickname(): String? = try {
        p().getString(KEY_NICKNAME, null) ?: getName()
    } catch (_: Exception) {
        null
    }

    fun clear() {
        try {
            p().edit().clear().apply()
        } catch (_: Exception) {
            // ignore
        }
    }
}
