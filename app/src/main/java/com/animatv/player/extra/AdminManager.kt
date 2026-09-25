package com.animatv.player.extra

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.animatv.player.App
import com.animatv.player.model.AdminConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * AdminManager - mengelola semua fitur admin:
 * 1. Autentikasi admin (kode rahasia)
 * 2. Remote config dari GitHub
 * 3. Feature flags
 */
object AdminManager {

    // ===== KONFIGURASI ADMIN =====
    // Ganti ini dengan kode rahasia milikmu!
    private const val ADMIN_SECRET_CODE = "ANIMATV2026"
    private const val ADMIN_GITHUB_OWNER = "toyamahikari12-hub"

    // URL config dari GitHub repo (repo aktif milikmu)
    private const val CONFIG_URL =
        "https://raw.githubusercontent.com/toyamahikari12-hub/StarVision-TV/main/config/features.json"

    // SharedPreferences keys
    private const val PREF_NAME = "animatv_admin"
    private const val KEY_IS_ADMIN = "is_admin_unlocked"
    private const val KEY_CONFIG_CACHE = "config_cache"
    private const val KEY_CONFIG_TIME = "config_fetch_time"
    private const val KEY_TAP_COUNT = "logo_tap_count"
    private const val KEY_TAP_TIME = "logo_tap_time"

    // Cache config selama 5 menit
    private const val CONFIG_CACHE_MINUTES = 5L

    private val prefs: SharedPreferences by lazy {
        App.context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    private var cachedConfig: AdminConfig? = null

    // ===== 1. ADMIN AUTH =====

    val isAdminUnlocked: Boolean
        get() = prefs.getBoolean(KEY_IS_ADMIN, false)

    fun tryUnlockAdmin(code: String): Boolean {
        return if (code == ADMIN_SECRET_CODE) {
            prefs.edit().putBoolean(KEY_IS_ADMIN, true).apply()
            true
        } else false
    }

    fun lockAdmin() {
        prefs.edit().putBoolean(KEY_IS_ADMIN, false).apply()
    }

    // Logo tap counter - tap 7x dalam 3 detik untuk buka dialog
    fun onLogoTapped(): Boolean {
        val now = System.currentTimeMillis()
        val lastTime = prefs.getLong(KEY_TAP_TIME, 0)
        var count = prefs.getInt(KEY_TAP_COUNT, 0)

        // Reset kalau sudah lebih dari 3 detik
        if (now - lastTime > 3000) count = 0

        count++
        prefs.edit()
            .putInt(KEY_TAP_COUNT, count)
            .putLong(KEY_TAP_TIME, now)
            .apply()

        return count >= 7 // return true kalau sudah 7x tap
    }

    fun resetTapCount() {
        prefs.edit().putInt(KEY_TAP_COUNT, 0).apply()
    }

    // ===== 2. REMOTE CONFIG =====

    fun getConfig(): AdminConfig {
        // Pakai cache dulu
        cachedConfig?.let { return it }

        // Coba load dari cache SharedPreferences
        val cacheTime = prefs.getLong(KEY_CONFIG_TIME, 0)
        val now = System.currentTimeMillis()
        val cacheValid = (now - cacheTime) < (CONFIG_CACHE_MINUTES * 60 * 1000)

        if (cacheValid) {
            val json = prefs.getString(KEY_CONFIG_CACHE, null)
            if (!json.isNullOrBlank()) {
                try {
                    val config = Gson().fromJson(json, AdminConfig::class.java)
                    cachedConfig = config
                    return config
                } catch (e: Exception) { /* fall through */ }
            }
        }

        return AdminConfig() // default config
    }

    fun fetchConfigAsync(onResult: (AdminConfig) -> Unit) {
        Thread {
            try {
                val client = OkHttpClient.Builder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(10, TimeUnit.SECONDS)
                    .build()

                val request = Request.Builder().url(CONFIG_URL).build()
                val response = client.newCall(request).execute()

                if (response.isSuccessful) {
                    val json = response.body?.string() ?: return@Thread
                    val config = Gson().fromJson(json, AdminConfig::class.java)

                    // Simpan ke cache
                    cachedConfig = config
                    prefs.edit()
                        .putString(KEY_CONFIG_CACHE, json)
                        .putLong(KEY_CONFIG_TIME, System.currentTimeMillis())
                        .apply()

                    App.runOnUiThread { onResult(config) }
                }
            } catch (e: Exception) {
                // Pakai default config kalau gagal
                App.runOnUiThread { onResult(AdminConfig()) }
            }
        }.start()
    }

    fun clearConfigCache() {
        cachedConfig = null
        prefs.edit()
            .remove(KEY_CONFIG_CACHE)
            .remove(KEY_CONFIG_TIME)
            .apply()
    }

    // ===== 3. FEATURE FLAGS =====

    fun isFeatureEnabled(feature: String): Boolean {
        val config = getConfig()
        return when (feature) {
            "sleep_timer"        -> config.featureSleepTimer
            "playback_speed"     -> config.featurePlaybackSpeed
            "gesture_control"    -> config.featureGestureControl
            "double_tap"         -> config.featureDoubleTap
            "auto_quality"       -> config.featureAutoQuality
            "mini_channel"       -> config.featureMiniChannelPanel
            "anime_background"   -> config.featureAnimeBackground
            "anime_quote"        -> config.featureAnimeQuote
            "sakura_effect"      -> config.featureSakuraEffect
            "anime_guide"        -> config.featureAnimeGuide
            "continue_watching"  -> config.featureContinueWatching
            "recently_watched"   -> config.featureRecentlyWatched
            "channel_shortcut"   -> config.featureChannelShortcut
            "live_now_banner"    -> config.featureLiveNowBanner
            else -> true
        }
    }
}
