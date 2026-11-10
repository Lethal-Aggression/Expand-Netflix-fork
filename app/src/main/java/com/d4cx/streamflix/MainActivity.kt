package com.d4cx.streamflix

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewStub
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.Future

class MainActivity : Activity() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val backgroundExecutor = Executors.newFixedThreadPool(5)

    private var activeSearchFuture: Future<*>? = null
    private var activeCatalogFuture: Future<*>? = null
    private var activeMovieStreamsFuture: Future<*>? = null

    // Shell & Navigation Rail
    private lateinit var mainShellContainer: ViewGroup
    private lateinit var navRails: List<FrameLayout>
    private lateinit var navIndicators: List<View>
    private lateinit var navIcons: List<ImageView>
    private lateinit var tvEngineRailBadge: TextView

    // Tab Screens (Lazy Inflation via ViewStubs for Sub-Second Cold Start)
    private lateinit var screenHome: ViewGroup
    private var screenSearch: View? = null
    private var screenCategories: View? = null
    private var screenLibrary: View? = null
    private var screenSettings: View? = null
    private var currentTabIndex = 0
    private var lastFocusedBeforeOverlay: View? = null

    // Home Views
    private lateinit var homeHeroBackdrop: ImageView
    private lateinit var homeHeroRating: TextView
    private lateinit var homeHeroYear: TextView
    private lateinit var homeHeroQuality: TextView
    private lateinit var homeHeroGenres: TextView
    private lateinit var homeHeroTitle: TextView
    private lateinit var homeHeroDesc: TextView
    private lateinit var btnHomeWatch: TextView
    private lateinit var btnHomeSources: TextView
    private lateinit var btnHomeLibrary: TextView
    private lateinit var homeContinueSection: View
    private lateinit var rvHomeContinue: RecyclerView
    private lateinit var rvHomeMovies: RecyclerView
    private lateinit var rvHomeSeries: RecyclerView
    private lateinit var rvHomeCartoons: RecyclerView

    private lateinit var continueAdapter: ContinueWatchingAdapter
    private lateinit var homeMoviesAdapter: ShelfPosterAdapter
    private lateinit var homeSeriesAdapter: ShelfPosterAdapter
    private lateinit var homeCartoonsAdapter: ShelfPosterAdapter
    private var homeHeroItem: CatalogItem? = null

    // Search Views
    private lateinit var inputSearch: EditText
    private lateinit var rvSearchKeypad: RecyclerView
    private lateinit var keySpace: TextView
    private lateinit var keyBackspace: TextView
    private lateinit var keyClear: TextView
    private lateinit var tvSearchHeader: TextView
    private lateinit var rvSearchFilters: RecyclerView
    private lateinit var searchTopMatchCard: View
    private lateinit var topMatchPoster: ImageView
    private lateinit var topMatchTitle: TextView
    private lateinit var topMatchMeta: TextView
    private lateinit var topMatchDesc: TextView
    private lateinit var rvSearchResults: RecyclerView
    private lateinit var tvSearchStatus: TextView

    private lateinit var searchFiltersAdapter: ChipAdapter
    private lateinit var searchResultsAdapter: ShelfPosterAdapter
    private var searchDebounceRunnable: Runnable? = null
    private var allSearchResults: List<CatalogItem> = emptyList()
    private var currentSearchFilter = "all"
    private var currentSearchTopMatch: CatalogItem? = null

    // Categories Views
    private lateinit var tvCatHeaderTitle: TextView
    private lateinit var tvCatSourceBadge: TextView
    private lateinit var rvCatTypes: RecyclerView
    private lateinit var rvCatGenres: RecyclerView
    private lateinit var rvCatGrid: RecyclerView
    private lateinit var tvCatLoading: TextView

    private lateinit var catTypeAdapter: ChipAdapter
    private lateinit var catGenreAdapter: ChipAdapter
    private lateinit var catGridAdapter: CatalogAdapter
    private var currentCategoryKey = "all"
    private var currentGenreKey = "Popular"
    private var catLoadedOnce = false

    // Library Views
    private lateinit var tabLibWatchlist: TextView
    private lateinit var tabLibHistory: TextView
    private lateinit var rvLibraryGrid: RecyclerView
    private lateinit var tvLibraryEmpty: TextView
    private lateinit var libraryAdapter: CatalogAdapter
    private var libraryModeWatchlist = true

    // Settings Views
    private lateinit var tvSettingsCacheTelemetry: TextView
    private lateinit var btnCache160: TextView
    private lateinit var btnCache240: TextView
    private lateinit var btnCache360: TextView
    private lateinit var btnCacheWipeNow: TextView
    private lateinit var btnTogglePrewarm: TextView
    private lateinit var tvSettingsAddonStatus: TextView
    private lateinit var btnSetRealDebrid: TextView
    private lateinit var btnSetCustomAddon: TextView
    private lateinit var btnResetAddon: TextView

    // Movie Detail Overlay (Lazy Inflation)
    private var detailMovieOverlay: ViewGroup? = null
    private lateinit var movieDetailBackdrop: ImageView
    private lateinit var btnMovieDetailBack: TextView
    private lateinit var movieDetailRating: TextView
    private lateinit var movieDetailYear: TextView
    private lateinit var movieDetailRuntime: TextView
    private lateinit var movieDetailGenres: TextView
    private lateinit var movieDetailTitle: TextView
    private lateinit var movieDetailOverview: TextView
    private lateinit var movieDetailCast: TextView
    private lateinit var btnMoviePlayBest: TextView
    private lateinit var btnMovieLibrary: TextView
    private lateinit var btnMovieWatched: TextView
    private lateinit var tvMovieStreamCount: TextView
    private lateinit var chipSrcAll: TextView
    private lateinit var chipSrc4K: TextView
    private lateinit var chipSrc1080: TextView
    private lateinit var chipSrcArabic: TextView
    private lateinit var rvMovieStreams: RecyclerView
    private lateinit var tvMovieStreamStatus: TextView

    private lateinit var movieStreamAdapter: StreamAdapter
    private var currentMovieItem: CatalogItem? = null
    private var allMovieStreams: List<StreamItem> = emptyList()
    private var displayedMovieStreams: List<StreamItem> = emptyList()
    private var currentMovieStreamFilter = "all"
    private var prewarmDebounceRunnable: Runnable? = null

    // Series Detail Overlay (Lazy Inflation)
    private var detailSeriesOverlay: ViewGroup? = null
    private lateinit var seriesDetailBackdrop: ImageView
    private lateinit var btnSeriesDetailBack: TextView
    private lateinit var seriesTypeTag: TextView
    private lateinit var seriesDetailRating: TextView
    private lateinit var seriesDetailYear: TextView
    private lateinit var seriesDetailCounts: TextView
    private lateinit var seriesDetailGenres: TextView
    private lateinit var seriesDetailTitle: TextView
    private lateinit var seriesDetailOverview: TextView
    private lateinit var btnSeriesResume: TextView
    private lateinit var btnSeriesLibrary: TextView
    private lateinit var btnSeriesPickSource: TextView
    private lateinit var rvSeriesSeasons: RecyclerView
    private lateinit var rvSeriesEpisodes: RecyclerView
    private lateinit var tvSeriesEpLoading: TextView

    private lateinit var seasonChipAdapter: ChipAdapter
    private lateinit var episodeAdapter: EpisodeAdapter
    private var currentSeriesItem: CatalogItem? = null
    private var currentEpisodesBySeason: Map<Int, List<EpisodeItem>> = emptyMap()
    private var currentActiveSeason = 1
    private var lastFocusedEpisode: EpisodeItem? = null
    private var epPrewarmRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
        )

        ImageLoader.init(this)
        setContentView(R.layout.activity_main)

        bindViews()
        setupLeftRail()
        setupHomeScreen()
        startEngineAndTelemetry()
        switchTab(0, focusContent = false)
        loadHomeContent()

        btnHomeWatch.postDelayed({
            if (currentTabIndex == 0 && detailMovieOverlay?.visibility != View.VISIBLE && detailSeriesOverlay?.visibility != View.VISIBLE) {
                btnHomeWatch.requestFocus()
            }
        }, 120)
    }

    private fun startEngineAndTelemetry() {
        tvEngineRailBadge.setTextColor(Color.parseColor("#FBBF24"))
        backgroundExecutor.execute {
            val running = JewEngineManager.ensureRunning(this)
            mainHandler.post {
                tvEngineRailBadge.setTextColor(
                    if (running) Color.parseColor("#10B981") else Color.parseColor("#F87171")
                )
            }
        }
    }

    private fun bindViews() {
        mainShellContainer = findViewById(R.id.mainShellContainer)
        navRails = listOf(
            findViewById(R.id.navRailHome),
            findViewById(R.id.navRailSearch),
            findViewById(R.id.navRailCategories),
            findViewById(R.id.navRailLibrary),
            findViewById(R.id.navRailSettings)
        )
        navIndicators = listOf(
            findViewById(R.id.navIndicatorHome),
            findViewById(R.id.navIndicatorSearch),
            findViewById(R.id.navIndicatorCategories),
            findViewById(R.id.navIndicatorLibrary),
            findViewById(R.id.navIndicatorSettings)
        )
        navIcons = listOf(
            findViewById(R.id.navIconHome),
            findViewById(R.id.navIconSearch),
            findViewById(R.id.navIconCategories),
            findViewById(R.id.navIconLibrary),
            findViewById(R.id.navIconSettings)
        )
        tvEngineRailBadge = findViewById(R.id.tvEngineRailBadge)

        screenHome = findViewById(R.id.screenHome)

        // Home
        homeHeroBackdrop = findViewById(R.id.homeHeroBackdrop)
        homeHeroRating = findViewById(R.id.homeHeroRating)
        homeHeroYear = findViewById(R.id.homeHeroYear)
        homeHeroQuality = findViewById(R.id.homeHeroQuality)
        homeHeroGenres = findViewById(R.id.homeHeroGenres)
        homeHeroTitle = findViewById(R.id.homeHeroTitle)
        homeHeroDesc = findViewById(R.id.homeHeroDesc)
        btnHomeWatch = findViewById(R.id.btnHomeWatch)
        btnHomeSources = findViewById(R.id.btnHomeSources)
        btnHomeLibrary = findViewById(R.id.btnHomeLibrary)
        homeContinueSection = findViewById(R.id.homeContinueSection)
        rvHomeContinue = findViewById(R.id.rvHomeContinue)
        rvHomeMovies = findViewById(R.id.rvHomeMovies)
        rvHomeSeries = findViewById(R.id.rvHomeSeries)
        rvHomeCartoons = findViewById(R.id.rvHomeCartoons)
    }

    // Lazy ViewStub inflation helpers for instant cold boot
    private fun ensureSearchInflated(): View {
        if (screenSearch == null) {
            val stub = findViewById<ViewStub>(R.id.stubSearch)
            val inflated = stub.inflate()
            screenSearch = inflated
            bindSearchViews(inflated)
            setupSearchScreen()
        }
        return screenSearch!!
    }

    private fun bindSearchViews(root: View) {
        inputSearch = root.findViewById(R.id.inputSearch)
        rvSearchKeypad = root.findViewById(R.id.rvSearchKeypad)
        keySpace = root.findViewById(R.id.keySpace)
        keyBackspace = root.findViewById(R.id.keyBackspace)
        keyClear = root.findViewById(R.id.keyClear)
        tvSearchHeader = root.findViewById(R.id.tvSearchHeader)
        rvSearchFilters = root.findViewById(R.id.rvSearchFilters)
        searchTopMatchCard = root.findViewById(R.id.searchTopMatchCard)
        topMatchPoster = root.findViewById(R.id.topMatchPoster)
        topMatchTitle = root.findViewById(R.id.topMatchTitle)
        topMatchMeta = root.findViewById(R.id.topMatchMeta)
        topMatchDesc = root.findViewById(R.id.topMatchDesc)
        rvSearchResults = root.findViewById(R.id.rvSearchResults)
        tvSearchStatus = root.findViewById(R.id.tvSearchStatus)
    }

    private fun ensureCategoriesInflated(): View {
        if (screenCategories == null) {
            val stub = findViewById<ViewStub>(R.id.stubCategories)
            val inflated = stub.inflate()
            screenCategories = inflated
            bindCategoriesViews(inflated)
            setupCategoriesScreen()
        }
        return screenCategories!!
    }

    private fun bindCategoriesViews(root: View) {
        tvCatHeaderTitle = root.findViewById(R.id.tvCatHeaderTitle)
        tvCatSourceBadge = root.findViewById(R.id.tvCatSourceBadge)
        rvCatTypes = root.findViewById(R.id.rvCatTypes)
        rvCatGenres = root.findViewById(R.id.rvCatGenres)
        rvCatGrid = root.findViewById(R.id.rvCatGrid)
        tvCatLoading = root.findViewById(R.id.tvCatLoading)
    }

    private fun ensureLibraryInflated(): View {
        if (screenLibrary == null) {
            val stub = findViewById<ViewStub>(R.id.stubLibrary)
            val inflated = stub.inflate()
            screenLibrary = inflated
            bindLibraryViews(inflated)
            setupLibraryScreen()
        }
        return screenLibrary!!
    }

    private fun bindLibraryViews(root: View) {
        tabLibWatchlist = root.findViewById(R.id.tabLibWatchlist)
        tabLibHistory = root.findViewById(R.id.tabLibHistory)
        rvLibraryGrid = root.findViewById(R.id.rvLibraryGrid)
        tvLibraryEmpty = root.findViewById(R.id.tvLibraryEmpty)
    }

    private fun ensureSettingsInflated(): View {
        if (screenSettings == null) {
            val stub = findViewById<ViewStub>(R.id.stubSettings)
            val inflated = stub.inflate()
            screenSettings = inflated
            bindSettingsViews(inflated)
            setupSettingsScreen()
        }
        return screenSettings!!
    }

    private fun bindSettingsViews(root: View) {
        tvSettingsCacheTelemetry = root.findViewById(R.id.tvSettingsCacheTelemetry)
        btnCache160 = root.findViewById(R.id.btnCache160)
        btnCache240 = root.findViewById(R.id.btnCache240)
        btnCache360 = root.findViewById(R.id.btnCache360)
        btnCacheWipeNow = root.findViewById(R.id.btnCacheWipeNow)
        btnTogglePrewarm = root.findViewById(R.id.btnTogglePrewarm)
        tvSettingsAddonStatus = root.findViewById(R.id.tvSettingsAddonStatus)
        btnSetRealDebrid = root.findViewById(R.id.btnSetRealDebrid)
        btnSetCustomAddon = root.findViewById(R.id.btnSetCustomAddon)
        btnResetAddon = root.findViewById(R.id.btnResetAddon)
    }

    private fun ensureDetailMovieInflated(): ViewGroup {
        if (detailMovieOverlay == null) {
            val stub = findViewById<ViewStub>(R.id.stubDetailMovie)
            val inflated = stub.inflate() as ViewGroup
            detailMovieOverlay = inflated
            bindMovieDetailViews(inflated)
            setupMovieDetailOverlay()
        }
        return detailMovieOverlay!!
    }

    private fun bindMovieDetailViews(root: ViewGroup) {
        movieDetailBackdrop = root.findViewById(R.id.movieDetailBackdrop)
        btnMovieDetailBack = root.findViewById(R.id.btnMovieDetailBack)
        movieDetailRating = root.findViewById(R.id.movieDetailRating)
        movieDetailYear = root.findViewById(R.id.movieDetailYear)
        movieDetailRuntime = root.findViewById(R.id.movieDetailRuntime)
        movieDetailGenres = root.findViewById(R.id.movieDetailGenres)
        movieDetailTitle = root.findViewById(R.id.movieDetailTitle)
        movieDetailOverview = root.findViewById(R.id.movieDetailOverview)
        movieDetailCast = root.findViewById(R.id.movieDetailCast)
        btnMoviePlayBest = root.findViewById(R.id.btnMoviePlayBest)
        btnMovieLibrary = root.findViewById(R.id.btnMovieLibrary)
        btnMovieWatched = root.findViewById(R.id.btnMovieWatched)
        tvMovieStreamCount = root.findViewById(R.id.tvMovieStreamCount)
        chipSrcAll = root.findViewById(R.id.chipSrcAll)
        chipSrc4K = root.findViewById(R.id.chipSrc4K)
        chipSrc1080 = root.findViewById(R.id.chipSrc1080)
        chipSrcArabic = root.findViewById(R.id.chipSrcArabic)
        rvMovieStreams = root.findViewById(R.id.rvMovieStreams)
        tvMovieStreamStatus = root.findViewById(R.id.tvMovieStreamStatus)
    }

    private fun ensureDetailSeriesInflated(): ViewGroup {
        if (detailSeriesOverlay == null) {
            val stub = findViewById<ViewStub>(R.id.stubDetailSeries)
            val inflated = stub.inflate() as ViewGroup
            detailSeriesOverlay = inflated
            bindSeriesDetailViews(inflated)
            setupSeriesDetailOverlay()
        }
        return detailSeriesOverlay!!
    }

    private fun bindSeriesDetailViews(root: ViewGroup) {
        seriesDetailBackdrop = root.findViewById(R.id.seriesDetailBackdrop)
        btnSeriesDetailBack = root.findViewById(R.id.btnSeriesDetailBack)
        seriesTypeTag = root.findViewById(R.id.seriesTypeTag)
        seriesDetailRating = root.findViewById(R.id.seriesDetailRating)
        seriesDetailYear = root.findViewById(R.id.seriesDetailYear)
        seriesDetailCounts = root.findViewById(R.id.seriesDetailCounts)
        seriesDetailGenres = root.findViewById(R.id.seriesDetailGenres)
        seriesDetailTitle = root.findViewById(R.id.seriesDetailTitle)
        seriesDetailOverview = root.findViewById(R.id.seriesDetailOverview)
        btnSeriesResume = root.findViewById(R.id.btnSeriesResume)
        btnSeriesLibrary = root.findViewById(R.id.btnSeriesLibrary)
        btnSeriesPickSource = root.findViewById(R.id.btnSeriesPickSource)
        rvSeriesSeasons = root.findViewById(R.id.rvSeriesSeasons)
        rvSeriesEpisodes = root.findViewById(R.id.rvSeriesEpisodes)
        tvSeriesEpLoading = root.findViewById(R.id.tvSeriesEpLoading)
    }

    // ====================================================================
    // LEFT RAIL TAB SWITCHING
    // ====================================================================
    private fun setupLeftRail() {
        navRails.forEachIndexed { index, rail ->
            rail.setOnClickListener {
                switchTab(index, focusContent = true)
            }
        }
    }

    private fun switchTab(index: Int, focusContent: Boolean) {
        currentTabIndex = index
        navRails.forEachIndexed { i, rail ->
            val active = (i == index)
            rail.isSelected = active
            navIndicators[i].visibility = if (active) View.VISIBLE else View.GONE
            navIcons[i].setColorFilter(if (active) 0xFFFFFFFF.toInt() else 0xFF6E7687.toInt())
        }

        // Hide Home if not index 0
        screenHome.visibility = if (index == 0) View.VISIBLE else View.GONE
        screenHome.descendantFocusability = if (index == 0) ViewGroup.FOCUS_AFTER_DESCENDANTS else ViewGroup.FOCUS_BLOCK_DESCENDANTS

        // Hide inactive tabs if inflated
        screenSearch?.let {
            it.visibility = if (index == 1) View.VISIBLE else View.GONE
            (it as? ViewGroup)?.descendantFocusability = if (index == 1) ViewGroup.FOCUS_AFTER_DESCENDANTS else ViewGroup.FOCUS_BLOCK_DESCENDANTS
        }
        screenCategories?.let {
            it.visibility = if (index == 2) View.VISIBLE else View.GONE
            (it as? ViewGroup)?.descendantFocusability = if (index == 2) ViewGroup.FOCUS_AFTER_DESCENDANTS else ViewGroup.FOCUS_BLOCK_DESCENDANTS
        }
        screenLibrary?.let {
            it.visibility = if (index == 3) View.VISIBLE else View.GONE
            (it as? ViewGroup)?.descendantFocusability = if (index == 3) ViewGroup.FOCUS_AFTER_DESCENDANTS else ViewGroup.FOCUS_BLOCK_DESCENDANTS
        }
        screenSettings?.let {
            it.visibility = if (index == 4) View.VISIBLE else View.GONE
            (it as? ViewGroup)?.descendantFocusability = if (index == 4) ViewGroup.FOCUS_AFTER_DESCENDANTS else ViewGroup.FOCUS_BLOCK_DESCENDANTS
        }

        when (index) {
            0 -> {
                refreshContinueWatching()
                if (focusContent) btnHomeWatch.post { btnHomeWatch.requestFocus() }
            }
            1 -> {
                val sView = ensureSearchInflated()
                sView.visibility = View.VISIBLE
                (sView as? ViewGroup)?.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
                if (focusContent) {
                    rvSearchKeypad.post {
                        rvSearchKeypad.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
                            ?: inputSearch.requestFocus()
                    }
                }
            }
            2 -> {
                val cView = ensureCategoriesInflated()
                cView.visibility = View.VISIBLE
                (cView as? ViewGroup)?.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
                if (!catLoadedOnce) loadCategoriesCatalog()
                if (focusContent) {
                    rvCatTypes.post {
                        rvCatTypes.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
                    }
                }
            }
            3 -> {
                val lView = ensureLibraryInflated()
                lView.visibility = View.VISIBLE
                (lView as? ViewGroup)?.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
                refreshLibraryGrid()
                if (focusContent) tabLibWatchlist.post { tabLibWatchlist.requestFocus() }
            }
            4 -> {
                val setView = ensureSettingsInflated()
                setView.visibility = View.VISIBLE
                (setView as? ViewGroup)?.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
                refreshSettingsView()
                if (focusContent) btnCache240.post { btnCache240.requestFocus() }
            }
        }
    }

    // ====================================================================
    // TAB 0: HOME SCREEN
    // ====================================================================
    private fun setupHomeScreen() {
        rvHomeContinue.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        continueAdapter = ContinueWatchingAdapter(
            items = emptyList(),
            onClick = { cw ->
                if (cw.type.equals("movie", true)) {
                    PlayerActivity.start(
                        context = this,
                        type = "movie",
                        id = cw.id,
                        title = cw.title,
                        season = 1,
                        episode = 1,
                        poster = cw.poster,
                        backdrop = cw.backdrop
                    )
                } else {
                    PlayerActivity.start(
                        context = this,
                        type = "series",
                        id = cw.id,
                        title = cw.title,
                        season = cw.season,
                        episode = cw.episode,
                        poster = cw.poster,
                        backdrop = cw.backdrop
                    )
                }
            },
            onLongRemove = { cw ->
                WatchHistoryManager.removeFromContinueWatching(this, cw.id)
                refreshContinueWatching()
                Toast.makeText(this, "Removed from Continue Watching", Toast.LENGTH_SHORT).show()
            }
        )
        rvHomeContinue.adapter = continueAdapter

        rvHomeMovies.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        homeMoviesAdapter = ShelfPosterAdapter(emptyList(), false, { item ->
            handleCatalogItemClick(item)
        })
        rvHomeMovies.adapter = homeMoviesAdapter

        rvHomeSeries.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        homeSeriesAdapter = ShelfPosterAdapter(emptyList(), false, { item ->
            handleCatalogItemClick(item)
        })
        rvHomeSeries.adapter = homeSeriesAdapter

        rvHomeCartoons.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        homeCartoonsAdapter = ShelfPosterAdapter(emptyList(), false, { item ->
            handleCatalogItemClick(item)
        })
        rvHomeCartoons.adapter = homeCartoonsAdapter

        btnHomeWatch.setOnClickListener {
            val hero = homeHeroItem ?: return@setOnClickListener
            if (isOverTheGardenWall(hero)) {
                showOverTheGardenWallChoiceDialog(hero)
            } else if (hero.type.equals("movie", true)) {
                PlayerActivity.start(
                    context = this,
                    type = "movie",
                    id = hero.id,
                    title = hero.name,
                    season = 1,
                    episode = 1,
                    poster = hero.poster,
                    backdrop = hero.backdrop ?: ""
                )
            } else {
                openSeriesDetail(hero)
            }
        }

        btnHomeSources.setOnClickListener {
            val hero = homeHeroItem ?: return@setOnClickListener
            handleCatalogItemClick(hero)
        }

        btnHomeLibrary.setOnClickListener {
            val hero = homeHeroItem ?: return@setOnClickListener
            val added = WatchHistoryManager.toggleLibrary(this, hero)
            syncHeroLibraryButton(added)
            Toast.makeText(
                this,
                if (added) "Added ${hero.name.substringBefore(" (")} to Library" else "Removed from Library",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun loadHomeContent() {
        refreshContinueWatching()
        val curated = CinemetaRepository.getCuratedCartoons()
        homeCartoonsAdapter.update(curated)

        backgroundExecutor.execute {
            val movies = CinemetaRepository.fetchCatalogFiltered("movie", "Popular")

            // Pick a rich cinematic hero from real Cinemeta movies (skip curated cartoon entry as top movie banner)
            val heroCandidate = movies.firstOrNull { !it.id.contains("tt3718778") && !it.id.contains("tt4938232") }
                ?: movies.firstOrNull()
                ?: curated.firstOrNull()

            mainHandler.post {
                if (movies.isNotEmpty()) {
                    homeMoviesAdapter.update(movies.take(24))
                }
                heroCandidate?.let { bindHomeHero(it) }
            }

            // Stagger series catalog fetch by 200ms to preserve smooth UI frame rendering
            try { Thread.sleep(200) } catch (_: Exception) {}
            val series = CinemetaRepository.fetchCatalogFiltered("series", "Popular")
            mainHandler.post {
                if (series.isNotEmpty()) {
                    homeSeriesAdapter.update(series.take(24))
                }
            }

            heroCandidate?.let { hero ->
                val deep = CinemetaRepository.fetchDeepMeta(hero.type, hero.id)
                if (deep != null) {
                    mainHandler.post {
                        if (homeHeroItem?.id == hero.id) {
                            val updated = hero.copy(
                                backdrop = deep.background ?: hero.backdrop,
                                description = deep.description?.ifEmpty { null } ?: hero.description,
                                imdbRating = deep.imdbRating ?: hero.imdbRating,
                                genres = if (deep.genres.isNotEmpty()) deep.genres else hero.genres
                            )
                            bindHomeHero(updated)
                        }
                    }
                }
            }
        }
    }

    private fun bindHomeHero(item: CatalogItem) {
        homeHeroItem = item
        homeHeroTitle.text = item.name.substringBefore(" (").uppercase(Locale.US)
        homeHeroRating.text = "★ ${item.imdbRating ?: "8.4"}"
        homeHeroYear.text = item.year.ifEmpty { "Featured" }
        homeHeroQuality.text = if (item.type.equals("movie", true)) "4K HDR" else "SERIES"
        homeHeroGenres.text = if (item.genres.isNotEmpty()) {
            item.genres.take(3).joinToString(" • ")
        } else {
            "Featured Spotlight"
        }
        homeHeroDesc.text = item.description?.ifEmpty { null }
            ?: "Instant torrent and Debrid streaming ready in 4K HDR."
        syncHeroLibraryButton(WatchHistoryManager.isInLibrary(this, item.id))

        val bgUrl = item.backdrop?.ifEmpty { null }
            ?: if (item.id.startsWith("tt")) "https://images.metahub.space/background/medium/${item.id}/img" else item.poster
        ImageLoader.load(bgUrl, homeHeroBackdrop, 960, 540, keepPreviousWhileLoading = true)
    }

    private fun syncHeroLibraryButton(inLib: Boolean) {
        btnHomeLibrary.text = if (inLib) "✓ In Library" else "+ Library"
    }

    private fun refreshContinueWatching() {
        val list = WatchHistoryManager.getContinueWatching(this)
        if (list.isEmpty()) {
            homeContinueSection.visibility = View.GONE
        } else {
            homeContinueSection.visibility = View.VISIBLE
            continueAdapter.update(list)
        }
    }

    // ====================================================================
    // TAB 1: SPLIT KEYPAD SEARCH SCREEN
    // ====================================================================
    private fun setupSearchScreen() {
        inputSearch.showSoftInputOnFocus = false
        inputSearch.setOnClickListener {
            inputSearch.showSoftInputOnFocus = true
            inputSearch.requestFocus()
        }

        val keys = (('A'..'Z').map { it.toString() } + ('0'..'9').map { it.toString() })
        rvSearchKeypad.layoutManager = GridLayoutManager(this, 6)
        rvSearchKeypad.adapter = KeypadAdapter(keys) { key ->
            val next = inputSearch.text.toString() + key.lowercase(Locale.US)
            inputSearch.setText(next)
            inputSearch.setSelection(next.length)
        }

        keySpace.setOnClickListener {
            val cur = inputSearch.text.toString()
            if (cur.isNotEmpty() && !cur.endsWith(" ")) {
                val next = "$cur "
                inputSearch.setText(next)
                inputSearch.setSelection(next.length)
            }
        }

        keyBackspace.setOnClickListener {
            val cur = inputSearch.text.toString()
            if (cur.isNotEmpty()) {
                val next = cur.dropLast(1)
                inputSearch.setText(next)
                inputSearch.setSelection(next.length)
            }
        }

        keyClear.setOnClickListener {
            inputSearch.text.clear()
            clearSearchResults()
        }

        val quickChips = listOf(
            R.id.chipQuickDune to "Dune",
            R.id.chipQuickJoJo to "JoJo",
            R.id.chipQuickSpongebob to "SpongeBob",
            R.id.chipQuickBreaking to "Breaking Bad",
            R.id.chipQuickInterstellar to "Interstellar"
        )
        quickChips.forEach { (viewId, q) ->
            findViewById<TextView>(viewId).setOnClickListener {
                searchDebounceRunnable?.let { mainHandler.removeCallbacks(it) }
                inputSearch.setText(q)
                inputSearch.setSelection(q.length)
                executeSearch(q)
            }
        }

        rvSearchFilters.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        searchFiltersAdapter = ChipAdapter(
            chips = listOf(
                ChipOption("all", "All Results"),
                ChipOption("movie", "Movies"),
                ChipOption("series", "TV & Anime")
            ),
            selectedKey = "all"
        ) { chip ->
            currentSearchFilter = chip.key
            searchFiltersAdapter.setSelected(chip.key)
            renderFilteredSearchResults()
        }
        rvSearchFilters.adapter = searchFiltersAdapter

        searchTopMatchCard.setOnClickListener {
            currentSearchTopMatch?.let { handleCatalogItemClick(it) }
        }

        rvSearchResults.layoutManager = GridLayoutManager(this, 4)
        searchResultsAdapter = ShelfPosterAdapter(emptyList(), fullWidthMode = true, { item ->
            handleCatalogItemClick(item)
        })
        rvSearchResults.adapter = searchResultsAdapter

        inputSearch.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH || (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER)) {
                searchDebounceRunnable?.let { mainHandler.removeCallbacks(it) }
                val q = inputSearch.text.toString().trim()
                if (q.length >= 2) executeSearch(q)
                true
            } else {
                false
            }
        }

        inputSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                searchDebounceRunnable?.let { mainHandler.removeCallbacks(it) }
                val q = s?.toString()?.trim() ?: ""
                if (q.isEmpty()) {
                    clearSearchResults()
                    return
                }
                if (q.length < 2) {
                    tvSearchHeader.text = "Search: \"$q\""
                    tvSearchStatus.visibility = View.VISIBLE
                    tvSearchStatus.text = "Keep typing..."
                    return
                }
                searchDebounceRunnable = Runnable { executeSearch(q) }
                mainHandler.postDelayed(searchDebounceRunnable!!, 300)
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun clearSearchResults() {
        activeSearchFuture?.cancel(true)
        allSearchResults = emptyList()
        currentSearchTopMatch = null
        tvSearchHeader.text = "Search Results"
        searchTopMatchCard.visibility = View.GONE
        searchResultsAdapter.update(emptyList())
        tvSearchStatus.visibility = View.VISIBLE
        tvSearchStatus.text = "Use D-Pad keypad or quick tags on the left"
    }

    private fun executeSearch(query: String) {
        if (query.length < 2) return
        tvSearchHeader.text = "Searching \"$query\"..."
        tvSearchStatus.visibility = View.VISIBLE
        tvSearchStatus.text = "Querying IMDb Suggest, Cinemeta & Kitsu..."

        activeSearchFuture?.cancel(true)
        activeSearchFuture = backgroundExecutor.submit {
            val res = CinemetaRepository.search(query)
            mainHandler.post {
                if (inputSearch.text.toString().trim().equals(query, ignoreCase = true) ||
                    inputSearch.text.toString().trim().length >= 2
                ) {
                    allSearchResults = res
                    tvSearchHeader.text = "Results for \"$query\" (${res.size})"
                    renderFilteredSearchResults()
                }
            }
        }
    }

    private fun renderFilteredSearchResults() {
        val filtered = when (currentSearchFilter) {
            "movie" -> allSearchResults.filter { it.type.equals("movie", true) }
            "series" -> allSearchResults.filter { !it.type.equals("movie", true) }
            else -> allSearchResults
        }

        if (filtered.isEmpty()) {
            currentSearchTopMatch = null
            searchTopMatchCard.visibility = View.GONE
            searchResultsAdapter.update(emptyList())
            tvSearchStatus.visibility = View.VISIBLE
            tvSearchStatus.text = if (allSearchResults.isEmpty()) "No matches found." else "No matches in this filter."
            return
        }

        tvSearchStatus.visibility = View.GONE
        val top = filtered.first()
        currentSearchTopMatch = top
        searchTopMatchCard.visibility = View.VISIBLE
        topMatchTitle.text = top.name.substringBefore(" (")
        val typeLabel = if (top.type.equals("movie", true)) "MOVIE" else "TV SERIES"
        val ratingStr = if (!top.imdbRating.isNullOrEmpty()) "★ ${top.imdbRating} • " else ""
        topMatchMeta.text = "$ratingStr$typeLabel${if (top.year.isNotEmpty()) " • ${top.year}" else ""}"
        topMatchDesc.text = top.description?.ifEmpty { null }
            ?: "Top match across Cinemeta, IMDb & Kitsu. Press OK for details & instant streams."
        ImageLoader.load(top.poster, topMatchPoster, 180, 270)

        searchResultsAdapter.update(filtered.drop(1))
    }

    // ====================================================================
    // TAB 2: CATEGORIES & DISCOVER (5-COLUMN GRID)
    // ====================================================================
    private fun setupCategoriesScreen() {
        rvCatTypes.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        catTypeAdapter = ChipAdapter(
            chips = listOf(
                ChipOption("all", "All Titles"),
                ChipOption("movie", "Movies"),
                ChipOption("series", "TV Series"),
                ChipOption("anime", "Anime"),
                ChipOption("cartoons", "Arabic & Cartoons")
            ),
            selectedKey = currentCategoryKey
        ) { chip ->
            currentCategoryKey = chip.key
            catTypeAdapter.setSelected(chip.key)
            loadCategoriesCatalog()
        }
        rvCatTypes.adapter = catTypeAdapter

        rvCatGenres.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        catGenreAdapter = ChipAdapter(
            chips = listOf(
                ChipOption("Popular", "Popular"),
                ChipOption("Action & Sci-Fi", "Action & Sci-Fi"),
                ChipOption("Thriller", "Thriller"),
                ChipOption("Comedy", "Comedy"),
                ChipOption("Drama", "Drama"),
                ChipOption("Top Rated", "Top Rated")
            ),
            selectedKey = currentGenreKey
        ) { chip ->
            currentGenreKey = chip.key
            catGenreAdapter.setSelected(chip.key)
            loadCategoriesCatalog()
        }
        rvCatGenres.adapter = catGenreAdapter

        rvCatGrid.layoutManager = GridLayoutManager(this, 5)
        catGridAdapter = CatalogAdapter(emptyList()) { item, _ ->
            handleCatalogItemClick(item)
        }
        rvCatGrid.adapter = catGridAdapter
    }

    private fun loadCategoriesCatalog() {
        catLoadedOnce = true
        val cat = currentCategoryKey
        val genre = currentGenreKey

        val headerTitle = when (cat) {
            "movie" -> "Movies • $genre"
            "series" -> "TV Series • $genre"
            "anime" -> "Anime (Kitsu) • $genre"
            "cartoons" -> "Arabic Dub & Cartoon Classics"
            else -> "Discover All • $genre"
        }
        tvCatHeaderTitle.text = headerTitle
        tvCatLoading.visibility = View.VISIBLE
        tvCatLoading.text = "Loading $headerTitle..."

        activeCatalogFuture?.cancel(true)
        activeCatalogFuture = backgroundExecutor.submit {
            val items = CinemetaRepository.fetchCatalogFiltered(cat, genre)
            mainHandler.post {
                if (currentCategoryKey == cat && currentGenreKey == genre) {
                    catGridAdapter.updateItems(items)
                    tvCatLoading.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
                    if (items.isEmpty()) {
                        tvCatLoading.text = "Could not load catalog. Check connection."
                    } else {
                        tvCatSourceBadge.text = "${items.size} Titles • Live Catalog"
                    }
                }
            }
        }
    }

    // ====================================================================
    // TAB 3: MY LIBRARY
    // ====================================================================
    private fun setupLibraryScreen() {
        rvLibraryGrid.layoutManager = GridLayoutManager(this, 5)
        libraryAdapter = CatalogAdapter(emptyList()) { item, _ ->
            handleCatalogItemClick(item)
        }
        rvLibraryGrid.adapter = libraryAdapter

        tabLibWatchlist.setOnClickListener {
            libraryModeWatchlist = true
            refreshLibraryGrid()
        }

        tabLibHistory.setOnClickListener {
            libraryModeWatchlist = false
            refreshLibraryGrid()
        }
    }

    private fun refreshLibraryGrid() {
        tabLibWatchlist.isSelected = libraryModeWatchlist
        tabLibHistory.isSelected = !libraryModeWatchlist

        val items = if (libraryModeWatchlist) {
            WatchHistoryManager.getLibraryItems(this)
        } else {
            WatchHistoryManager.getContinueWatching(this).map { cw ->
                CatalogItem(
                    id = cw.id,
                    name = cw.title,
                    type = cw.type,
                    year = if (cw.type.equals("movie", true)) "In Progress" else String.format("S%02d · E%02d", cw.season, cw.episode),
                    poster = cw.poster.ifEmpty { "https://images.metahub.space/poster/small/${cw.id}/img" },
                    backdrop = cw.backdrop
                )
            }
        }

        libraryAdapter.updateItems(items)
        if (items.isEmpty()) {
            tvLibraryEmpty.visibility = View.VISIBLE
            tvLibraryEmpty.text = if (libraryModeWatchlist) {
                "Nothing in Watchlist yet. Open any movie or series and press + Library."
            } else {
                "No recent playback history yet."
            }
        } else {
            tvLibraryEmpty.visibility = View.GONE
        }
    }

    // ====================================================================
    // TAB 4: SETTINGS & ENGINE CACHE TELEMETRY
    // ====================================================================
    private fun setupSettingsScreen() {
        btnCache160.setOnClickListener { applyCacheCap(160) }
        btnCache240.setOnClickListener { applyCacheCap(240) }
        btnCache360.setOnClickListener { applyCacheCap(360) }

        btnCacheWipeNow.setOnClickListener {
            Toast.makeText(this, "Wiping rolling cache...", Toast.LENGTH_SHORT).show()
            JewEngineManager.wipeCache(this) {
                mainHandler.post {
                    Toast.makeText(this, "Engine & image cache cleared.", Toast.LENGTH_SHORT).show()
                    refreshSettingsView()
                }
            }
        }

        btnTogglePrewarm.setOnClickListener {
            val next = !SettingsManager.isPrewarmEnabled(this)
            SettingsManager.setPrewarmEnabled(this, next)
            refreshSettingsView()
        }

        btnSetRealDebrid.setOnClickListener {
            showTextConfigDialog(
                title = "Real-Debrid API Key",
                subtitle = "Enables instant HTTPS 4K HDR streaming via Torrentio RD+",
                initialValue = SettingsManager.getRealDebridKey(this),
                hint = "Paste Real-Debrid API token..."
            ) { input ->
                SettingsManager.setRealDebridKey(this, input)
                refreshSettingsView()
                Toast.makeText(
                    this,
                    if (input.isNotBlank()) "Real-Debrid configured ✓" else "Real-Debrid cleared",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        btnSetCustomAddon.setOnClickListener {
            showTextConfigDialog(
                title = "Custom Stremio Addon URL",
                subtitle = "Overrides base Torrentio URL (without trailing /manifest.json)",
                initialValue = SettingsManager.getCustomAddonUrl(this),
                hint = "https://torrentio.strem.fun/..."
            ) { input ->
                SettingsManager.setCustomAddonUrl(this, input)
                refreshSettingsView()
                Toast.makeText(this, "Addon endpoint updated ✓", Toast.LENGTH_SHORT).show()
            }
        }

        btnResetAddon.setOnClickListener {
            SettingsManager.setRealDebridKey(this, "")
            SettingsManager.setCustomAddonUrl(this, "")
            refreshSettingsView()
            Toast.makeText(this, "Reset to default Torrentio public addon", Toast.LENGTH_SHORT).show()
        }
    }

    private fun applyCacheCap(mb: Int) {
        SettingsManager.setMaxCacheMB(this, mb)
        JewEngineManager.updateMaxCacheMB(mb)
        refreshSettingsView()
        Toast.makeText(this, "Rolling cache cap set to $mb MB", Toast.LENGTH_SHORT).show()
    }

    private fun refreshSettingsView() {
        val capMb = SettingsManager.getMaxCacheMB(this)
        btnCache160.isSelected = (capMb == 160)
        btnCache240.isSelected = (capMb == 240)
        btnCache360.isSelected = (capMb == 360)

        val prewarm = SettingsManager.isPrewarmEnabled(this)
        btnTogglePrewarm.isSelected = prewarm
        btnTogglePrewarm.text = if (prewarm) "Enabled" else "Disabled"

        val effUrl = SettingsManager.getEffectiveAddonBaseUrl(this)
        val rdKey = SettingsManager.getRealDebridKey(this)
        tvSettingsAddonStatus.text = when {
            SettingsManager.getCustomAddonUrl(this).isNotEmpty() -> "Custom Addon: $effUrl"
            rdKey.isNotEmpty() -> "Torrentio + Real-Debrid Active (${rdKey.take(6)}***)"
            else -> "Active Addon: $effUrl"
        }

        tvSettingsCacheTelemetry.text = "Polling live engine telemetry..."
        backgroundExecutor.execute {
            val stats = JewEngineManager.fetchEngineStats()
            val freeMb = try {
                val stat = StatFs(cacheDir.absolutePath)
                stat.availableBytes / (1024.0 * 1024.0)
            } catch (_: Exception) { 0.0 }

            mainHandler.post {
                tvSettingsCacheTelemetry.text = if (stats != null) {
                    val usedMb = stats.cacheBytes / (1024.0 * 1024.0)
                    val capActualMb = stats.cacheCapBytes / (1024.0 * 1024.0)
                    String.format(
                        Locale.US,
                        "Engine Cache: %.1f / %.0f MB (%d items) • Active Torrents: %d • Free Storage: %.0f MB",
                        usedMb, capActualMb, stats.cacheItems, stats.activeTorrents, freeMb
                    )
                } else {
                    String.format(Locale.US, "Engine Offline/Starting • Target Cap: %d MB • Free Storage: %.0f MB", capMb, freeMb)
                }
            }
        }
    }

    // ====================================================================
    // ITEM CLICK ROUTER
    // ====================================================================
    private fun handleCatalogItemClick(item: CatalogItem) {
        if (isOverTheGardenWall(item)) {
            showOverTheGardenWallChoiceDialog(item)
            return
        }
        val isSeries = item.type.equals("series", ignoreCase = true) ||
            item.type.equals("tv", ignoreCase = true) ||
            item.type.equals("anime", ignoreCase = true)
        if (isSeries) {
            openSeriesDetail(item)
        } else {
            openMovieDetail(item)
        }
    }

    private fun isOverTheGardenWall(item: CatalogItem): Boolean {
        return item.id.contains("tt3718778") ||
            item.name.contains("Garden Wall", ignoreCase = true) ||
            item.name.contains("حائط الحديقة")
    }

    private fun showOverTheGardenWallChoiceDialog(item: CatalogItem) {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val view = layoutInflater.inflate(R.layout.dialog_otgw_choice, null)
        dialog.setContentView(view)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        val btnMovie = view.findViewById<View>(R.id.btnOptionMovie)
        val btnSeries = view.findViewById<View>(R.id.btnOptionSeries)

        btnMovie.setOnClickListener {
            dialog.dismiss()
            PlayerActivity.start(
                context = this,
                type = "movie",
                id = "tt3718778",
                title = "خلف حائط الحديقة - الفيلم الكامل (78 دقيقة)",
                season = 1,
                episode = 1,
                poster = item.poster,
                backdrop = item.backdrop ?: ""
            )
        }

        btnSeries.setOnClickListener {
            dialog.dismiss()
            openSeriesDetail(item.copy(type = "series"))
        }

        dialog.show()
        btnMovie.post { btnMovie.requestFocus() }
    }

    // ====================================================================
    // MOVIE DETAIL OVERLAY + STREAM PICKER + PREWARM
    // ====================================================================
    private fun setupMovieDetailOverlay() {
        btnMovieDetailBack.setOnClickListener { closeMovieDetail() }

        rvMovieStreams.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        movieStreamAdapter = StreamAdapter(
            streams = emptyList(),
            onStreamClick = { stream, idx ->
                val movie = currentMovieItem ?: return@StreamAdapter
                launchMovieFromStreamList(movie, displayedMovieStreams, idx)
            },
            onStreamFocused = { stream ->
                scheduleStreamPrewarm(stream)
            }
        )
        rvMovieStreams.adapter = movieStreamAdapter

        btnMoviePlayBest.setOnClickListener {
            val movie = currentMovieItem ?: return@setOnClickListener
            val list = displayedMovieStreams.ifEmpty { allMovieStreams }
            if (list.isNotEmpty()) {
                launchMovieFromStreamList(movie, list, 0)
            } else {
                PlayerActivity.start(
                    context = this,
                    type = "movie",
                    id = movie.id,
                    title = movie.name,
                    season = 1,
                    episode = 1,
                    poster = movie.poster,
                    backdrop = movie.backdrop ?: ""
                )
            }
        }

        btnMovieLibrary.setOnClickListener {
            val movie = currentMovieItem ?: return@setOnClickListener
            val added = WatchHistoryManager.toggleLibrary(this, movie)
            syncMovieDetailButtons(movie)
            Toast.makeText(
                this,
                if (added) "Saved to Library ✓" else "Removed from Library",
                Toast.LENGTH_SHORT
            ).show()
        }

        btnMovieWatched.setOnClickListener {
            val movie = currentMovieItem ?: return@setOnClickListener
            val next = !WatchHistoryManager.isWatched(this, "movie", movie.id, 1, 1)
            WatchHistoryManager.markWatched(this, "movie", movie.id, 1, 1, next)
            syncMovieDetailButtons(movie)
            Toast.makeText(this, if (next) "Marked Watched ✓" else "Marked Unwatched", Toast.LENGTH_SHORT).show()
        }

        chipSrcAll.setOnClickListener { applyMovieStreamFilter("all") }
        chipSrc4K.setOnClickListener { applyMovieStreamFilter("4k") }
        chipSrc1080.setOnClickListener { applyMovieStreamFilter("1080") }
        chipSrcArabic.setOnClickListener { applyMovieStreamFilter("arabic") }
    }

    private fun openMovieDetail(item: CatalogItem) {
        val overlay = ensureDetailMovieInflated()
        lastFocusedBeforeOverlay = currentFocus
        currentMovieItem = item
        currentMovieStreamFilter = "all"
        allMovieStreams = emptyList()
        displayedMovieStreams = emptyList()
        movieStreamAdapter.update(emptyList())

        mainShellContainer.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        mainShellContainer.visibility = View.GONE

        movieDetailTitle.text = item.name.substringBefore(" (").uppercase(Locale.US)
        movieDetailYear.text = item.year.ifEmpty { "Feature" }
        movieDetailRating.text = "★ ${item.imdbRating ?: "8.0"} IMDb"
        movieDetailRuntime.text = "Feature Film"
        movieDetailGenres.text = if (item.genres.isNotEmpty()) item.genres.take(4).joinToString(" • ") else "Cinema"
        movieDetailOverview.text = item.description?.ifEmpty { null } ?: "Fetching overview from Cinemeta..."
        movieDetailCast.text = "Loading cast..."

        val bg = item.backdrop?.ifEmpty { null }
            ?: if (item.id.startsWith("tt")) "https://images.metahub.space/background/medium/${item.id}/img" else item.poster
        ImageLoader.load(bg, movieDetailBackdrop, 960, 540)

        syncMovieDetailButtons(item)
        tvMovieStreamCount.text = "• Searching Torrentio..."
        tvMovieStreamStatus.visibility = View.VISIBLE
        tvMovieStreamStatus.text = "Fetching healthy swarms..."

        chipSrcAll.isSelected = true
        chipSrc4K.isSelected = false
        chipSrc1080.isSelected = false
        chipSrcArabic.isSelected = false

        overlay.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        overlay.visibility = View.VISIBLE
        overlay.alpha = 0f
        overlay.animate().alpha(1f).setDuration(150).start()
        btnMoviePlayBest.postDelayed({ btnMoviePlayBest.requestFocus() }, 90)

        backgroundExecutor.execute {
            val meta = CinemetaRepository.fetchDeepMeta(item.type, item.id)
            mainHandler.post {
                if (currentMovieItem?.id == item.id && meta != null) {
                    if (!meta.description.isNullOrEmpty()) movieDetailOverview.text = meta.description
                    if (!meta.imdbRating.isNullOrEmpty()) movieDetailRating.text = "★ ${meta.imdbRating} IMDb"
                    if (!meta.runtime.isNullOrEmpty()) movieDetailRuntime.text = meta.runtime
                    if (meta.genres.isNotEmpty()) movieDetailGenres.text = meta.genres.take(4).joinToString(" • ")
                    if (meta.cast.isNotEmpty()) movieDetailCast.text = "Cast: ${meta.cast.take(5).joinToString(", ")}"
                    if (!meta.background.isNullOrEmpty()) {
                        currentMovieItem = item.copy(
                            backdrop = meta.background,
                            description = meta.description ?: item.description
                        )
                        ImageLoader.load(meta.background, movieDetailBackdrop, 960, 540, keepPreviousWhileLoading = true)
                    }
                }
            }
        }

        activeMovieStreamsFuture?.cancel(true)
        activeMovieStreamsFuture = backgroundExecutor.submit {
            val streams = StreamRepository.fetchStreams("movie", item.id, context = this)
            mainHandler.post {
                if (currentMovieItem?.id == item.id) {
                    allMovieStreams = streams
                    applyMovieStreamFilter(currentMovieStreamFilter)
                    streams.firstOrNull { !it.isDirect && it.infoHash != null }?.let { topTorrent ->
                        JewEngineManager.prewarmStream(this, topTorrent)
                    }
                }
            }
        }
    }

    private fun syncMovieDetailButtons(item: CatalogItem) {
        val pos = WatchHistoryManager.getPlaybackPosition(this, "movie", item.id, 1, 1)
        btnMoviePlayBest.text = if (pos > 10_000) "▶  Resume Movie" else "▶  Watch Best Source"
        btnMovieLibrary.text = if (WatchHistoryManager.isInLibrary(this, item.id)) "✓ In Library" else "+ Library"
        btnMovieWatched.text = if (WatchHistoryManager.isWatched(this, "movie", item.id, 1, 1)) "✓ Watched" else "✓ Mark Watched"
    }

    private fun applyMovieStreamFilter(filterKey: String) {
        currentMovieStreamFilter = filterKey
        chipSrcAll.isSelected = (filterKey == "all")
        chipSrc4K.isSelected = (filterKey == "4k")
        chipSrc1080.isSelected = (filterKey == "1080")
        chipSrcArabic.isSelected = (filterKey == "arabic")

        val filtered = when (filterKey) {
            "4k" -> allMovieStreams.filter { it.quality.startsWith("4K") }
            "1080" -> allMovieStreams.filter { it.quality.contains("1080") || it.quality.contains("1440") }
            "arabic" -> allMovieStreams.filter { it.hasArabicHint }
            else -> allMovieStreams
        }

        displayedMovieStreams = filtered
        movieStreamAdapter.update(filtered)
        tvMovieStreamCount.text = "• ${allMovieStreams.size} Sources Ready"

        if (filtered.isEmpty()) {
            tvMovieStreamStatus.visibility = View.VISIBLE
            tvMovieStreamStatus.text = if (allMovieStreams.isEmpty()) {
                "No playable streams found for this title."
            } else {
                "No streams match this quality filter. Select All."
            }
        } else {
            tvMovieStreamStatus.visibility = View.GONE
        }
    }

    private fun scheduleStreamPrewarm(stream: StreamItem) {
        prewarmDebounceRunnable?.let { mainHandler.removeCallbacks(it) }
        if (stream.isDirect || stream.infoHash.isNullOrEmpty()) return
        val runnable = Runnable {
            JewEngineManager.prewarmStream(this, stream)
        }
        prewarmDebounceRunnable = runnable
        mainHandler.postDelayed(runnable, 350)
    }

    private fun launchMovieFromStreamList(movie: CatalogItem, streams: List<StreamItem>, idx: Int) {
        prewarmDebounceRunnable?.let { mainHandler.removeCallbacks(it) }
        PlayerActivity.start(
            context = this,
            type = "movie",
            id = movie.id,
            title = movie.name.substringBefore(" ("),
            season = 1,
            episode = 1,
            poster = movie.poster,
            backdrop = movie.backdrop ?: "",
            preloadedStreams = streams,
            selectedStreamIndex = idx
        )
    }

    private fun closeMovieDetail() {
        activeMovieStreamsFuture?.cancel(true)
        prewarmDebounceRunnable?.let { mainHandler.removeCallbacks(it) }
        val overlay = detailMovieOverlay ?: return
        overlay.animate().alpha(0f).setDuration(130).withEndAction {
            overlay.visibility = View.GONE
            overlay.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            mainShellContainer.visibility = View.VISIBLE
            mainShellContainer.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            restoreFocusAfterOverlay()
        }.start()
    }

    // ====================================================================
    // SERIES DETAIL OVERLAY + HORIZONTAL SEASONS & 16:9 EPISODE SHELF
    // ====================================================================
    private fun setupSeriesDetailOverlay() {
        btnSeriesDetailBack.setOnClickListener { closeSeriesDetail() }

        rvSeriesSeasons.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        seasonChipAdapter = ChipAdapter(emptyList(), "1") { chip ->
            val seasonNum = chip.key.toIntOrNull() ?: return@ChipAdapter
            selectSeriesSeason(seasonNum, moveFocusToEpisode = false)
        }
        rvSeriesSeasons.adapter = seasonChipAdapter

        rvSeriesEpisodes.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        episodeAdapter = EpisodeAdapter(
            episodes = emptyList(),
            currentSeriesId = "",
            onEpisodeClick = { ep ->
                val series = currentSeriesItem ?: return@EpisodeAdapter
                launchSeriesEpisode(series, ep)
            },
            onEpisodeFocused = { ep ->
                lastFocusedEpisode = ep
                scheduleEpisodePrewarm(ep)
            }
        )
        rvSeriesEpisodes.adapter = episodeAdapter

        btnSeriesResume.setOnClickListener {
            val series = currentSeriesItem ?: return@setOnClickListener
            val target = resolveTargetResumeEpisode(series)
            launchSeriesEpisode(series, target)
        }

        btnSeriesLibrary.setOnClickListener {
            val series = currentSeriesItem ?: return@setOnClickListener
            val added = WatchHistoryManager.toggleLibrary(this, series)
            syncSeriesLibraryButton(series)
            Toast.makeText(
                this,
                if (added) "Saved to Library ✓" else "Removed from Library",
                Toast.LENGTH_SHORT
            ).show()
        }

        btnSeriesPickSource.setOnClickListener {
            val series = currentSeriesItem ?: return@setOnClickListener
            val ep = lastFocusedEpisode ?: resolveTargetResumeEpisode(series)
            showEpisodeSourcePickerDialog(series, ep)
        }
    }

    private fun openSeriesDetail(item: CatalogItem) {
        val overlay = ensureDetailSeriesInflated()
        lastFocusedBeforeOverlay = currentFocus
        currentSeriesItem = item
        lastFocusedEpisode = null
        currentEpisodesBySeason = emptyMap()
        episodeAdapter.updateEpisodes(emptyList(), seriesId = item.id)
        seasonChipAdapter.update(emptyList(), "1")

        mainShellContainer.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        mainShellContainer.visibility = View.GONE

        seriesTypeTag.text = if (item.id.startsWith("kitsu:") || item.genres.any { it.equals("Anime", true) }) {
            "ANIME SERIES"
        } else {
            "TV SERIES"
        }
        seriesDetailTitle.text = item.name.substringBefore(" (").uppercase(Locale.US)
        seriesDetailRating.text = "★ ${item.imdbRating ?: "8.5"} IMDb"
        seriesDetailYear.text = item.year.ifEmpty { "Series" }
        seriesDetailCounts.text = "Loading seasons..."
        seriesDetailGenres.text = if (item.genres.isNotEmpty()) item.genres.take(3).joinToString(" • ") else ""
        seriesDetailOverview.text = item.description?.ifEmpty { null } ?: "Fetching series metadata from Cinemeta..."
        syncSeriesLibraryButton(item)

        val cw = WatchHistoryManager.getLatestForSeries(this, item.id)
        btnSeriesResume.text = if (cw != null) {
            if (cw.positionMs > 10_000) {
                String.format(Locale.US, "▶  Resume S%02d · E%02d", cw.season, cw.episode)
            } else {
                String.format(Locale.US, "▶  Up Next S%02d · E%02d", cw.season, cw.episode)
            }
        } else {
            "▶  Play S01 · E01"
        }

        val bg = item.backdrop?.ifEmpty { null }
            ?: if (item.id.startsWith("tt")) "https://images.metahub.space/background/medium/${item.id}/img" else item.poster
        ImageLoader.load(bg, seriesDetailBackdrop, 960, 540)

        tvSeriesEpLoading.visibility = View.VISIBLE
        tvSeriesEpLoading.text = "Loading seasons & episodes..."

        overlay.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        overlay.visibility = View.VISIBLE
        overlay.alpha = 0f
        overlay.animate().alpha(1f).setDuration(150).start()
        btnSeriesResume.postDelayed({ btnSeriesResume.requestFocus() }, 90)

        backgroundExecutor.execute {
            val deep = CinemetaRepository.fetchDeepMeta(item.type, item.id)
            mainHandler.post {
                if (currentSeriesItem?.id == item.id) {
                    if (deep != null) {
                        applySeriesDeepMeta(deep, item)
                    } else {
                        tvSeriesEpLoading.visibility = View.VISIBLE
                        tvSeriesEpLoading.text = "No episode list available. Try Play S01·E01."
                    }
                }
            }
        }
    }

    private fun applySeriesDeepMeta(meta: DeepMeta, item: CatalogItem) {
        if (!meta.description.isNullOrEmpty()) seriesDetailOverview.text = meta.description
        if (!meta.imdbRating.isNullOrEmpty()) seriesDetailRating.text = "★ ${meta.imdbRating} IMDb"
        if (meta.genres.isNotEmpty()) seriesDetailGenres.text = meta.genres.take(3).joinToString(" • ")
        if (!meta.background.isNullOrEmpty()) {
            currentSeriesItem = item.copy(
                backdrop = meta.background,
                description = meta.description ?: item.description
            )
            ImageLoader.load(meta.background, seriesDetailBackdrop, 960, 540, keepPreviousWhileLoading = true)
        }

        val grouped = meta.videos.groupBy { it.season }
        currentEpisodesBySeason = grouped

        val sortedSeasonNumbers = grouped.keys.sortedWith { a, b ->
            when {
                a == 0 -> 1
                b == 0 -> -1
                else -> a.compareTo(b)
            }
        }

        val storySeasonsCount = sortedSeasonNumbers.count { it > 0 }.coerceAtLeast(sortedSeasonNumbers.size)
        seriesDetailCounts.text = "$storySeasonsCount Seasons • ${meta.videos.size} Eps"

        val chips = sortedSeasonNumbers.map { s ->
            ChipOption(s.toString(), formatSeriesSeasonLabel(item, s, grouped[s]?.size ?: 0))
        }

        val cw = WatchHistoryManager.getLatestForSeries(this, item.id)
        val initialSeason = if (cw != null && grouped.containsKey(cw.season)) {
            cw.season
        } else {
            sortedSeasonNumbers.firstOrNull { it > 0 } ?: sortedSeasonNumbers.firstOrNull() ?: 1
        }
        currentActiveSeason = initialSeason
        seasonChipAdapter.update(chips, initialSeason.toString())
        selectSeriesSeason(initialSeason, moveFocusToEpisode = false, scrollToEpisodeNumber = cw?.episode)
    }

    private fun formatSeriesSeasonLabel(item: CatalogItem, s: Int, count: Int): String {
        if (item.id == "tt2359704" || item.name.contains("JoJo", ignoreCase = true)) {
            val base = when (s) {
                1 -> "Part 1–2: Phantom Blood / Tendency"
                2 -> "Part 3: Stardust Crusaders"
                3 -> "Part 4: Diamond"
                4 -> "Part 5: Golden Wind"
                5 -> "Part 6: Stone Ocean"
                6 -> "Part 7: Steel Ball Run"
                0 -> "OVAs & Specials"
                else -> "Season $s"
            }
            return "$base ($count)"
        }
        return if (s == 0) "Specials ($count)" else "Season $s ($count)"
    }

    private fun selectSeriesSeason(
        seasonNumber: Int,
        moveFocusToEpisode: Boolean,
        scrollToEpisodeNumber: Int? = null
    ) {
        currentActiveSeason = seasonNumber
        seasonChipAdapter.setSelected(seasonNumber.toString())
        val series = currentSeriesItem ?: return
        val eps = (currentEpisodesBySeason[seasonNumber] ?: emptyList()).sortedBy { it.episodeNumber }
        episodeAdapter.updateEpisodes(eps, seriesId = series.id)

        tvSeriesEpLoading.visibility = if (eps.isEmpty()) View.VISIBLE else View.GONE
        if (eps.isEmpty()) {
            tvSeriesEpLoading.text = "No episodes indexed in this season."
            return
        }

        val targetIdx = if (scrollToEpisodeNumber != null) {
            eps.indexOfFirst { it.episodeNumber == scrollToEpisodeNumber }.coerceAtLeast(0)
        } else 0

        if (targetIdx > 0) {
            rvSeriesEpisodes.post { rvSeriesEpisodes.scrollToPosition(targetIdx) }
        }
        lastFocusedEpisode = eps[targetIdx]

        if (moveFocusToEpisode) {
            rvSeriesEpisodes.postDelayed({
                rvSeriesEpisodes.findViewHolderForAdapterPosition(targetIdx)?.itemView?.requestFocus()
            }, 80)
        }
    }

    private fun resolveTargetResumeEpisode(series: CatalogItem): EpisodeItem {
        val cw = WatchHistoryManager.getLatestForSeries(this, series.id)
        if (cw != null) {
            currentEpisodesBySeason[cw.season]?.firstOrNull { it.episodeNumber == cw.episode }?.let { return it }
        }
        val activeList = currentEpisodesBySeason[currentActiveSeason].orEmpty().sortedBy { it.episodeNumber }
        return activeList.firstOrNull() ?: EpisodeItem(
            id = "${series.id}:${cw?.season ?: currentActiveSeason}:${cw?.episode ?: 1}",
            season = cw?.season ?: currentActiveSeason,
            episodeNumber = cw?.episode ?: 1,
            title = "Episode ${cw?.episode ?: 1}",
            thumbnail = null,
            overview = null,
            released = null
        )
    }

    private fun scheduleEpisodePrewarm(ep: EpisodeItem) {
        epPrewarmRunnable?.let { mainHandler.removeCallbacks(it) }
        val series = currentSeriesItem ?: return
        if (!SettingsManager.isPrewarmEnabled(this)) return

        val runnable = Runnable {
            backgroundExecutor.execute {
                val streams = StreamRepository.fetchStreams(
                    type = "series",
                    id = series.id,
                    season = ep.season,
                    episode = ep.episodeNumber,
                    context = this
                )
                streams.firstOrNull { !it.isDirect && it.infoHash != null }?.let { best ->
                    JewEngineManager.prewarmStream(this, best)
                }
            }
        }
        epPrewarmRunnable = runnable
        mainHandler.postDelayed(runnable, 650)
    }

    private fun syncSeriesLibraryButton(item: CatalogItem) {
        btnSeriesLibrary.text = if (WatchHistoryManager.isInLibrary(this, item.id)) "✓ In Library" else "+ Library"
    }

    private fun launchSeriesEpisode(series: CatalogItem, ep: EpisodeItem, preloadedStreams: List<StreamItem>? = null, streamIdx: Int = 0) {
        epPrewarmRunnable?.let { mainHandler.removeCallbacks(it) }
        PlayerActivity.start(
            context = this,
            type = "series",
            id = series.id,
            title = "${series.name.substringBefore(" (")} — ${ep.title}",
            season = ep.season,
            episode = ep.episodeNumber,
            poster = series.poster,
            backdrop = ep.thumbnail ?: series.backdrop ?: "",
            preloadedStreams = preloadedStreams,
            selectedStreamIndex = streamIdx
        )
    }

    private fun showEpisodeSourcePickerDialog(series: CatalogItem, ep: EpisodeItem) {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_surface_panel)
            setPadding(40, 32, 40, 32)
        }

        val titleTv = TextView(this).apply {
            text = String.format(Locale.US, "Pick Source • S%02d · E%02d — %s", ep.season, ep.episodeNumber, ep.title)
            setTextColor(Color.WHITE)
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        val statusTv = TextView(this).apply {
            text = "Searching Torrentio & Arabic Archives..."
            setTextColor(Color.parseColor("#8E96A4"))
            textSize = 11.5f
            setPadding(0, 6, 0, 16)
        }
        val rv = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity, LinearLayoutManager.HORIZONTAL, false)
            layoutParams = LinearLayout.LayoutParams(
                (resources.displayMetrics.density * 740).toInt(),
                (resources.displayMetrics.density * 136).toInt()
            )
        }
        val closeBtn = TextView(this).apply {
            text = "Close"
            setTextColor(Color.WHITE)
            textSize = 11.5f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setBackgroundResource(R.drawable.selector_pill_secondary)
            gravity = android.view.Gravity.CENTER
            setPadding(28, 10, 28, 10)
            isFocusable = true
            isClickable = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 20 }
            setOnClickListener { dialog.dismiss() }
        }

        var loadedStreams: List<StreamItem> = emptyList()
        val pickerAdapter = StreamAdapter(
            streams = emptyList(),
            onStreamClick = { _, idx ->
                dialog.dismiss()
                launchSeriesEpisode(series, ep, loadedStreams, idx)
            },
            onStreamFocused = { stream -> scheduleStreamPrewarm(stream) }
        )
        rv.adapter = pickerAdapter

        root.addView(titleTv)
        root.addView(statusTv)
        root.addView(rv)
        root.addView(closeBtn)

        dialog.setContentView(root)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        dialog.show()
        closeBtn.requestFocus()

        backgroundExecutor.execute {
            val streams = StreamRepository.fetchStreams(
                type = "series",
                id = series.id,
                season = ep.season,
                episode = ep.episodeNumber,
                context = this
            )
            mainHandler.post {
                if (!dialog.isShowing) return@post
                loadedStreams = streams
                pickerAdapter.update(streams)
                if (streams.isEmpty()) {
                    statusTv.text = "No streams found for S%02d · E%02d.".format(ep.season, ep.episodeNumber)
                } else {
                    statusTv.text = "${streams.size} sources available — select to launch"
                    streams.firstOrNull { !it.isDirect && it.infoHash != null }?.let {
                        JewEngineManager.prewarmStream(this, it)
                    }
                    rv.post { rv.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() }
                }
            }
        }
    }

    private fun closeSeriesDetail() {
        epPrewarmRunnable?.let { mainHandler.removeCallbacks(it) }
        val overlay = detailSeriesOverlay ?: return
        overlay.animate().alpha(0f).setDuration(130).withEndAction {
            overlay.visibility = View.GONE
            overlay.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            mainShellContainer.visibility = View.VISIBLE
            mainShellContainer.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            restoreFocusAfterOverlay()
        }.start()
    }

    private fun restoreFocusAfterOverlay() {
        val prev = lastFocusedBeforeOverlay
        lastFocusedBeforeOverlay = null
        mainShellContainer.post {
            if (prev != null && prev.isAttachedToWindow && prev.isShown) {
                prev.requestFocus()
            } else {
                navRails.getOrNull(currentTabIndex)?.requestFocus()
            }
        }
    }

    // ====================================================================
    // STYLED TV TEXT INPUT DIALOG (REAL-DEBRID / CUSTOM ADDON)
    // ====================================================================
    private fun showTextConfigDialog(
        title: String,
        subtitle: String,
        initialValue: String,
        hint: String,
        onSave: (String) -> Unit
    ) {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val d = resources.displayMetrics.density
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_surface_panel)
            setPadding((26 * d).toInt(), (22 * d).toInt(), (26 * d).toInt(), (22 * d).toInt())
        }

        val tvTitle = TextView(this).apply {
            text = title
            setTextColor(Color.WHITE)
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        val tvSub = TextView(this).apply {
            text = subtitle
            setTextColor(Color.parseColor("#8E96A4"))
            textSize = 11f
            setPadding(0, (4 * d).toInt(), 0, (14 * d).toInt())
        }
        val edit = EditText(this).apply {
            setText(initialValue)
            setSelection(initialValue.length)
            this.hint = hint
            setHintTextColor(Color.parseColor("#5A6375"))
            setTextColor(Color.WHITE)
            textSize = 13f
            isSingleLine = true
            setBackgroundResource(R.drawable.bg_search_box)
            setPadding((14 * d).toInt(), (10 * d).toInt(), (14 * d).toInt(), (10 * d).toInt())
            layoutParams = LinearLayout.LayoutParams((460 * d).toInt(), (42 * d).toInt())
        }

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (16 * d).toInt() }
        }

        val btnSave = TextView(this).apply {
            text = "Save"
            setTextColor(Color.parseColor("#050507"))
            textSize = 11.5f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setBackgroundResource(R.drawable.selector_pill_primary)
            gravity = android.view.Gravity.CENTER
            setPadding((22 * d).toInt(), (8 * d).toInt(), (22 * d).toInt(), (8 * d).toInt())
            isFocusable = true
            isClickable = true
            setOnClickListener {
                val valStr = edit.text.toString().trim()
                dialog.dismiss()
                onSave(valStr)
            }
        }

        val btnCancel = TextView(this).apply {
            text = "Cancel"
            setTextColor(Color.WHITE)
            textSize = 11.5f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setBackgroundResource(R.drawable.selector_pill_secondary)
            gravity = android.view.Gravity.CENTER
            setPadding((18 * d).toInt(), (8 * d).toInt(), (18 * d).toInt(), (8 * d).toInt())
            isFocusable = true
            isClickable = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { leftMargin = (10 * d).toInt() }
            setOnClickListener { dialog.dismiss() }
        }

        btnRow.addView(btnSave)
        btnRow.addView(btnCancel)

        root.addView(tvTitle)
        root.addView(tvSub)
        root.addView(edit)
        root.addView(btnRow)

        dialog.setContentView(root)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        dialog.show()
        edit.requestFocus()
    }

    // ====================================================================
    // KEY HANDLING & LIFECYCLE
    // ====================================================================
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (detailMovieOverlay?.visibility == View.VISIBLE) {
                closeMovieDetail()
                return true
            }
            if (detailSeriesOverlay?.visibility == View.VISIBLE) {
                closeSeriesDetail()
                return true
            }
            if (currentTabIndex == 1 && screenSearch != null && inputSearch.text.isNotEmpty()) {
                inputSearch.text.clear()
                clearSearchResults()
                return true
            }
            if (currentTabIndex != 0) {
                switchTab(0, focusContent = true)
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onResume() {
        super.onResume()
        if (::continueAdapter.isInitialized) {
            refreshContinueWatching()
        }
        if (::episodeAdapter.isInitialized) {
            episodeAdapter.notifyDataSetChanged()
        }
        currentMovieItem?.let {
            if (detailMovieOverlay?.visibility == View.VISIBLE) {
                syncMovieDetailButtons(it)
            }
        }
        currentSeriesItem?.let { item ->
            if (detailSeriesOverlay?.visibility == View.VISIBLE) {
                val cw = WatchHistoryManager.getLatestForSeries(this, item.id)
                if (cw != null) {
                    btnSeriesResume.text = if (cw.positionMs > 10_000) {
                        String.format(Locale.US, "▶  Resume S%02d · E%02d", cw.season, cw.episode)
                    } else {
                        String.format(Locale.US, "▶  Up Next S%02d · E%02d", cw.season, cw.episode)
                    }
                }
            }
        }
        if (currentTabIndex == 3) {
            refreshLibraryGrid()
        } else if (currentTabIndex == 4) {
            refreshSettingsView()
        }
    }

    override fun onDestroy() {
        activeSearchFuture?.cancel(true)
        activeCatalogFuture?.cancel(true)
        activeMovieStreamsFuture?.cancel(true)
        mainHandler.removeCallbacksAndMessages(null)
        backgroundExecutor.shutdownNow()
        JewEngineManager.wipeCache(this)
        super.onDestroy()
    }
}
