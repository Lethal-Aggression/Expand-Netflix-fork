package com.d4cx.streamflix

import android.content.Context
import android.content.SharedPreferences

object SettingsManager {
    private const val PREFS_NAME = "jewflix_settings"

    private const val KEY_MAX_CACHE_MB = "max_cache_mb"
    private const val KEY_PREWARM_ENABLED = "prewarm_enabled"
    private const val KEY_RD_KEY = "realdebrid_key"
    private const val KEY_CUSTOM_ADDON_URL = "custom_addon_url"
    private const val KEY_PREFERRED_QUALITY = "preferred_quality"

    const val DEFAULT_CACHE_MB = 400
    const val DEFAULT_ADDON_URL = "https://torrentio.strem.fun"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getMaxCacheMB(context: Context): Int {
        return getPrefs(context).getInt(KEY_MAX_CACHE_MB, DEFAULT_CACHE_MB).coerceIn(160, 800)
    }

    fun setMaxCacheMB(context: Context, mb: Int) {
        getPrefs(context).edit().putInt(KEY_MAX_CACHE_MB, mb.coerceIn(160, 800)).apply()
    }

    fun isPrewarmEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_PREWARM_ENABLED, true)
    }

    fun setPrewarmEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_PREWARM_ENABLED, enabled).apply()
    }

    fun getRealDebridKey(context: Context): String {
        return getPrefs(context).getString(KEY_RD_KEY, "") ?: ""
    }

    fun setRealDebridKey(context: Context, key: String) {
        getPrefs(context).edit().putString(KEY_RD_KEY, key.trim()).apply()
    }

    fun getCustomAddonUrl(context: Context): String {
        return getPrefs(context).getString(KEY_CUSTOM_ADDON_URL, "") ?: ""
    }

    fun setCustomAddonUrl(context: Context, url: String) {
        val clean = url.trim().removeSuffix("/manifest.json").removeSuffix("/")
        getPrefs(context).edit().putString(KEY_CUSTOM_ADDON_URL, clean).apply()
    }

    fun getPreferredQuality(context: Context): String {
        return getPrefs(context).getString(KEY_PREFERRED_QUALITY, "auto") ?: "auto"
    }

    fun setPreferredQuality(context: Context, quality: String) {
        getPrefs(context).edit().putString(KEY_PREFERRED_QUALITY, quality).apply()
    }

    fun getEffectiveAddonBaseUrl(context: Context): String {
        val custom = getCustomAddonUrl(context)
        if (custom.isNotEmpty() && (custom.startsWith("http://") || custom.startsWith("https://"))) {
            return custom.removeSuffix("/")
        }
        val rdKey = getRealDebridKey(context)
        if (rdKey.isNotEmpty()) {
            return "https://torrentio.strem.fun/realdebrid=$rdKey"
        }
        return DEFAULT_ADDON_URL
    }
}
