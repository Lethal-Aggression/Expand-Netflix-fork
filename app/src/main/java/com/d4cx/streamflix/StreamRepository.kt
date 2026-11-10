package com.d4cx.streamflix

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.regex.Pattern

object StreamRepository {
    private const val TAG = "StreamRepository"

    fun fetchStreams(
        type: String,
        id: String,
        season: Int? = null,
        episode: Int? = null,
        context: Context? = null
    ): List<StreamItem> {
        val streamType = if (type.equals("anime", true)) "series" else type
        val queryId = if (streamType == "series") {
            if (id.startsWith("kitsu:")) {
                if (id.count { it == ':' } >= 2) id else "$id:${episode ?: 1}"
            } else if (id.contains(":")) {
                id
            } else if (season != null && episode != null) {
                "$id:$season:$episode"
            } else {
                "$id:1:1"
            }
        } else {
            id
        }

        val streams = mutableListOf<StreamItem>()

        // 0. Sovereign Arabic Dub Archive for SpongeBob, Gumball, OTGW, Regular Show, Adventure Time, Courage
        val arabicStreams = resolveArabicDubStreams(id, season, episode, type)
        streams.addAll(arabicStreams)

        // 1. Primary configured Addon / Torrentio (supports custom URL or Real-Debrid config)
        val baseUrl = if (context != null) {
            SettingsManager.getEffectiveAddonBaseUrl(context)
        } else {
            SettingsManager.DEFAULT_ADDON_URL
        }
        val torrentioUrl = "$baseUrl/stream/$streamType/$queryId.json"
        val jsonStr = httpGet(torrentioUrl, 5500)
        if (jsonStr != null) {
            parseStreamJson(jsonStr, streams)
        }

        // 2. Fallback to Torrentio public default if custom addon yielded zero streams
        if (streams.isEmpty() && baseUrl != SettingsManager.DEFAULT_ADDON_URL) {
            httpGet("${SettingsManager.DEFAULT_ADDON_URL}/stream/$streamType/$queryId.json", 4500)?.let {
                parseStreamJson(it, streams)
            }
        }

        // 3. Fallback for Anime Kitsu
        if (streams.isEmpty() && (type == "series" || type == "anime")) {
            httpGet("https://anime-kitsu.strem.fun/stream/series/$queryId.json", 4000)?.let {
                parseStreamJson(it, streams)
            }
        }

        return streams.sortedByDescending { it.streamScore() }
    }

    private fun parseStreamJson(jsonStr: String, outList: MutableList<StreamItem>) {
        try {
            val root = JSONObject(jsonStr)
            val arr = root.optJSONArray("streams") ?: return
            val seedPattern = Pattern.compile("👤\\s*(\\d+)")
            val sizePattern = Pattern.compile("💾\\s*([\\d\\.]+\\s*[GMKT]B)", Pattern.CASE_INSENSITIVE)

            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val name = obj.optString("name", "Torrentio")
                val title = obj.optString("title", "")
                val infoHash = obj.optString("infoHash").ifEmpty { null }
                val fileIdx = if (obj.has("fileIdx")) obj.optInt("fileIdx") else null
                val url = obj.optString("url").ifEmpty { null }

                val sources = mutableListOf<String>()
                obj.optJSONArray("sources")?.let { sourcesArr ->
                    for (j in 0 until sourcesArr.length()) {
                        sources.add(sourcesArr.getString(j))
                    }
                }

                var seeders = 0
                val seedMatcher = seedPattern.matcher(title)
                if (seedMatcher.find()) {
                    seeders = seedMatcher.group(1)?.toIntOrNull() ?: 0
                }

                var sizeText = ""
                val sizeMatcher = sizePattern.matcher(title)
                if (sizeMatcher.find()) {
                    sizeText = sizeMatcher.group(1) ?: ""
                }

                val upperTitle = ("$name $title").uppercase(Locale.US)
                val quality = when {
                    upperTitle.contains("4K") || upperTitle.contains("2160P") || upperTitle.contains("UHD") -> {
                        when {
                            upperTitle.contains("HDR10+") -> "4K HDR10+"
                            upperTitle.contains("HDR") -> "4K HDR10"
                            upperTitle.contains("DV") || upperTitle.contains("DOLBY VISION") -> "4K DV→HDR"
                            else -> "4K"
                        }
                    }
                    upperTitle.contains("2K") || upperTitle.contains("1440P") -> "1440p"
                    upperTitle.contains("1080P") || upperTitle.contains("FHD") -> "1080p"
                    upperTitle.contains("720P") -> "720p"
                    else -> "HD"
                }

                val firstLineTitle = title.lineSequence().firstOrNull()?.trim()?.take(88) ?: name
                val tags = mutableListOf<String>()
                when {
                    upperTitle.contains("REMUX") -> tags.add("REMUX")
                    upperTitle.contains("BLURAY") -> tags.add("BluRay")
                    upperTitle.contains("WEB-DL") || upperTitle.contains("WEBRIP") -> tags.add("WEB-DL")
                }
                if (upperTitle.contains("HEVC") || upperTitle.contains("X265") || upperTitle.contains("H265")) {
                    tags.add("HEVC 10-bit")
                } else if (upperTitle.contains("X264") || upperTitle.contains("H264") || upperTitle.contains("AVC")) {
                    tags.add("H.264")
                }
                when {
                    upperTitle.contains("ATMOS") -> tags.add("Atmos")
                    upperTitle.contains("DDP") || upperTitle.contains("EAC3") -> tags.add("DDP 5.1")
                    upperTitle.contains("TRUEHD") -> tags.add("TrueHD 7.1")
                    upperTitle.contains("AAC") -> tags.add("AAC")
                }
                val hasArabic = upperTitle.contains("ARA") || upperTitle.contains("ARABIC") ||
                        upperTitle.contains("SUB") || upperTitle.contains("MULTI")

                outList.add(
                    StreamItem(
                        name = name,
                        title = title,
                        infoHash = infoHash,
                        fileIdx = fileIdx,
                        url = url,
                        sources = sources,
                        quality = quality,
                        seeders = seeders,
                        sizeText = sizeText,
                        releaseTitle = firstLineTitle,
                        codecTags = tags.joinToString(" • ").ifEmpty { "Direct Container" },
                        hasArabicHint = hasArabic,
                        isDirect = url != null
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing streams JSON: ${e.message}")
        }
    }

    private fun resolveArabicDubStreams(
        id: String,
        season: Int?,
        episode: Int?,
        type: String = "series"
    ): List<StreamItem> {
        val s = season ?: 1
        val e = episode ?: 1
        val results = mutableListOf<StreamItem>()

        if (id.contains("tt0206512") && s in 1..12) {
            val fileNum = if (s == 2) e.coerceIn(1, 38) else if (e > 25) (e + 1) / 2 else e
            val directUrl = String.format(Locale.US, "https://archive.org/download/Spongebob.Squarepants.S%02d/%02d.mp4", s, fileNum)
            results.add(
                StreamItem(
                    name = "Archive.org Arabic Dub",
                    title = "سبونج بوب سكوير بانتس - الموسم $s الحلقة $e [Original Arabic Audio]",
                    url = directUrl,
                    quality = "1080p",
                    seeders = 99999,
                    sizeText = "200 MB",
                    releaseTitle = "SpongeBob.Arabic.Dub.S%02dE%02d.Archive".format(s, fileNum),
                    codecTags = "Direct Stream • Arabic Audio • Instant",
                    hasArabicHint = true,
                    isDirect = true
                )
            )
            val altNum = (e + 1) / 2
            if (s != 2 && altNum != fileNum && altNum in 1..30) {
                val altUrl = String.format(Locale.US, "https://archive.org/download/Spongebob.Squarepants.S%02d/%02d.mp4", s, altNum)
                results.add(
                    StreamItem(
                        name = "Archive.org Arabic Dub (Alt)",
                        title = "سبونج بوب سكوير بانتس - الموسم $s البث $altNum [Original Arabic Audio]",
                        url = altUrl,
                        quality = "1080p",
                        seeders = 99998,
                        sizeText = "200 MB",
                        releaseTitle = "SpongeBob.Arabic.Broadcast.S%02d.%02d".format(s, altNum),
                        codecTags = "Direct Stream • Arabic Audio",
                        hasArabicHint = true,
                        isDirect = true
                    )
                )
            }
        }

        if (id.contains("tt1942683")) {
            val epNum = if (s > 1) ((s - 1) * 36 + e).coerceIn(1, 48) else e.coerceIn(1, 48)
            val epStr = String.format(Locale.US, "%02d", epNum)
            val fileName = "عالم غامبول المدهش مدبلج الحلقة $epStr.mp4"
            val encodedName = URLEncoder.encode(fileName, "UTF-8").replace("+", "%20")
            results.add(
                StreamItem(
                    name = "Cartoon Network Arabic",
                    title = "عالم غامبول المدهش - الموسم $s الحلقة $e [Original CN Arabic Dub]",
                    url = "https://archive.org/download/03_20251029/$encodedName",
                    quality = "1080p",
                    seeders = 99999,
                    sizeText = "150 MB",
                    releaseTitle = "Gumball.CN.Arabic.Dub.Ep$epStr.mp4",
                    codecTags = "Direct Stream • Official CN Arabic Dub",
                    hasArabicHint = true,
                    isDirect = true
                )
            )
        }

        if (id.contains("tt3718778")) {
            val cnMovieUrl = "https://archive.org/download/20261003_20261003_1042/%D8%AE%D9%84%D9%81%20%D8%AD%D8%A7%D8%A6%D8%B7%20%D8%A7%D9%84%D8%AD%D8%AF%D9%8A%D9%82%D8%A9%20%D9%83%D8%B1%D8%AA%D9%88%D9%86%20%D9%86%D8%AA%D9%88%D8%B1%D9%83%20%D8%A8%D8%A7%D9%84%D8%B9%D8%B1%D8%A8%D9%8A%D8%A9%20%D8%A3%D9%81%D9%84%D8%A7%D9%85.mp4"
            if (type == "movie") {
                results.add(
                    StreamItem(
                        name = "Cartoon Network Full Movie",
                        title = "خلف حائط الحديقة - الفيلم الكامل 78 دقيقة [Original Arabic Dub]",
                        url = cnMovieUrl,
                        quality = "1080p",
                        seeders = 99999,
                        sizeText = "664 MB",
                        releaseTitle = "Over.the.Garden.Wall.CN.Arabic.78min.mp4",
                        codecTags = "Direct Stream • Official CN Arabic Dub",
                        hasArabicHint = true,
                        isDirect = true
                    )
                )
            } else {
                if (e == 1) {
                    val ep1ArabicUrl = "https://archive.org/download/1_20250908_20250908_1942/%D8%AE%D9%84%D9%81%20%D8%AD%D8%A7%D8%A6%D8%B7%20%D8%A7%D9%84%D8%AD%D8%AF%D9%8A%D9%82%D8%A91.mp4"
                    results.add(
                        StreamItem(
                            name = "1080p BluRay + Ep 1 Arabic Dub",
                            title = "خلف حائط الحديقة - S01E01 [1080p BluRay + Arabic Audio]",
                            infoHash = "f6dce1236321031311ccdc5d36c4a99a1eb733a3",
                            fileIdx = 4,
                            audioUrl = ep1ArabicUrl,
                            quality = "1080p",
                            seeders = 99999,
                            sizeText = "200 MB",
                            releaseTitle = "Over.the.Garden.Wall.S01E01.BluRay+Arabic",
                            codecTags = "Dual Mux • BluRay Video + CN Arabic Audio",
                            hasArabicHint = true
                        )
                    )
                }
                results.add(
                    StreamItem(
                        name = "Cartoon Network Full Movie",
                        title = "خلف حائط الحديقة - الفيلم الكامل 78 دقيقة [Original Arabic Dub]",
                        url = cnMovieUrl,
                        quality = "1080p",
                        seeders = 99990,
                        sizeText = "664 MB",
                        releaseTitle = "Over.the.Garden.Wall.CN.Arabic.78min.mp4",
                        codecTags = "Direct Stream • Official CN Arabic Dub",
                        hasArabicHint = true,
                        isDirect = true
                    )
                )
            }
        }

        if (id.contains("tt4938232") || (id.contains("tt1710308") && type == "movie")) {
            val regularShowMovieUrl = "https://archive.org/download/20261017_20261017_0821/%D8%A7%D9%84%D8%B9%D8%B1%D8%B6%20%D8%A7%D9%84%D8%B9%D8%A7%D8%AF%D9%8A%20%D8%A7%D9%84%D9%81%D9%8A%D9%84%D9%85%20%D9%83%D8%B1%D8%AA%D9%88%D9%86%20%D9%86%D8%AA%D9%88%D8%B1%D9%83%20%D8%A8%D8%A7%D9%84%D8%B9%D8%B1%D8%A8%D9%8A%D8%A9%20%D8%A3%D9%81%D9%84%D8%A7%D9%85.mp4"
            results.add(
                StreamItem(
                    name = "Cartoon Network Arabic Film",
                    title = "العرض العادي: الفيلم الكامل 65 دقيقة [Original Arabic Dub]",
                    url = regularShowMovieUrl,
                    quality = "1080p",
                    seeders = 99999,
                    sizeText = "548 MB",
                    releaseTitle = "Regular.Show.The.Movie.CN.Arabic.65min.mp4",
                    codecTags = "Direct Stream • Official CN Arabic Dub",
                    hasArabicHint = true,
                    isDirect = true
                )
            )
        }

        if (id.contains("tt1305826") && s == 1 && e == 1) {
            results.add(
                StreamItem(
                    name = "Cartoon Network Arabic",
                    title = "وقت المغامرة - س01 ح01 [Original Arabic Dub]",
                    url = "https://archive.org/download/adventure-time-s-01e-01-dub-star-dima-1/Adventure_Time_S01e01_dub_%5BStarDima%5D.mp4",
                    quality = "1080p",
                    seeders = 99999,
                    sizeText = "272 MB",
                    releaseTitle = "Adventure.Time.S01E01.CN.Arabic.mp4",
                    codecTags = "Direct Stream • Arabic Dub",
                    hasArabicHint = true,
                    isDirect = true
                )
            )
        }

        if (id.contains("tt0220880")) {
            val epNum = e.coerceIn(1, 32)
            results.add(
                StreamItem(
                    name = "Cartoon Network Arabic Ep %02d".format(epNum),
                    title = "كوردج الجبان - حلقة %02d [Original Arabic Dub]".format(epNum),
                    url = "https://archive.org/download/courage_aljban_25/courage_aljban_%02d.mp4".format(epNum),
                    quality = "1080p",
                    seeders = 99999,
                    sizeText = "72 MB",
                    releaseTitle = "Courage.Cowardly.Dog.Ep%02d.CN.Arabic.mp4".format(epNum),
                    codecTags = "Direct Stream • Official CN Arabic Dub",
                    hasArabicHint = true,
                    isDirect = true
                )
            )
        }

        return results
    }

    private fun StreamItem.streamScore(): Int {
        if (audioUrl != null || url?.contains("archive.org") == true) return 100000
        if (isDirect && seeders == 0) return 95000 // Real-Debrid direct HTTPS streams have instant playback!
        if (seeders <= 0) return -10000

        val combined = "$title $name".lowercase(Locale.US)
        val isSus = combined.contains("peerless") || combined.contains("绝世") || combined.contains("camrip") || combined.contains("telesync")
        if (isSus) return -50000

        val parsedGb = sizeText.lowercase(Locale.US).let { s ->
            if (s.contains("gb")) s.replace("gb", "").trim().toDoubleOrNull() ?: 0.0
            else if (s.contains("mb")) (s.replace("mb", "").trim().toDoubleOrNull() ?: 0.0) / 1024.0
            else 0.0
        }

        val isRemux = combined.contains("remux")
        val isWebDl = combined.contains("web-dl") || combined.contains("webrip") || combined.contains("web")
        val isBluray = combined.contains("bluray") || combined.contains("bdrip") || combined.contains("brrip")

        // 1. Base score: 1080p is calibrated as the golden standard for Android TV streaming
        val baseQuality = when {
            quality == "1080p" -> 9200
            quality == "1440p" || quality == "2K" -> 8800
            quality.startsWith("4K") -> {
                // Only grant high score to 4K if file size is compact (< 6.5 GB) with strong seeders
                if (parsedGb in 0.1..6.5 && seeders >= 25) 8600 else 3500
            }
            quality == "720p" -> 6500
            quality == "HD" -> 5000
            else -> 4000
        }

        // 2. Seeder health: rewards stable swarms, caps artificial 1k-peer swarms
        val seederScore = when {
            seeders >= 100 -> 3500
            seeders >= 50 -> 2800
            seeders >= 20 -> 2000
            seeders >= 8 -> 1000
            seeders >= 3 -> 200
            else -> -3000
        }

        // 3. Size balance: 1.0 GB - 4.5 GB is the sweet-spot that never buffers on TV Wi-Fi
        var sizeScore = 0
        when {
            parsedGb in 1.0..4.5 -> sizeScore += 2500
            parsedGb in 4.5..8.0 -> sizeScore += 500
            parsedGb in 8.0..14.0 -> sizeScore -= 5000
            parsedGb in 14.0..25.0 -> sizeScore -= 15000
            parsedGb > 25.0 -> sizeScore -= 30000 // Colossal 30-70GB files cause severe buffer starvation
        }

        val formatBonus = when {
            isWebDl -> 1200
            isBluray -> 1000
            else -> 0
        }
        val remuxPenalty = if (isRemux) -10000 else 0

        return baseQuality + seederScore + sizeScore + formatBonus + remuxPenalty
    }

    fun resolvePlayableUrl(stream: StreamItem): String {
        if (!stream.url.isNullOrEmpty()) return stream.url
        val hash = stream.infoHash ?: return ""
        val idx = stream.fileIdx ?: 0
        val sb = StringBuilder("http://127.0.0.1:${JewEngineManager.ENGINE_PORT}/stream?hash=$hash&idx=$idx")
        if (stream.sources.isNotEmpty()) {
            val cleanTrackers = stream.sources.mapNotNull { src ->
                val clean = src.removePrefix("tracker:").trim()
                if (clean.startsWith("udp://") || clean.startsWith("http://") || clean.startsWith("https://")) clean else null
            }
            if (cleanTrackers.isNotEmpty()) {
                val trackersQuery = cleanTrackers.take(16).joinToString("&") { src ->
                    "tr=" + URLEncoder.encode(src, "UTF-8")
                }
                sb.append("&").append(trackersQuery)
            }
        }
        return sb.toString()
    }

    fun httpGet(urlString: String, timeoutMs: Int = 4500): String? {
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
                while (reader.readLine().also { line = it } != null) sb.append(line)
                reader.close()
                sb.toString()
            } else null
        } catch (_: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }
}
