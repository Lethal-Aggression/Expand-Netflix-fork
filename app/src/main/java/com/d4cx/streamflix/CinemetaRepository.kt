package com.d4cx.streamflix

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future

object CinemetaRepository {

    private val searchCache = ConcurrentHashMap<String, List<CatalogItem>>()
    private val catalogCache = ConcurrentHashMap<String, List<CatalogItem>>()
    private val metaCache = ConcurrentHashMap<String, DeepMeta>()
    private val executor = Executors.newFixedThreadPool(5)

    fun getCuratedCartoons(): List<CatalogItem> = listOf(
        CatalogItem(
            id = "tt3718778",
            name = "Over the Garden Wall (خلف حائط الحديقة)",
            type = "series",
            year = "2014",
            poster = "https://images.metahub.space/poster/small/tt3718778/img",
            backdrop = "https://images.metahub.space/background/medium/tt3718778/img",
            imdbRating = "8.8",
            description = "Two brothers lose themselves in a strange forest filled with wandering souls. Includes original Arabic Cartoon Network film & 1080p BluRay episodes.",
            genres = listOf("Animation", "Fantasy", "Adventure")
        ),
        CatalogItem(
            id = "tt0206512",
            name = "SpongeBob SquarePants (سبونج بوب)",
            type = "series",
            year = "1999–",
            poster = "https://images.metahub.space/poster/small/tt0206512/img",
            backdrop = "https://images.metahub.space/background/medium/tt0206512/img",
            imdbRating = "8.2",
            description = "Full SpongeBob seasons S01–S12 streaming instantaneously with original Arabic dub audio.",
            genres = listOf("Animation", "Comedy")
        ),
        CatalogItem(
            id = "tt1942683",
            name = "The Amazing World of Gumball (عالم غامبول المدهش)",
            type = "series",
            year = "2011–2019",
            poster = "https://images.metahub.space/poster/small/tt1942683/img",
            backdrop = "https://images.metahub.space/background/medium/tt1942683/img",
            imdbRating = "8.4",
            description = "Gumball and Darwin's misadventures in Elmore with full original Cartoon Network Arabic audio.",
            genres = listOf("Animation", "Comedy")
        ),
        CatalogItem(
            id = "tt1710308",
            name = "Regular Show (العرض العادي)",
            type = "series",
            year = "2010–2017",
            poster = "https://images.metahub.space/poster/small/tt1710308/img",
            backdrop = "https://images.metahub.space/background/medium/tt1710308/img",
            imdbRating = "8.6",
            description = "Mordecai and Rigby turn mundane park chores into surreal cosmic disasters.",
            genres = listOf("Animation", "Comedy")
        ),
        CatalogItem(
            id = "tt4938232",
            name = "Regular Show: The Movie (العرض العادي: الفيلم)",
            type = "movie",
            year = "2015",
            poster = "https://images.metahub.space/poster/small/tt4938232/img",
            backdrop = "https://images.metahub.space/background/medium/tt4938232/img",
            imdbRating = "7.4",
            description = "Full 65-minute feature film in original official Cartoon Network Arabic dub.",
            genres = listOf("Animation", "Adventure")
        ),
        CatalogItem(
            id = "tt1305826",
            name = "Adventure Time (وقت المغامرة)",
            type = "series",
            year = "2010–2018",
            poster = "https://images.metahub.space/poster/small/tt1305826/img",
            backdrop = "https://images.metahub.space/background/medium/tt1305826/img",
            imdbRating = "8.6",
            description = "Finn the Human and Jake the Dog traverse the post-apocalyptic Land of Ooo.",
            genres = listOf("Animation", "Fantasy")
        ),
        CatalogItem(
            id = "tt0220880",
            name = "Courage the Cowardly Dog (كوردج الجبان)",
            type = "series",
            year = "1999–2002",
            poster = "https://images.metahub.space/poster/small/tt0220880/img",
            backdrop = "https://images.metahub.space/background/medium/tt0220880/img",
            imdbRating = "8.3",
            description = "All 32 classic Arabic Cartoon Network dubbed episodes of Courage protecting Muriel & Eustace.",
            genres = listOf("Animation", "Mystery")
        ),
        CatalogItem(
            id = "tt1178180",
            name = "The Marvelous Misadventures of Flapjack (فلاب جاك)",
            type = "series",
            year = "2008–2010",
            poster = "https://images.metahub.space/poster/small/tt1178180/img",
            backdrop = "https://images.metahub.space/background/medium/tt1178180/img",
            imdbRating = "7.8",
            description = "Flapjack, Captain K'nuckles, and Bubbie search for Candied Island in 1080p.",
            genres = listOf("Animation", "Comedy")
        ),
        CatalogItem(
            id = "tt12158994",
            name = "Tom and Jerry (1940) Classic Collection",
            type = "series",
            year = "1940–1968",
            poster = "https://images.metahub.space/poster/small/tt12158994/img",
            backdrop = "https://images.metahub.space/background/medium/tt12158994/img",
            imdbRating = "9.0",
            description = "Complete Golden Collection 177 shorts in remastered 1080p BluRay.",
            genres = listOf("Animation", "Classic")
        )
    )

    fun fetchCatalog(type: String): List<CatalogItem> {
        return fetchCatalogFiltered(type, "Popular")
    }

    /**
     * Fetches Cinemeta / Kitsu / Curated catalogs filtered by Category tab + Genre chip.
     * Categories: "all", "movie", "series", "anime", "cartoons"
     * Genres: "Popular", "Action & Sci-Fi", "Thriller", "Comedy", "Drama", "Top Rated"
     */
    fun fetchCatalogFiltered(category: String, genre: String): List<CatalogItem> {
        val cacheKey = "${category.lowercase()}_${genre.lowercase()}"
        catalogCache[cacheKey]?.let { if (it.isNotEmpty()) return it }

        if (category.equals("cartoons", ignoreCase = true)) {
            val out = getCuratedCartoons()
            catalogCache[cacheKey] = out
            return out
        }

        if (category.equals("anime", ignoreCase = true)) {
            val items = fetchKitsuCatalog(genre)
            if (items.isNotEmpty()) catalogCache[cacheKey] = items
            return items
        }

        if (category.equals("all", ignoreCase = true)) {
            val fMov = executor.submit<List<CatalogItem>> { fetchCinemetaSingle("movie", genre) }
            val fSer = executor.submit<List<CatalogItem>> { fetchCinemetaSingle("series", genre) }
            val movies = try { fMov.get() } catch (_: Exception) { emptyList() }
            val series = try { fSer.get() } catch (_: Exception) { emptyList() }
            val interleaved = mutableListOf<CatalogItem>()
            val maxLen = maxOf(movies.size, series.size)
            for (i in 0 until maxLen) {
                if (i < movies.size) interleaved.add(movies[i])
                if (i < series.size) interleaved.add(series[i])
            }
            if (interleaved.isNotEmpty()) catalogCache[cacheKey] = interleaved
            return interleaved
        }

        val normType = if (category.equals("series", ignoreCase = true)) "series" else "movie"
        val items = fetchCinemetaSingle(normType, genre)
        if (items.isNotEmpty()) catalogCache[cacheKey] = items
        return items
    }

    private fun fetchKitsuCatalog(genre: String): List<CatalogItem> {
        val kitsuGenre = when (genre) {
            "Action & Sci-Fi" -> "Action"
            "Thriller" -> "Thriller"
            "Comedy" -> "Comedy"
            "Drama" -> "Drama"
            else -> ""
        }
        val url = if (kitsuGenre.isNotEmpty()) {
            "https://anime-kitsu.strem.fun/catalog/anime/kitsu-anime-list/genre=${URLEncoder.encode(kitsuGenre, "UTF-8")}.json"
        } else {
            "https://anime-kitsu.strem.fun/catalog/anime/kitsu-anime-list.json"
        }
        val json = httpGet(url, 4000) ?: return emptyList()
        return parseMetasJson(json, "series", defaultRating = "8.4")
    }

    private fun fetchCinemetaSingle(type: String, genre: String): List<CatalogItem> {
        val urls = when (genre) {
            "Top Rated" -> listOf(
                "https://v3-cinemeta.strem.io/catalog/$type/imdbRating.json",
                "https://cinemeta-catalogs.strem.io/imdbRating/catalog/$type/imdbRating.json"
            )
            "Action & Sci-Fi" -> listOf(
                "https://v3-cinemeta.strem.io/catalog/$type/top/genre=Action.json",
                "https://v3-cinemeta.strem.io/catalog/$type/top/genre=Sci-Fi.json"
            )
            "Thriller", "Comedy", "Drama" -> listOf(
                "https://v3-cinemeta.strem.io/catalog/$type/top/genre=${URLEncoder.encode(genre, "UTF-8")}.json"
            )
            else -> listOf(
                "https://cinemeta-catalogs.strem.io/top/catalog/$type/top.json",
                "https://v3-cinemeta.strem.io/catalog/$type/top.json"
            )
        }

        var json: String? = null
        for (u in urls) {
            json = httpGet(u, 3500)
            if (!json.isNullOrEmpty()) break
        }
        if (json == null) return emptyList()

        val results = parseMetasJson(json, type).toMutableList()
        if (genre == "Popular") {
            if (type == "movie") {
                val curated = getCuratedCartoons().filter { it.type == "movie" || it.id == "tt3718778" }
                    .map { if (it.id == "tt3718778") it.copy(type = "movie", name = "Over the Garden Wall (الفيلم)") else it }
                val ids = curated.map { it.id }.toSet()
                results.removeAll { it.id in ids }
                results.addAll(0, curated)
            } else if (type == "series") {
                val curated = getCuratedCartoons().filter { it.type == "series" }
                val ids = curated.map { it.id }.toSet()
                results.removeAll { it.id in ids }
                results.addAll(0, curated)
            }
        }
        return results
    }

    private fun parseMetasJson(json: String, fallbackType: String, defaultRating: String? = null): List<CatalogItem> {
        val results = mutableListOf<CatalogItem>()
        try {
            val root = JSONObject(json)
            val metas = root.optJSONArray("metas") ?: JSONArray()
            for (i in 0 until metas.length()) {
                val obj = metas.optJSONObject(i) ?: continue
                val id = obj.optString("id").ifEmpty { obj.optString("imdb_id") }
                if (id.isEmpty()) continue

                val name = obj.optString("name")
                val itemType = obj.optString("type", fallbackType)
                val year = obj.optString("releaseInfo").ifEmpty { obj.optString("year") }
                val poster = obj.optString("poster").ifEmpty {
                    if (id.startsWith("kitsu:")) "https://media.kitsu.app/anime/poster_images/${id.removePrefix("kitsu:")}/medium.jpg"
                    else "https://images.metahub.space/poster/small/$id/img"
                }
                val backdrop = obj.optString("background").ifEmpty {
                    if (id.startsWith("tt")) "https://images.metahub.space/background/medium/$id/img" else null
                }
                val imdbRating = if (obj.has("imdbRating")) obj.optString("imdbRating").ifEmpty { defaultRating } else defaultRating
                val desc = obj.optString("description")
                val genres = mutableListOf<String>()
                obj.optJSONArray("genres")?.let { arr ->
                    for (g in 0 until arr.length()) genres.add(arr.optString(g))
                }

                results.add(
                    CatalogItem(
                        id = id,
                        name = name,
                        type = if (itemType == "anime") "series" else itemType,
                        year = year,
                        poster = poster,
                        imdbRating = imdbRating,
                        description = desc,
                        cinemetaIndex = i,
                        backdrop = backdrop,
                        genres = genres
                    )
                )
            }
        } catch (_: Exception) {}
        return results
    }

    fun search(query: String): List<CatalogItem> {
        val qRaw = query.trim().lowercase()
        if (qRaw.length < 2) return emptyList()

        val qClean = when {
            qRaw.contains("سبونج") || qRaw.contains("سكوير") -> "spongebob"
            qRaw.contains("غامبول") || qRaw.contains("غامبل") -> "gumball"
            qRaw.contains("خلف") || qRaw.contains("حديق") || qRaw.contains("garden wall") -> "over the garden wall"
            qRaw.contains("عادي") || qRaw.contains("موردكاي") || qRaw.contains("ريغبي") || qRaw.contains("regular show") -> "regular show"
            qRaw.contains("مغامر") || qRaw.contains("فين") || qRaw.contains("جيك") || qRaw.contains("adventure time") -> "adventure time"
            qRaw.contains("كوردج") || qRaw.contains("كورج") || qRaw.contains("جبان") || qRaw.contains("courage") -> "courage the cowardly dog"
            qRaw.contains("فلاب") || qRaw.contains("كناكلز") || qRaw.contains("سكاكر") || qRaw.contains("flapjack") -> "flapjack"
            qRaw.contains("توم") || qRaw.contains("جيري") || qRaw.contains("tom and jerry") || qRaw.contains("tom & jerry") -> "tom and jerry 1940"
            qRaw.contains("كونان") || qRaw.contains("conan") -> "detective conan"
            qRaw.contains("ippo") || qRaw.contains("hajime") -> "hajime no ippo"
            qRaw.contains("steel ball") || qRaw.contains("sbr") -> "steel ball run"
            else -> qRaw
        }

        searchCache[qClean]?.let { return it }
        if (Thread.currentThread().isInterrupted) return emptyList()

        val pool = ConcurrentHashMap<String, CatalogItem>()
        val qEncoded = URLEncoder.encode(qClean, "UTF-8")
        val futures = mutableListOf<Future<*>>()

        // A. IMDb Suggestion API
        futures.add(executor.submit {
            if (Thread.currentThread().isInterrupted) return@submit
            try {
                val imdbUrl = "https://v3.sg.media-imdb.com/suggestion/x/$qEncoded.json"
                val json = httpGet(imdbUrl, 2200) ?: return@submit
                val d = JSONObject(json).optJSONArray("d") ?: return@submit
                for (i in 0 until d.length()) {
                    val item = d.optJSONObject(i) ?: continue
                    val id = item.optString("id")
                    if (id.isEmpty()) continue
                    val q = item.optString("q")
                    val isSeries = q.contains("series", true) || q.contains("TV", true)
                    val isMovie = q.contains("feature", true) || q.contains("movie", true)
                    if (!isSeries && !isMovie && q.isNotEmpty()) continue

                    val name = item.optString("l")
                    val year = item.optInt("y", 0).let { if (it > 0) it.toString() else "" }
                    val rank = item.optInt("rank", 999999)
                    val poster = item.optJSONObject("i")?.optString("imageUrl")
                        ?: "https://images.metahub.space/poster/small/$id/img"

                    pool[id] = CatalogItem(
                        id = id,
                        name = name,
                        type = if (isSeries) "series" else "movie",
                        year = year,
                        poster = poster,
                        imdbRank = rank,
                        backdrop = "https://images.metahub.space/background/medium/$id/img"
                    )
                }
            } catch (_: Exception) {}
        })

        // B. Cinemeta Series
        futures.add(executor.submit {
            if (Thread.currentThread().isInterrupted) return@submit
            try {
                val json = httpGet("https://v3-cinemeta.strem.io/catalog/series/top/search=$qEncoded.json", 2500) ?: return@submit
                for (parsed in parseMetasJson(json, "series")) {
                    val existing = pool[parsed.id]
                    if (existing != null) {
                        pool[parsed.id] = existing.copy(
                            cinemetaIndex = existing.cinemetaIndex ?: parsed.cinemetaIndex,
                            imdbRating = parsed.imdbRating ?: existing.imdbRating,
                            description = parsed.description ?: existing.description,
                            genres = if (parsed.genres.isNotEmpty()) parsed.genres else existing.genres
                        )
                    } else {
                        pool[parsed.id] = parsed
                    }
                }
            } catch (_: Exception) {}
        })

        // C. Kitsu Anime
        futures.add(executor.submit {
            if (Thread.currentThread().isInterrupted) return@submit
            try {
                val json = httpGet("https://anime-kitsu.strem.fun/catalog/anime/kitsu-anime-list/search=$qEncoded.json", 2800) ?: return@submit
                for (parsed in parseMetasJson(json, "series")) {
                    val existing = pool[parsed.id]
                    if (existing != null) {
                        pool[parsed.id] = existing.copy(
                            cinemetaIndex = existing.cinemetaIndex ?: parsed.cinemetaIndex,
                            imdbRating = parsed.imdbRating ?: existing.imdbRating,
                            description = parsed.description ?: existing.description
                        )
                    } else {
                        pool[parsed.id] = parsed.copy(genres = listOf("Anime"))
                    }
                }
            } catch (_: Exception) {}
        })

        // D. Cinemeta Movies
        futures.add(executor.submit {
            if (Thread.currentThread().isInterrupted) return@submit
            try {
                val json = httpGet("https://v3-cinemeta.strem.io/catalog/movie/top/search=$qEncoded.json", 2500) ?: return@submit
                for (parsed in parseMetasJson(json, "movie")) {
                    val existing = pool[parsed.id]
                    if (existing != null) {
                        pool[parsed.id] = existing.copy(
                            cinemetaIndex = existing.cinemetaIndex ?: parsed.cinemetaIndex,
                            imdbRating = parsed.imdbRating ?: existing.imdbRating,
                            description = parsed.description ?: existing.description,
                            genres = if (parsed.genres.isNotEmpty()) parsed.genres else existing.genres
                        )
                    } else {
                        pool[parsed.id] = parsed
                    }
                }
            } catch (_: Exception) {}
        })

        for (f in futures) {
            try { f.get() } catch (_: Exception) {}
        }
        if (Thread.currentThread().isInterrupted) return emptyList()

        val qWords = qClean.split(Regex("\\s+")).filter { it.length > 2 }
        val isJojoQuery = qClean.contains("jojo") || qClean.contains("bizarre") ||
                qClean.contains("crusader") || qClean.contains("stone ocean") ||
                qClean.contains("golden wind") || qClean.contains("steel ball")

        fun calculateScore(item: CatalogItem): Long {
            val name = item.name.lowercase().trim()
            val isKitsu = item.id.startsWith("kitsu:")
            val titleMatches = name.contains(qClean) || qWords.any { name.contains(it) } ||
                    isKitsu || isJojoQuery || item.cinemetaIndex != null || item.imdbRank != null
            if (!titleMatches) return -999999L

            var score = 0L
            item.imdbRank?.let { score += (50000 - it * 4).coerceAtLeast(0) }
            item.cinemetaIndex?.let { score += (30000 - it * 1200).coerceAtLeast(0) }
            if (isKitsu) score += 35000L
            if (isJojoQuery && (name.contains("jojo") || name.contains("steel ball") || name.contains("crusader") ||
                        name.contains("stone ocean") || name.contains("golden wind"))) {
                score += 25000L
            }
            when {
                name == qClean -> score += if (item.imdbRank != null || item.cinemetaIndex != null) 4000L else 500L
                name.startsWith(qClean) -> score += 2500L
                name.contains(qClean) -> score += 1500L
            }
            item.imdbRating?.toDoubleOrNull()?.let { score += (it * 300).toLong() }
            if (item.poster.length < 15) score -= 10000L
            return score
        }

        val sorted = pool.values
            .filter { calculateScore(it) > 0 }
            .sortedByDescending { calculateScore(it) }

        searchCache[qClean] = sorted
        return sorted
    }

    fun fetchDeepMeta(type: String, id: String): DeepMeta? {
        val cacheKey = "${type}_$id"
        metaCache[cacheKey]?.let { return it }

        val url = if (id.startsWith("kitsu:")) {
            "https://anime-kitsu.strem.fun/meta/anime/$id.json"
        } else {
            val reqType = if (type.equals("anime", true)) "series" else type
            "https://v3-cinemeta.strem.io/meta/$reqType/$id.json"
        }
        val json = httpGet(url, 4000) ?: return null

        return try {
            val root = JSONObject(json)
            val meta = root.optJSONObject("meta") ?: return null

            val name = meta.optString("name")
            val itemType = meta.optString("type", type)
            val year = meta.optString("releaseInfo").ifEmpty { meta.optString("year") }
            val poster = meta.optString("poster").ifEmpty { "https://images.metahub.space/poster/small/$id/img" }
            val background = if (meta.has("background")) meta.optString("background").ifEmpty {
                if (id.startsWith("tt")) "https://images.metahub.space/background/medium/$id/img" else null
            } else {
                if (id.startsWith("tt")) "https://images.metahub.space/background/medium/$id/img" else null
            }
            val imdbRating = if (meta.has("imdbRating")) meta.optString("imdbRating") else null
            val description = meta.optString("description")

            val genresList = mutableListOf<String>()
            meta.optJSONArray("genres")?.let { arr ->
                for (i in 0 until arr.length()) genresList.add(arr.optString(i))
            }

            val castList = mutableListOf<String>()
            meta.optJSONArray("cast")?.let { arr ->
                for (i in 0 until arr.length()) castList.add(arr.optString(i))
            }

            var director: String? = null
            if (meta.has("director")) {
                val d = meta.opt("director")
                director = if (d is JSONArray) {
                    (0 until d.length()).joinToString(", ") { d.optString(it) }
                } else {
                    d?.toString()
                }
            }

            val runtime = if (meta.has("runtime")) meta.optString("runtime") else null
            val country = if (meta.has("country")) meta.optString("country") else null

            val videosList = mutableListOf<EpisodeItem>()
            meta.optJSONArray("videos")?.let { videosArr ->
                for (i in 0 until videosArr.length()) {
                    val v = videosArr.optJSONObject(i) ?: continue
                    val vidId = v.optString("id")
                    val season = v.optInt("season", 1)
                    val epNum = if (v.has("episode")) v.optInt("episode") else v.optInt("number", i + 1)
                    val epTitle = v.optString("title").ifEmpty { v.optString("name", "Episode $epNum") }
                    val thumb = if (v.has("thumbnail")) v.optString("thumbnail") else "https://episodes.metahub.space/$id/$season/$epNum/w780.jpg"
                    val epOverview = v.optString("overview").ifEmpty { v.optString("description") }
                    val released = v.optString("released")

                    videosList.add(
                        EpisodeItem(
                            id = vidId,
                            season = season,
                            episodeNumber = epNum,
                            title = epTitle,
                            thumbnail = thumb,
                            overview = epOverview,
                            released = if (released.length >= 10) released.substring(0, 10) else released
                        )
                    )
                }
            }

            val parsed = DeepMeta(
                id = id,
                name = name,
                type = itemType,
                year = year,
                poster = poster,
                background = background,
                imdbRating = imdbRating,
                description = description,
                genres = genresList,
                cast = castList,
                director = director,
                runtime = runtime,
                country = country,
                videos = videosList
            )
            metaCache[cacheKey] = parsed
            parsed
        } catch (_: Exception) {
            null
        }
    }

    private fun httpGet(urlString: String, timeoutMs: Int = 3500): String? {
        if (Thread.currentThread().isInterrupted) return null
        var conn: HttpURLConnection? = null
        return try {
            val url = URL(urlString)
            conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.instanceFollowRedirects = true
            conn.useCaches = true
            conn.setRequestProperty("User-Agent", "JewFlix-Native/2.0")

            if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else null
        } catch (_: Exception) {
            null
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }
}
