package com.d4cx.streamflix

data class CatalogItem(
    val id: String,
    val name: String,
    val type: String,
    val year: String,
    val poster: String,
    val imdbRating: String? = null,
    val description: String? = null,
    val imdbRank: Int? = null,
    val cinemetaIndex: Int? = null,
    val backdrop: String? = null,
    val genres: List<String> = emptyList()
)

data class DeepMeta(
    val id: String,
    val name: String,
    val type: String,
    val year: String,
    val poster: String,
    val background: String?,
    val imdbRating: String?,
    val description: String?,
    val genres: List<String>,
    val cast: List<String>,
    val director: String?,
    val runtime: String?,
    val country: String?,
    val videos: List<EpisodeItem>
)

data class EpisodeItem(
    val id: String,
    val season: Int,
    val episodeNumber: Int,
    val title: String,
    val thumbnail: String?,
    val overview: String?,
    val released: String?
)

data class SeasonOption(
    val seasonNumber: Int,
    val label: String,
    val episodeCount: Int
)

data class StreamItem(
    val name: String,
    val title: String,
    val infoHash: String? = null,
    val fileIdx: Int? = null,
    val url: String? = null,
    val audioUrl: String? = null,
    val sources: List<String> = emptyList(),
    val quality: String = "1080p",
    val seeders: Int = 0,
    val sizeText: String = "",
    val releaseTitle: String = "",
    val codecTags: String = "",
    val hasArabicHint: Boolean = false,
    val isDirect: Boolean = false
)

data class ContinueWatchingItem(
    val id: String,
    val type: String,
    val title: String,
    val poster: String,
    val backdrop: String,
    val season: Int,
    val episode: Int,
    val episodeTitle: String,
    val positionMs: Long,
    val durationMs: Long,
    val timestamp: Long
)

data class EngineStatsSnapshot(
    val cacheBytes: Long,
    val cacheCapBytes: Long,
    val cacheItems: Int,
    val activeTorrents: Int,
    val activeStreams: Int
)
