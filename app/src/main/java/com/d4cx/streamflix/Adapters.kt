package com.d4cx.streamflix

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView

data class ChipOption(
    val key: String,
    val label: String
)

// ====================================================================
// 1. HOME CONTINUE WATCHING (16:9 CARDS + RESUME BAR)
// ====================================================================
class ContinueWatchingAdapter(
    private var items: List<ContinueWatchingItem>,
    private val onClick: (ContinueWatchingItem) -> Unit,
    private val onLongRemove: (ContinueWatchingItem) -> Unit
) : RecyclerView.Adapter<ContinueWatchingAdapter.VH>() {

    private val interpolator = DecelerateInterpolator(1.5f)

    class VH(val view: View) : RecyclerView.ViewHolder(view) {
        val backdrop: ImageView = view.findViewById(R.id.cwBackdrop)
        val labelPill: TextView = view.findViewById(R.id.cwLabelPill)
        val timeLeft: TextView = view.findViewById(R.id.cwTimeLeft)
        val progress: ProgressBar = view.findViewById(R.id.cwProgress)
    }

    fun update(newItems: List<ContinueWatchingItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_continue_card, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        val isSeries = !item.type.equals("movie", true)
        holder.labelPill.text = if (isSeries) {
            String.format("%s • S%02d E%02d", item.title.substringBefore(" ("), item.season, item.episode)
        } else {
            item.title.substringBefore(" (")
        }

        if (item.durationMs > 0 && item.positionMs > 0) {
            val remainingMin = ((item.durationMs - item.positionMs) / 60_000L).coerceAtLeast(1)
            holder.timeLeft.text = "${remainingMin}m left"
            holder.progress.visibility = View.VISIBLE
            holder.progress.progress = ((item.positionMs.toFloat() / item.durationMs.toFloat()) * 1000).toInt().coerceIn(20, 1000)
        } else {
            holder.timeLeft.text = "Up Next"
            holder.progress.visibility = View.GONE
        }

        ImageLoader.load(item.backdrop.ifEmpty { item.poster }, holder.backdrop, 420, 236)

        holder.view.setOnFocusChangeListener { v, hasFocus ->
            v.animate()
                .scaleX(if (hasFocus) 1.015f else 1f)
                .scaleY(if (hasFocus) 1.015f else 1f)
                .translationZ(if (hasFocus) 10f else 0f)
                .setDuration(130)
                .setInterpolator(interpolator)
                .start()
        }

        holder.view.setOnClickListener { onClick(item) }
        holder.view.setOnLongClickListener {
            onLongRemove(item)
            true
        }
    }

    override fun getItemCount(): Int = items.size
}

// ====================================================================
// 2. HOME HORIZONTAL & SEARCH GRID POSTER SHELF ADAPTER
// ====================================================================
class ShelfPosterAdapter(
    private var items: List<CatalogItem>,
    private val fullWidthMode: Boolean = false,
    private val onClick: (CatalogItem) -> Unit,
    private val onFocused: ((CatalogItem, Int) -> Unit)? = null
) : RecyclerView.Adapter<ShelfPosterAdapter.VH>() {

    private val interpolator = DecelerateInterpolator(1.5f)

    class VH(val view: View) : RecyclerView.ViewHolder(view) {
        val poster: ImageView = view.findViewById(R.id.shelfPoster)
        val rating: TextView = view.findViewById(R.id.shelfRating)
        val title: TextView = view.findViewById(R.id.shelfTitle)
        val sub: TextView = view.findViewById(R.id.shelfSub)
    }

    fun update(newItems: List<CatalogItem>) {
        val diff = DiffUtil.calculateDiff(CatalogDiffCallback(items, newItems))
        items = newItems
        diff.dispatchUpdatesTo(this)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_shelf_poster, parent, false)
        if (fullWidthMode) {
            val lp = v.layoutParams as ViewGroup.MarginLayoutParams
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT
            lp.setMargins(12, 12, 12, 12)
            v.layoutParams = lp
        }
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.title.text = item.name.substringBefore(" (")
        val typeText = if (item.type.equals("movie", true)) "Movie" else "Series"
        holder.sub.text = if (item.year.isNotEmpty()) "${item.year} • $typeText" else typeText

        if (!item.imdbRating.isNullOrEmpty()) {
            holder.rating.visibility = View.GONE
            holder.rating.text = "★ ${item.imdbRating}"
        } else {
            holder.rating.visibility = View.GONE
        }

        ImageLoader.load(item.poster, holder.poster, 260, 390)

        holder.view.setOnFocusChangeListener { v, hasFocus ->
            if (hasFocus) {
                onFocused?.invoke(item, holder.bindingAdapterPosition.coerceAtLeast(0))
            }
            v.animate()
                .scaleX(if (hasFocus) 1.015f else 1f)
                .scaleY(if (hasFocus) 1.015f else 1f)
                .translationZ(if (hasFocus) 10f else 0f)
                .setDuration(130)
                .setInterpolator(interpolator)
                .start()
        }
        holder.view.setOnClickListener { onClick(item) }
    }

    override fun getItemCount(): Int = items.size
}

// ====================================================================
// 3. CATEGORIES & LIBRARY 5-COLUMN GRID ADAPTER
// ====================================================================
class CatalogAdapter(
    private var items: List<CatalogItem>,
    private val onItemClick: (CatalogItem, Int) -> Unit
) : RecyclerView.Adapter<CatalogAdapter.ViewHolder>() {

    private val interpolator = DecelerateInterpolator(1.5f)

    class ViewHolder(val view: View) : RecyclerView.ViewHolder(view) {
        val poster: ImageView = view.findViewById(R.id.cardPoster)
        val fallbackText: TextView = view.findViewById(R.id.cardPosterFallback)
        val ratingBadge: TextView = view.findViewById(R.id.cardRatingBadge)
        val title: TextView = view.findViewById(R.id.cardTitle)
        val year: TextView = view.findViewById(R.id.cardYear)
        val type: TextView = view.findViewById(R.id.cardType)
    }

    fun updateItems(newItems: List<CatalogItem>) {
        val diff = DiffUtil.calculateDiff(CatalogDiffCallback(items, newItems))
        items = newItems
        diff.dispatchUpdatesTo(this)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_catalog_card, parent, false)
        return ViewHolder(v)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.title.text = item.name.substringBefore(" (")
        holder.year.text = item.year
        holder.type.text = if (item.type.equals("series", true) || item.type.equals("anime", true)) "SERIES" else "MOVIE"

        if (!item.imdbRating.isNullOrEmpty()) {
            holder.ratingBadge.text = "★ ${item.imdbRating}"
            holder.ratingBadge.visibility = View.VISIBLE
        } else {
            holder.ratingBadge.visibility = View.GONE
        }

        ImageLoader.load(item.poster, holder.poster, 280, 420)

        holder.view.setOnFocusChangeListener { v, hasFocus ->
            v.animate()
                .scaleX(if (hasFocus) 1.015f else 1f)
                .scaleY(if (hasFocus) 1.015f else 1f)
                .translationZ(if (hasFocus) 10f else 0f)
                .setDuration(130)
                .setInterpolator(interpolator)
                .start()
        }

        holder.view.setOnClickListener {
            onItemClick(item, holder.bindingAdapterPosition.coerceAtLeast(0))
        }
    }

    override fun getItemCount(): Int = items.size
}

class CatalogDiffCallback(
    private val oldList: List<CatalogItem>,
    private val newList: List<CatalogItem>
) : DiffUtil.Callback() {
    override fun getOldListSize(): Int = oldList.size
    override fun getNewListSize(): Int = newList.size
    override fun areItemsTheSame(oldPos: Int, newPos: Int): Boolean {
        return oldList[oldPos].id == newList[newPos].id && oldList[oldPos].type == newList[newPos].type
    }
    override fun areContentsTheSame(oldPos: Int, newPos: Int): Boolean {
        return oldList[oldPos] == newList[newPos]
    }
}

// ====================================================================
// 4. HORIZONTAL FILTER & SEASON CHIP PILL ADAPTER
// ====================================================================
class ChipAdapter(
    private var chips: List<ChipOption>,
    private var selectedKey: String,
    private val onSelect: (ChipOption) -> Unit
) : RecyclerView.Adapter<ChipAdapter.VH>() {
    var plainText=false

    class VH(val tv: TextView) : RecyclerView.ViewHolder(tv)

    fun update(newChips: List<ChipOption>, newSelectedKey: String) {
        chips = newChips
        selectedKey = newSelectedKey
        notifyDataSetChanged()
    }

    fun setSelected(key: String) {
        selectedKey = key
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val tv = LayoutInflater.from(parent.context).inflate(R.layout.item_chip_pill, parent, false) as TextView
        return VH(tv)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val chip = chips[position]
        if(plainText){
            holder.tv.setBackgroundResource(R.drawable.selector_category_text)
            holder.tv.setTextColor(android.content.res.ColorStateList.valueOf(Color.LTGRAY))
            holder.tv.setTextColor(holder.tv.context.getColorStateList(R.color.color_category_text))
            holder.tv.typeface=android.graphics.Typeface.DEFAULT
        }
        holder.tv.text = chip.label
        holder.tv.isSelected = (chip.key == selectedKey)
        holder.tv.setOnClickListener { onSelect(chip) }
    }

    override fun getItemCount(): Int = chips.size
}

// ====================================================================
// 5. VIRTUAL D-PAD KEYBOARD ADAPTER (SEARCH)
// ====================================================================
class KeypadAdapter(
    private val keys: List<String>,
    private val onKeyClick: (String) -> Unit
) : RecyclerView.Adapter<KeypadAdapter.VH>() {

    class VH(val tv: TextView) : RecyclerView.ViewHolder(tv)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val tv = LayoutInflater.from(parent.context).inflate(R.layout.item_keycap, parent, false) as TextView
        return VH(tv)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val k = keys[position]
        holder.tv.text = k
        holder.tv.setOnClickListener { onKeyClick(k) }
    }

    override fun getItemCount(): Int = keys.size
}

// ====================================================================
// 6. SERIES DETAIL 16:9 EPISODE CARDS ADAPTER
// ====================================================================
class EpisodeAdapter(
    private var episodes: List<EpisodeItem>,
    var currentSeriesId: String = "",
    private val onEpisodeClick: (EpisodeItem) -> Unit,
    private val onEpisodeFocused: ((EpisodeItem) -> Unit)? = null
) : RecyclerView.Adapter<EpisodeAdapter.ViewHolder>() {

    private val interpolator = DecelerateInterpolator(1.5f)

    class ViewHolder(val view: View) : RecyclerView.ViewHolder(view) {
        val thumbnail: ImageView = view.findViewById(R.id.epThumbnail)
        val fallbackText: TextView = view.findViewById(R.id.epFallbackText)
        val badge: TextView = view.findViewById(R.id.epBadge)
        val watchedBadge: TextView = view.findViewById(R.id.epWatchedBadge)
        val date: TextView = view.findViewById(R.id.epDate)
        val progressBar: ProgressBar = view.findViewById(R.id.epProgressBar)
        val title: TextView = view.findViewById(R.id.epTitle)
        val overview: TextView = view.findViewById(R.id.epOverview)
        val statusMeta: TextView = view.findViewById(R.id.epStatusMeta)
        val actionHint: TextView = view.findViewById(R.id.epActionHint)
    }

    fun updateEpisodes(newEpisodes: List<EpisodeItem>, seriesId: String = "") {
        episodes = newEpisodes
        if (seriesId.isNotEmpty()) currentSeriesId = seriesId
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_episode_card, parent, false)
        return ViewHolder(v)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val ep = episodes[position]
        val context = holder.view.context
        val sId = if (currentSeriesId.isNotEmpty()) currentSeriesId else ep.id.substringBefore(":")
        val isWatched = WatchHistoryManager.isWatched(context, "series", sId, ep.season, ep.episodeNumber)
        val resumeRatio = WatchHistoryManager.getResumeRatio(context, "series", sId, ep.season, ep.episodeNumber)

        holder.title.text = "E%02d — %s".format(ep.episodeNumber, ep.title)
        holder.overview.text = if (!ep.overview.isNullOrEmpty()) ep.overview else "Ready to stream in Cinemeta catalog."

        if (isWatched) {
            holder.badge.text = "E%02d".format(ep.episodeNumber)
            holder.watchedBadge.visibility = View.VISIBLE
            holder.date.visibility = View.GONE
            holder.progressBar.visibility = View.GONE
            holder.statusMeta.text = String.format("S%02d · E%02d • %s", ep.season, ep.episodeNumber, ep.released ?: "")
            holder.statusMeta.setTextColor(Color.LTGRAY)
            holder.actionHint.text = "▶ Rewatch"
        } else if (resumeRatio > 0.02f) {
            holder.badge.text = "E%02d • RESUME".format(ep.episodeNumber)
            holder.watchedBadge.visibility = View.GONE
            holder.date.visibility = View.GONE
            holder.date.text = ep.released ?: ""
            holder.progressBar.visibility = View.VISIBLE
            holder.progressBar.progress = (resumeRatio * 1000).toInt().coerceIn(30, 1000)

            val pos = WatchHistoryManager.getPlaybackPosition(context, "series", sId, ep.season, ep.episodeNumber)
            val dur = WatchHistoryManager.getDuration(context, "series", sId, ep.season, ep.episodeNumber)
            val leftMin = ((dur - pos) / 60_000L).coerceAtLeast(1)
            holder.statusMeta.text = "${leftMin}m remaining • Resume"
            holder.statusMeta.setTextColor(Color.parseColor("#E2E8F0"))
            holder.actionHint.text = "▶ Resume"
        } else {
            holder.badge.text = "E%02d".format(ep.episodeNumber)
            holder.watchedBadge.visibility = View.GONE
            holder.date.visibility = View.GONE
            holder.date.text = ep.released ?: ""
            holder.progressBar.visibility = View.GONE
            holder.statusMeta.text = String.format("S%02d · E%02d • %s", ep.season, ep.episodeNumber, ep.released ?: "Ready")
            holder.statusMeta.setTextColor(Color.parseColor("#788294"))
            holder.actionHint.text = "▶ Play"
        }

        ImageLoader.load(ep.thumbnail, holder.thumbnail, 480, 270)

        holder.view.setOnFocusChangeListener { v, hasFocus ->
            if (hasFocus) {
                onEpisodeFocused?.invoke(ep)
            }
            v.animate()
                .scaleX(if (hasFocus) 1.015f else 1f)
                .scaleY(if (hasFocus) 1.015f else 1f)
                .translationZ(if (hasFocus) 8f else 0f)
                .setDuration(130)
                .setInterpolator(interpolator)
                .start()
        }

        holder.view.setOnClickListener { onEpisodeClick(ep) }

        holder.view.setOnLongClickListener {
            val nextWatched = !isWatched
            WatchHistoryManager.markWatched(context, "series", sId, ep.season, ep.episodeNumber, nextWatched)
            notifyItemChanged(holder.bindingAdapterPosition)
            Toast.makeText(
                context,
                if (nextWatched) "Marked E%02d Watched ✓".format(ep.episodeNumber) else "Marked E%02d Unwatched".format(ep.episodeNumber),
                Toast.LENGTH_SHORT
            ).show()
            true
        }
    }

    override fun getItemCount(): Int = episodes.size
}

// ====================================================================
// 7. STREAM SOURCES CARDS ADAPTER (MOVIE DETAIL & PICK SOURCE)
// ====================================================================
class StreamAdapter(
    private var streams: List<StreamItem>,
    private val onStreamClick: (StreamItem, Int) -> Unit,
    private val onStreamFocused: ((StreamItem) -> Unit)? = null
) : RecyclerView.Adapter<StreamAdapter.VH>() {

    private val interpolator = DecelerateInterpolator(1.5f)

    class VH(val view: View) : RecyclerView.ViewHolder(view) {
        val qualityPill: TextView = view.findViewById(R.id.streamQualityPill)
        val addonTag: TextView = view.findViewById(R.id.streamAddonTag)
        val releaseTitle: TextView = view.findViewById(R.id.streamReleaseTitle)
        val codecTags: TextView = view.findViewById(R.id.streamCodecTags)
        val peersStatus: TextView = view.findViewById(R.id.streamPeersStatus)
        val sizeText: TextView = view.findViewById(R.id.streamSizeText)
    }

    fun update(newStreams: List<StreamItem>) {
        streams = newStreams
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_stream_card, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val s = streams[position]
        holder.qualityPill.text = s.quality
        if (s.quality.startsWith("4K")) {
            holder.qualityPill.setBackgroundColor(Color.WHITE)
            holder.qualityPill.setTextColor(Color.parseColor("#050507"))
        } else {
            holder.qualityPill.setBackgroundColor(Color.parseColor("#232836"))
            holder.qualityPill.setTextColor(Color.parseColor("#F8FAFC"))
        }

        holder.addonTag.text = when {
            s.name.contains("Archive", true) || s.name.contains("Cartoon", true) -> "DIRECT • ARA"
            s.isDirect -> "DEBRID • FAST"
            s.name.contains("RD", true) -> "RD+ STREAM"
            else -> s.name.lineSequence().firstOrNull()?.take(14)?.uppercase() ?: "TORRENTIO"
        }

        holder.releaseTitle.text = s.releaseTitle.ifEmpty { s.title }
        holder.codecTags.text = s.codecTags
        holder.sizeText.text = s.sizeText.ifEmpty { if (s.isDirect) "Stream" else "" }

        if (s.isDirect) {
            holder.peersStatus.text = "● Instant HTTPS"
            holder.peersStatus.setTextColor(Color.parseColor("#10B981"))
        } else if (s.seeders >= 25) {
            holder.peersStatus.text = "● ${s.seeders} peers • Instant"
            holder.peersStatus.setTextColor(Color.parseColor("#10B981"))
        } else if (s.seeders > 0) {
            holder.peersStatus.text = "● ${s.seeders} peers • Swarm"
            holder.peersStatus.setTextColor(Color.parseColor("#FBBF24"))
        } else {
            holder.peersStatus.text = "● Low peers"
            holder.peersStatus.setTextColor(Color.parseColor("#F87171"))
        }

        holder.view.setOnFocusChangeListener { v, hasFocus ->
            if (hasFocus) {
                onStreamFocused?.invoke(s)
            }
            v.animate()
                .scaleX(if (hasFocus) 1.015f else 1f)
                .scaleY(if (hasFocus) 1.015f else 1f)
                .translationZ(if (hasFocus) 8f else 0f)
                .setDuration(120)
                .setInterpolator(interpolator)
                .start()
        }

        holder.view.setOnClickListener {
            onStreamClick(s, holder.bindingAdapterPosition.coerceAtLeast(0))
        }
    }

    override fun getItemCount(): Int = streams.size
}
