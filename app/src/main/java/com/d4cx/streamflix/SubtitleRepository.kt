package com.d4cx.streamflix

import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

data class SubtitleItem(
    val id: String,
    val langCode: String,
    val langLabel: String,
    val url: String
)

object SubtitleRepository {
    private const val TAG = "SubtitleRepository"

    private val languageNames = mapOf(
        "eng" to "English",
        "jpn" to "Japanese (日本語)",
        "spa" to "Spanish (Español)",
        "fre" to "French (Français)",
        "fra" to "French (Français)",
        "ger" to "German (Deutsch)",
        "deu" to "German (Deutsch)",
        "ita" to "Italian (Italiano)",
        "por" to "Portuguese (Português)",
        "pob" to "Portuguese (BR)",
        "rus" to "Russian (Русский)",
        "ara" to "Arabic (العربية)",
        "chi" to "Chinese (中文)",
        "zho" to "Chinese (中文)",
        "kor" to "Korean (한국어)",
        "tur" to "Turkish (Türkçe)",
        "pol" to "Polish (Polski)",
        "dut" to "Dutch (Nederlands)",
        "nld" to "Dutch (Nederlands)",
        "ind" to "Indonesian",
        "hin" to "Hindi",
        "swe" to "Swedish",
        "nor" to "Norwegian",
        "dan" to "Danish",
        "fin" to "Finnish",
        "gre" to "Greek",
        "ell" to "Greek",
        "heb" to "Hebrew",
        "hun" to "Hungarian",
        "ces" to "Czech",
        "cze" to "Czech",
        "ron" to "Romanian",
        "rum" to "Romanian",
        "tha" to "Thai",
        "vie" to "Vietnamese"
    )

    fun fetchSubtitles(type: String, id: String, season: Int? = null, episode: Int? = null): List<SubtitleItem> {
        val streamType = if (type == "anime") "series" else type
        val queryId = if (streamType == "series") {
            if (id.contains(":")) {
                id
            } else if (season != null && episode != null) {
                "$id:$season:$episode"
            } else {
                "$id:1:1"
            }
        } else {
            id
        }

        val urlStr = "https://opensubtitles-v3.strem.io/subtitles/$streamType/$queryId.json"
        val jsonStr = httpGet(urlStr, 4500) ?: return emptyList()

        val results = mutableListOf<SubtitleItem>()
        try {
            val root = JSONObject(jsonStr)
            val subsArr = root.optJSONArray("subtitles") ?: return emptyList()
            val langCounts = mutableMapOf<String, Int>()
            val seenUrls = mutableSetOf<String>()

            for (i in 0 until subsArr.length()) {
                val obj = subsArr.getJSONObject(i)
                val subId = obj.optString("id", "$i")
                val lang = obj.optString("lang", "eng").lowercase()
                val url = obj.optString("url", "")
                if (url.isNotEmpty() && !seenUrls.contains(url)) {
                    val count = (langCounts[lang] ?: 0) + 1
                    // Keep up to 4 candidate sync files per language
                    if (count <= 4) {
                        seenUrls.add(url)
                        langCounts[lang] = count
                        val baseName = languageNames[lang] ?: lang.uppercase()
                        val displayName = if (count == 1) baseName else "$baseName #$count"
                        results.add(SubtitleItem(subId, lang, displayName, url))
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse subtitles: ${e.message}")
        }

        return results.sortedWith(compareBy({
            when (it.langCode) {
                "eng" -> 0
                "ara" -> 1
                "jpn" -> 2
                "spa" -> 3
                "fre", "fra" -> 4
                "ger", "deu" -> 5
                "ita" -> 6
                "por", "pob" -> 7
                "rus" -> 8
                else -> 9
            }
        }, { it.langLabel }))
    }

    private fun httpGet(urlString: String, timeoutMs: Int = 4500): String? {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL(urlString)
            conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "JewFlix/2.0 (Android TV)")
            conn.setRequestProperty("Accept", "application/json")

            if (conn.responseCode in 200..299) {
                val reader = BufferedReader(InputStreamReader(conn.inputStream))
                val sb = StringBuilder()
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    sb.append(line)
                }
                reader.close()
                sb.toString()
            } else {
                null
            }
        } catch (_: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }
}
