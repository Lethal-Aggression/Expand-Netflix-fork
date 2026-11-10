package com.d4cx.streamflix

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

object WatchHistoryManager {
    private const val TAG = "WatchHistoryManager"
    private const val PREFS_NAME = "jewflix_watch_history"

    private const val PREFIX_POS = "pos_"
    private const val PREFIX_DUR = "dur_"
    private const val PREFIX_WATCHED = "watched_"
    private const val PREFIX_TIMESTAMP = "ts_"
    private const val KEY_CONTINUE_LIST = "continue_watching_json"
    private const val KEY_LIBRARY_LIST = "library_watchlist_json"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private fun buildKey(type: String, id: String, season: Int, episode: Int): String {
        val sanitizedId = id.replace(":", "_")
        return if (type.equals("movie", ignoreCase = true)) {
            "movie_$sanitizedId"
        } else {
            "series_${sanitizedId}_s${season}_e$episode"
        }
    }

    fun savePlaybackPosition(
        context: Context,
        type: String,
        id: String,
        season: Int,
        episode: Int,
        positionMs: Long,
        durationMs: Long,
        title: String = "",
        poster: String = "",
        backdrop: String = "",
        episodeTitle: String = ""
    ) {
        if (id.isEmpty()) return
        val prefs = getPrefs(context)
        val key = buildKey(type, id, season, episode)
        val now = System.currentTimeMillis()

        val isCompleted = durationMs > 60_000 && (
            (positionMs.toDouble() / durationMs.toDouble() >= 0.90) ||
            (durationMs - positionMs <= 60_000)
        )

        val editor = prefs.edit()
        if (isCompleted) {
            editor.putBoolean(PREFIX_WATCHED + key, true)
            editor.putLong(PREFIX_POS + key, 0L)
            editor.putLong(PREFIX_DUR + key, durationMs)
            editor.putLong(PREFIX_TIMESTAMP + key, now)
            Log.d(TAG, "Item $key marked as WATCHED.")
        } else if (positionMs > 8_000) {
            editor.putLong(PREFIX_POS + key, positionMs)
            editor.putLong(PREFIX_DUR + key, durationMs)
            editor.putLong(PREFIX_TIMESTAMP + key, now)
        }
        editor.apply()

        if (title.isNotEmpty() || durationMs > 0) {
            updateContinueIndex(
                context = context,
                id = id,
                type = type,
                title = title,
                poster = poster,
                backdrop = backdrop,
                season = season,
                episode = episode,
                episodeTitle = episodeTitle,
                positionMs = if (isCompleted) 0L else positionMs,
                durationMs = durationMs,
                isCompleted = isCompleted,
                timestamp = now
            )
        }
    }

    @Synchronized
    private fun updateContinueIndex(
        context: Context,
        id: String,
        type: String,
        title: String,
        poster: String,
        backdrop: String,
        season: Int,
        episode: Int,
        episodeTitle: String,
        positionMs: Long,
        durationMs: Long,
        isCompleted: Boolean,
        timestamp: Long
    ) {
        if (id.isEmpty()) return
        val prefs = getPrefs(context)
        val list = getContinueWatching(context).toMutableList()
        val prev = list.firstOrNull { it.id == id }

        list.removeAll { it.id == id }

        // Remove from Continue Watching when a movie is completed
        if (isCompleted && type.equals("movie", ignoreCase = true)) {
            persistContinueList(prefs, list)
            return
        }

        // For series, advance pointer hint to next episode when current episode finishes, or keep active position
        val nextEp = if (isCompleted) episode + 1 else episode
        val effectivePos = if (isCompleted) 0L else positionMs
        val effectiveDur = if (isCompleted) 0L else durationMs
        val cleanTitle = title.ifEmpty { prev?.title ?: id }
        val cleanPoster = poster.ifEmpty { prev?.poster ?: "https://images.metahub.space/poster/small/$id/img" }
        val cleanBackdrop = backdrop.ifEmpty {
            prev?.backdrop ?: if (id.startsWith("tt")) "https://images.metahub.space/background/medium/$id/img" else cleanPoster
        }
        val cleanEpTitle = if (isCompleted) "Up Next: Episode $nextEp" else episodeTitle.ifEmpty { prev?.episodeTitle ?: "" }

        if (effectivePos > 8_000 || isCompleted) {
            list.add(
                0,
                ContinueWatchingItem(
                    id = id,
                    type = type,
                    title = cleanTitle,
                    poster = cleanPoster,
                    backdrop = cleanBackdrop,
                    season = season,
                    episode = nextEp,
                    episodeTitle = cleanEpTitle,
                    positionMs = effectivePos,
                    durationMs = effectiveDur,
                    timestamp = timestamp
                )
            )
        }

        if (list.size > 16) {
            list.subList(16, list.size).clear()
        }
        persistContinueList(prefs, list)
    }

    private fun persistContinueList(prefs: SharedPreferences, list: List<ContinueWatchingItem>) {
        val arr = JSONArray()
        for (item in list) {
            arr.put(JSONObject().apply {
                put("id", item.id)
                put("type", item.type)
                put("title", item.title)
                put("poster", item.poster)
                put("backdrop", item.backdrop)
                put("season", item.season)
                put("episode", item.episode)
                put("episodeTitle", item.episodeTitle)
                put("positionMs", item.positionMs)
                put("durationMs", item.durationMs)
                put("timestamp", item.timestamp)
            })
        }
        prefs.edit().putString(KEY_CONTINUE_LIST, arr.toString()).apply()
    }

    @Synchronized
    fun getContinueWatching(context: Context): List<ContinueWatchingItem> {
        val prefs = getPrefs(context)
        val raw = prefs.getString(KEY_CONTINUE_LIST, null) ?: return emptyList()
        val out = mutableListOf<ContinueWatchingItem>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id")
                if (id.isEmpty()) continue
                out.add(
                    ContinueWatchingItem(
                        id = id,
                        type = o.optString("type", "movie"),
                        title = o.optString("title", ""),
                        poster = o.optString("poster", ""),
                        backdrop = o.optString("backdrop", ""),
                        season = o.optInt("season", 1),
                        episode = o.optInt("episode", 1),
                        episodeTitle = o.optString("episodeTitle", ""),
                        positionMs = o.optLong("positionMs", 0L),
                        durationMs = o.optLong("durationMs", 0L),
                        timestamp = o.optLong("timestamp", 0L)
                    )
                )
            }
        } catch (_: Exception) {}
        return out.sortedByDescending { it.timestamp }
    }

    fun getLatestForSeries(context: Context, seriesId: String): ContinueWatchingItem? {
        return getContinueWatching(context).firstOrNull { it.id == seriesId }
    }

    fun removeFromContinueWatching(context: Context, id: String) {
        val prefs = getPrefs(context)
        val list = getContinueWatching(context).filterNot { it.id == id }
        persistContinueList(prefs, list)
    }

    // ====================================================================
    // LIBRARY / WATCHLIST BOOKMARKS
    // ====================================================================

    @Synchronized
    fun isInLibrary(context: Context, id: String): Boolean {
        if (id.isEmpty()) return false
        return getLibraryItems(context).any { it.id == id }
    }

    @Synchronized
    fun toggleLibrary(context: Context, item: CatalogItem): Boolean {
        val prefs = getPrefs(context)
        val current = getLibraryItems(context).toMutableList()
        val exists = current.any { it.id == item.id }
        if (exists) {
            current.removeAll { it.id == item.id }
        } else {
            current.add(0, item)
        }
        val arr = JSONArray()
        for (it in current) {
            arr.put(JSONObject().apply {
                put("id", it.id)
                put("name", it.name)
                put("type", it.type)
                put("year", it.year)
                put("poster", it.poster)
                put("imdbRating", it.imdbRating ?: "")
                put("description", it.description ?: "")
                put("backdrop", it.backdrop ?: "")
            })
        }
        prefs.edit().putString(KEY_LIBRARY_LIST, arr.toString()).apply()
        return !exists
    }

    @Synchronized
    fun getLibraryItems(context: Context): List<CatalogItem> {
        val prefs = getPrefs(context)
        val raw = prefs.getString(KEY_LIBRARY_LIST, null) ?: return emptyList()
        val list = mutableListOf<CatalogItem>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id")
                if (id.isEmpty()) continue
                list.add(
                    CatalogItem(
                        id = id,
                        name = o.optString("name", ""),
                        type = o.optString("type", "movie"),
                        year = o.optString("year", ""),
                        poster = o.optString("poster", ""),
                        imdbRating = o.optString("imdbRating").ifEmpty { null },
                        description = o.optString("description").ifEmpty { null },
                        backdrop = o.optString("backdrop").ifEmpty { null }
                    )
                )
            }
        } catch (_: Exception) {}
        return list
    }

    // ====================================================================
    // INDIVIDUAL EPISODE / MOVIE STATE HELPERS
    // ====================================================================

    fun getPlaybackPosition(
        context: Context,
        type: String,
        id: String,
        season: Int,
        episode: Int
    ): Long {
        if (id.isEmpty()) return 0L
        return getPrefs(context).getLong(PREFIX_POS + buildKey(type, id, season, episode), 0L)
    }

    fun getDuration(
        context: Context,
        type: String,
        id: String,
        season: Int,
        episode: Int
    ): Long {
        if (id.isEmpty()) return 0L
        return getPrefs(context).getLong(PREFIX_DUR + buildKey(type, id, season, episode), 0L)
    }

    fun getResumeRatio(
        context: Context,
        type: String,
        id: String,
        season: Int,
        episode: Int
    ): Float {
        val pos = getPlaybackPosition(context, type, id, season, episode)
        val dur = getDuration(context, type, id, season, episode)
        if (dur <= 0 || pos <= 0) return 0f
        return (pos.toFloat() / dur.toFloat()).coerceIn(0f, 1f)
    }

    fun isWatched(
        context: Context,
        type: String,
        id: String,
        season: Int,
        episode: Int
    ): Boolean {
        if (id.isEmpty()) return false
        return getPrefs(context).getBoolean(PREFIX_WATCHED + buildKey(type, id, season, episode), false)
    }

    fun markWatched(
        context: Context,
        type: String,
        id: String,
        season: Int,
        episode: Int,
        watched: Boolean
    ) {
        if (id.isEmpty()) return
        val prefs = getPrefs(context)
        val key = buildKey(type, id, season, episode)
        prefs.edit()
            .putBoolean(PREFIX_WATCHED + key, watched)
            .putLong(PREFIX_POS + key, 0L)
            .apply()
    }
}
