package com.d4cx.streamflix

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.FilteringMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.SingleSampleMediaSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors

@OptIn(UnstableApi::class)
class PlayerActivity : Activity() {

    private lateinit var playerView: PlayerView
    private lateinit var hudTopBar: View
    private lateinit var vignetteTop: View
    private lateinit var hudControls: View
    private lateinit var vignetteBottom: View
    private lateinit var tvPlayerTitle: TextView
    private lateinit var tvPlayerStreamBadge: TextView
    private lateinit var btnPlayPause: ImageButton
    private lateinit var btnPrev: ImageButton
    private lateinit var btnNext: ImageButton
    private lateinit var btnSources: ImageButton
    private lateinit var btnSubtitles: ImageButton
    private lateinit var btnAudio: ImageButton
    private lateinit var btnSettings: ImageButton
    private lateinit var seekBar: SeekBar
    private lateinit var tvTimeCurrent: TextView
    private lateinit var tvTimeDuration: TextView
    private lateinit var tvSeekOsd: TextView
    private lateinit var layoutBuffering: View
    private lateinit var tvBufferingStatus: TextView
    private lateinit var menuOverlay: View
    private lateinit var tvMenuTitle: TextView
    private lateinit var rvMenuOptions: RecyclerView

    private var player: ExoPlayer? = null
    private var mediaSourceFactory: DefaultMediaSourceFactory? = null
    private var dataSourceFactory: DefaultDataSource.Factory? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val backgroundExecutor = Executors.newFixedThreadPool(3)

    private var isHudVisible = true
    private var isUserTrackingSeek = false
    private var isScrubbing = false
    private var targetSeekMs: Long? = null

    private var availableStreams: List<StreamItem> = emptyList()
    private var currentStreamIndex: Int = 0
    private var currentPlayingUri: String? = null
    private var currentAudioUri: String? = null
    private var currentSubtitleConfig: MediaItem.SubtitleConfiguration? = null

    private var openSubtitlesList: List<SubtitleItem> = emptyList()
    private var activeSubtitleId: String? = null
    private var activeSubtitleLang: String? = null
    private var hasAutoSelectedAudio = false

    private var mediaType: String = "series"
    private var mediaId: String = ""
    private var mediaTitle: String = ""
    private var mediaPoster: String = ""
    private var mediaBackdrop: String = ""
    private var seasonNum: Int = 1
    private var episodeNum: Int = 1

    private var lastSavedProgressMs: Long = 0L
    private var hasResumedInitialPosition: Boolean = false
    private var isStatsPolling = false
    private var playbackRequestTimestamp: Long = 0L
    private var isFirstFrameRendered: Boolean = false
    @Volatile
    private var prebufferJobId: Long = 0L

    private val bufferDisplayDebounceRunnable = Runnable {
        if (layoutBuffering.visibility != View.VISIBLE) {
            layoutBuffering.visibility = View.VISIBLE
        }
    }

    private val statsPollingRunnable = object : Runnable {
        override fun run() {
            if (!isStatsPolling) return
            val curStream = availableStreams.getOrNull(currentStreamIndex)
            val hash = curStream?.infoHash
            if (hash != null) {
                backgroundExecutor.execute {
                    val statsJson = StreamRepository.httpGet("http://127.0.0.1:${JewEngineManager.ENGINE_PORT}/stats?hash=$hash", 450)
                    if (statsJson != null) {
                        try {
                            val obj = JSONObject(statsJson)
                            val completed = obj.optLong("bytes_completed")
                            val activePeers = obj.optInt("active_peers")
                            val totalPeers = obj.optInt("total_peers")
                            val speed = obj.optDouble("download_speed", 0.0)
                            val mb = completed / (1024.0 * 1024.0)
                            val speedMb = speed / (1024.0 * 1024.0)
                            val peers = if (activePeers > 0) activePeers else totalPeers
                            Log.d("EXO-DEBUG", String.format(Locale.US, "[POLL] Speed=%.2f MB/s, Cached=%.1f MB, Peers=%d (act=%d, tot=%d), State=%d, Pos=%d ms, Buf=%d ms",
                                speedMb, mb, peers, activePeers, totalPeers, player?.playbackState ?: -1, player?.currentPosition ?: -1, player?.bufferedPosition ?: -1))
                            mainHandler.post {
                                if (!isStatsPolling) return@post
                                val qLabel = curStream.quality
                                tvPlayerStreamBadge.text = if (speedMb > 0.05) {
                                    String.format(Locale.US, "%s • %.1f MB/s • %d peers", qLabel, speedMb, peers)
                                } else {
                                    String.format(Locale.US, "%s • Cached %.1f MB • %d peers", qLabel, mb, peers)
                                }
                                if (layoutBuffering.visibility == View.VISIBLE) {
                                    tvBufferingStatus.text = if (speedMb > 0.05) {
                                        String.format(Locale.US, "Buffering %s • %.1f MB/s (%d peers)", qLabel, speedMb, peers)
                                    } else {
                                        String.format(Locale.US, "Buffering %s • %d peers", qLabel, peers)
                                    }
                                }
                            }
                        } catch (_: Exception) {}
                    }
                }
            } else if (curStream != null) {
                tvPlayerStreamBadge.text = "${curStream.quality} • Direct Stream"
            }
            mainHandler.postDelayed(this, 750)
        }
    }

    private val hideHudRunnable = Runnable { hideHud() }
    private val commitSeekRunnable = Runnable { commitSeekNow() }

    private val updateProgressRunnable = object : Runnable {
        override fun run() {
            val p = player
            if (p != null && !isScrubbing && !isUserTrackingSeek && !seekBar.hasFocus() && p.duration > 0) {
                val position = p.currentPosition
                val duration = p.duration
                seekBar.progress = ((position.toDouble() / duration.toDouble()) * 1000).toInt()
                tvTimeCurrent.text = formatTime(position)
                tvTimeDuration.text = formatTime(duration)

                val now = System.currentTimeMillis()
                if (now - lastSavedProgressMs >= 4000L && position > 0) {
                    lastSavedProgressMs = now
                    WatchHistoryManager.savePlaybackPosition(
                        context = this@PlayerActivity,
                        type = mediaType,
                        id = mediaId,
                        season = seasonNum,
                        episode = episodeNum,
                        positionMs = position,
                        durationMs = duration,
                        title = mediaTitle.substringBefore(" — "),
                        poster = mediaPoster,
                        backdrop = mediaBackdrop
                    )
                }
            }
            mainHandler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)

        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
        )

        mediaType = intent.getStringExtra(EXTRA_MEDIA_TYPE) ?: "series"
        mediaId = intent.getStringExtra(EXTRA_MEDIA_ID) ?: ""
        mediaTitle = intent.getStringExtra(EXTRA_TITLE) ?: "JewFlix Player"
        mediaPoster = intent.getStringExtra(EXTRA_POSTER) ?: ""
        mediaBackdrop = intent.getStringExtra(EXTRA_BACKDROP) ?: ""
        seasonNum = intent.getIntExtra(EXTRA_SEASON, 1)
        episodeNum = intent.getIntExtra(EXTRA_EPISODE, 1)

        initViews()
        initExoPlayer()
        setupListeners()
        loadSubtitlesInBackground()

        startStatsPolling()

        val directUrl = intent.getStringExtra(EXTRA_STREAM_URL)
        if (!directUrl.isNullOrEmpty()) {
            showBuffering("Starting engine & stream...")
            backgroundExecutor.execute {
                JewEngineManager.ensureRunning(this@PlayerActivity)
                mainHandler.post {
                    playDirectUrl(directUrl, null, 0L)
                }
            }
        } else if (pendingStreams != null && pendingStreamsId == streamSessionKey()) {
            availableStreams = pendingStreams!!
            currentStreamIndex = pendingStreamIndex.coerceIn(0, (availableStreams.size - 1).coerceAtLeast(0))
            pendingStreams = null
            if (availableStreams.isNotEmpty()) {
                val savedPos = WatchHistoryManager.getPlaybackPosition(this, mediaType, mediaId, seasonNum, episodeNum)
                playStreamItem(availableStreams[currentStreamIndex], savedPos)
            } else {
                resolveAndPlayStream()
            }
        } else {
            resolveAndPlayStream()
        }
    }

    private fun streamSessionKey(): String = "${mediaType}_${mediaId}_s${seasonNum}_e${episodeNum}"

    private fun initViews() {
        playerView = findViewById(R.id.playerView)
        hudTopBar = findViewById(R.id.hudTopBar)
        vignetteTop = findViewById(R.id.vignetteTop)
        hudControls = findViewById(R.id.hudControls)
        vignetteBottom = findViewById(R.id.vignetteBottom)
        tvPlayerTitle = findViewById(R.id.tvPlayerTitle)
        tvPlayerStreamBadge = findViewById(R.id.tvPlayerStreamBadge)
        btnPlayPause = findViewById(R.id.btnPlayPause)
        btnPrev = findViewById(R.id.btnPrev)
        btnNext = findViewById(R.id.btnNext)
        btnSources = findViewById(R.id.btnSources)
        btnSubtitles = findViewById(R.id.btnSubtitles)
        btnAudio = findViewById(R.id.btnAudio)
        btnSettings = findViewById(R.id.btnSettings)
        seekBar = findViewById(R.id.seekBar)
        tvTimeCurrent = findViewById(R.id.tvTimeCurrent)
        tvTimeDuration = findViewById(R.id.tvTimeDuration)
        tvSeekOsd = findViewById(R.id.tvSeekOsd)
        layoutBuffering = findViewById(R.id.layoutBuffering)
        tvBufferingStatus = findViewById(R.id.tvBufferingStatus)
        menuOverlay = findViewById(R.id.menuOverlay)
        tvMenuTitle = findViewById(R.id.tvMenuTitle)
        rvMenuOptions = findViewById(R.id.rvMenuOptions)

        rvMenuOptions.layoutManager = LinearLayoutManager(this)
        updateHeaderTitle()

        seekBar.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                if (isScrubbing && targetSeekMs != null) {
                    commitSeekNow()
                }
            } else {
                cancelHideHud()
            }
        }

        applySubtitleCustomization()

        if (mediaType.equals("movie", ignoreCase = true)) {
            btnPrev.visibility = View.GONE
            btnNext.visibility = View.GONE
            btnPlayPause.nextFocusLeftId = View.NO_ID
            btnPlayPause.nextFocusRightId = R.id.btnSources
            btnSources.nextFocusLeftId = R.id.btnPlayPause
        } else {
            btnPrev.visibility = View.VISIBLE
            btnNext.visibility = View.VISIBLE
            btnPlayPause.nextFocusLeftId = R.id.btnPrev
            btnPlayPause.nextFocusRightId = R.id.btnNext
            btnNext.nextFocusLeftId = R.id.btnPlayPause
            btnNext.nextFocusRightId = R.id.btnSources
            btnSources.nextFocusLeftId = R.id.btnNext
        }

        btnPlayPause.requestFocus()
        scheduleHideHud()
    }

    private fun updateHeaderTitle() {
        tvPlayerTitle.text = if (mediaType.equals("movie", true)) {
            mediaTitle
        } else {
            String.format(Locale.US, "%s  •  S%02d · E%02d", mediaTitle.substringBefore(" — "), seasonNum, episodeNum)
        }
    }

    private fun initExoPlayer() {
        // Calibrated zero-stall torrent buffer profile: robust playback runway with 128MB cushion
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 30_000,
                /* maxBufferMs = */ 90_000,
                /* bufferForPlaybackMs = */ 5_000,
                /* bufferForPlaybackAfterRebufferMs = */ 8_000
            )
            .setTargetBufferBytes(128 * 1024 * 1024)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setUserAgent("JewFlix/2.0 (Linux; Android TV)")
            .setConnectTimeoutMs(45_000)
            .setReadTimeoutMs(60_000)

        val dsf = DefaultDataSource.Factory(this, httpDataSourceFactory)
        this.dataSourceFactory = dsf

        val extractorsFactory = DefaultExtractorsFactory()
            .setConstantBitrateSeekingEnabled(true)

        val msFactory = DefaultMediaSourceFactory(dsf, extractorsFactory)
            .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(6))
        this.mediaSourceFactory = msFactory

        val renderersFactory = DefaultRenderersFactory(this).apply {
            setEnableDecoderFallback(true)
        }

        player = ExoPlayer.Builder(this, renderersFactory)
            .setMediaSourceFactory(msFactory)
            .setLoadControl(loadControl)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()

        playerView.player = player
        applySubtitleCustomization()

        player?.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                Log.w("EXO-STATE", "STATE CHANGED -> $state (BUFFERING=2, READY=3, ENDED=4, IDLE=1), Pos=${player?.currentPosition}ms, BufPos=${player?.bufferedPosition}ms")
                when (state) {
                    Player.STATE_BUFFERING -> showBuffering()
                    Player.STATE_READY -> {
                        hideBuffering()
                        updatePlayPauseIcon()
                        player?.duration?.let { dur ->
                            if (dur > 0) tvTimeDuration.text = formatTime(dur)
                            if (!hasResumedInitialPosition) {
                                hasResumedInitialPosition = true
                                val savedPos = WatchHistoryManager.getPlaybackPosition(
                                    this@PlayerActivity, mediaType, mediaId, seasonNum, episodeNum
                                )
                                if (savedPos > 10_000 && dur > 30_000 && savedPos < (dur - 30_000) && (player?.currentPosition ?: 0L) < 5_000) {
                                    player?.seekTo(savedPos)
                                    showSeekOsd("Resumed (${formatTime(savedPos)})")
                                }
                            }
                        }
                    }
                    Player.STATE_ENDED -> {
                        hideBuffering()
                        showHud()
                        btnPlayPause.setImageResource(R.drawable.ic_player_play)
                        player?.duration?.let { dur ->
                            WatchHistoryManager.savePlaybackPosition(
                                context = this@PlayerActivity,
                                type = mediaType,
                                id = mediaId,
                                season = seasonNum,
                                episode = episodeNum,
                                positionMs = dur,
                                durationMs = dur,
                                title = mediaTitle.substringBefore(" — "),
                                poster = mediaPoster,
                                backdrop = mediaBackdrop
                            )
                        }
                        JewEngineManager.wipeCache(this@PlayerActivity)
                    }
                    Player.STATE_IDLE -> hideBuffering()
                }
            }

            override fun onRenderedFirstFrame() {
                isFirstFrameRendered = true
                hideBuffering()
            }

            override fun onTracksChanged(tracks: Tracks) {
                autoSelectPreferredAudioTrack(tracks)
                applyActiveSubtitleTrackIfPending(tracks)
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                updatePlayPauseIcon()
                if (isPlaying) {
                    hideBuffering()
                    scheduleHideHud()
                } else {
                    showHud()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.e("EXO-DEBUG", "Stream error: ${error.errorCodeName} (${error.message})")
                if (!isFirstFrameRendered) {
                    hideBuffering()
                    showHud()
                    val currentPos = player?.currentPosition ?: 0L
                    Toast.makeText(
                        this@PlayerActivity,
                        "Stream slow (${error.errorCodeName}) — switching source...",
                        Toast.LENGTH_SHORT
                    ).show()

                    if (currentStreamIndex + 1 < availableStreams.size) {
                        currentStreamIndex++
                        playStreamItem(availableStreams[currentStreamIndex], resumePositionMs = currentPos)
                    }
                } else {
                    // Mid-playback glitch: don't abandon the movie, let player re-sync
                    showBuffering()
                }
            }
        })

        mainHandler.post(updateProgressRunnable)
    }

    private fun startStatsPolling() {
        if (!isStatsPolling) {
            isStatsPolling = true
            mainHandler.post(statsPollingRunnable)
        }
    }

    private fun showBuffering(message: String? = null) {
        if (message != null) {
            tvBufferingStatus.text = message
        }
        if (!isFirstFrameRendered) {
            // Initial connect: show instantly
            layoutBuffering.visibility = View.VISIBLE
        } else {
            // Mid-playback: debounce by 800ms so micro-rebuffering or GOP decodes NEVER flash on screen
            mainHandler.removeCallbacks(bufferDisplayDebounceRunnable)
            mainHandler.postDelayed(bufferDisplayDebounceRunnable, 800L)
        }
    }

    private fun hideBuffering() {
        mainHandler.removeCallbacks(bufferDisplayDebounceRunnable)
        layoutBuffering.visibility = View.GONE
    }

    private fun autoSelectPreferredAudioTrack(tracks: Tracks) {
        if (hasAutoSelectedAudio) return
        val p = player ?: return

        for (group in tracks.groups) {
            if (group.type == C.TRACK_TYPE_AUDIO) {
                for (i in 0 until group.length) {
                    val format = group.getTrackFormat(i)
                    val lang = (format.language ?: format.label ?: "").lowercase()
                    if (lang.contains("ar") || lang.contains("ara") || lang.contains("arabic") || lang.contains("عرب")) {
                        p.trackSelectionParameters = p.trackSelectionParameters
                            .buildUpon()
                            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, i))
                            .build()
                        hasAutoSelectedAudio = true
                        return
                    }
                }
            }
        }

        for (group in tracks.groups) {
            if (group.type == C.TRACK_TYPE_AUDIO) {
                for (i in 0 until group.length) {
                    val format = group.getTrackFormat(i)
                    val lang = (format.language ?: format.label ?: "").lowercase()
                    if (lang.contains("jp") || lang.contains("japan") || lang.contains("ja")) {
                        p.trackSelectionParameters = p.trackSelectionParameters
                            .buildUpon()
                            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, i))
                            .build()
                        hasAutoSelectedAudio = true
                        return
                    }
                }
            }
        }
    }

    private fun applyActiveSubtitleTrackIfPending(tracks: Tracks) {
        val p = player ?: return
        val langTarget = activeSubtitleLang ?: return
        if (activeSubtitleId == "off") {
            p.trackSelectionParameters = p.trackSelectionParameters
                .buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .build()
            return
        }

        var matchedOverride: TrackSelectionOverride? = null
        for (group in tracks.groups) {
            if (group.type == C.TRACK_TYPE_TEXT) {
                for (i in 0 until group.length) {
                    val format = group.getTrackFormat(i)
                    val lang = (format.language ?: "").lowercase()
                    val label = (format.label ?: "").lowercase()
                    val id = format.id ?: ""

                    val isMatch = (activeSubtitleId?.let { id.contains(it) } == true)
                        || label.contains(langTarget, ignoreCase = true)
                        || (lang.isNotEmpty() && (lang == langTarget || lang.startsWith(langTarget) || langTarget.startsWith(lang)))

                    if (isMatch) {
                        matchedOverride = TrackSelectionOverride(group.mediaTrackGroup, i)
                        break
                    }
                }
                if (matchedOverride != null) break
            }
        }

        if (matchedOverride != null) {
            p.trackSelectionParameters = p.trackSelectionParameters
                .buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .setOverrideForType(matchedOverride)
                .build()
        }
    }

    private fun setupListeners() {
        btnPlayPause.setOnClickListener {
            togglePlayPause()
            scheduleHideHud()
        }

        btnPrev.setOnClickListener {
            if (!mediaType.equals("movie", ignoreCase = true) && episodeNum > 1) {
                saveCurrentPlaybackState()
                episodeNum--
                hasResumedInitialPosition = false
                updateHeaderTitle()
                loadSubtitlesInBackground()
                resolveAndPlayStream()
            }
            scheduleHideHud()
        }

        btnNext.setOnClickListener {
            if (!mediaType.equals("movie", ignoreCase = true)) {
                saveCurrentPlaybackState()
                episodeNum++
                hasResumedInitialPosition = false
                updateHeaderTitle()
                loadSubtitlesInBackground()
                resolveAndPlayStream()
            }
            scheduleHideHud()
        }

        btnSources.setOnClickListener { showSourcesMenu() }
        btnSubtitles.setOnClickListener { showSubtitlesMenu() }
        btnAudio.setOnClickListener { showAudioMenu() }
        btnSettings.setOnClickListener { showSettingsMenu() }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && !isScrubbing) {
                    val p = player ?: return
                    val duration = p.duration
                    if (duration > 0) {
                        tvTimeCurrent.text = formatTime((duration * progress) / 1000)
                    }
                    scheduleHideHud()
                }
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {
                isUserTrackingSeek = true
                isScrubbing = true
                cancelHideHud()
            }

            override fun onStopTrackingTouch(sb: SeekBar?) {
                val p = player ?: return
                val duration = p.duration
                if (duration > 0) {
                    p.seekTo((duration * (sb?.progress ?: 0)) / 1000)
                }
                mainHandler.postDelayed({
                    isUserTrackingSeek = false
                    isScrubbing = false
                }, 300)
                scheduleHideHud()
            }
        })
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            scheduleHideHud()

            if (menuOverlay.visibility == View.VISIBLE) {
                if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                    closeMenu()
                    return true
                }
                return super.dispatchKeyEvent(event)
            }

            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (seekBar.hasFocus()) {
                        handleSeekBarScrub(isForward = false)
                        return true
                    }
                    if (!isHudVisible) {
                        showHud()
                        seekBar.requestFocus()
                        handleSeekBarScrub(isForward = false)
                        return true
                    }
                    return super.dispatchKeyEvent(event)
                }

                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (seekBar.hasFocus()) {
                        handleSeekBarScrub(isForward = true)
                        return true
                    }
                    if (!isHudVisible) {
                        showHud()
                        seekBar.requestFocus()
                        handleSeekBarScrub(isForward = true)
                        return true
                    }
                    return super.dispatchKeyEvent(event)
                }

                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_STEP_FORWARD -> {
                    handleSeekBarScrub(isForward = true)
                    return true
                }

                KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_MEDIA_STEP_BACKWARD -> {
                    handleSeekBarScrub(isForward = false)
                    return true
                }

                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    if (isScrubbing && targetSeekMs != null) {
                        commitSeekNow()
                        return true
                    }
                    if (!isHudVisible) {
                        showHud()
                        togglePlayPause()
                        return true
                    }
                    if (seekBar.hasFocus() || btnPlayPause.hasFocus()) {
                        togglePlayPause()
                        return true
                    }
                }

                KeyEvent.KEYCODE_DPAD_UP -> {
                    if (!isHudVisible) {
                        showHud()
                        seekBar.requestFocus()
                        return true
                    }
                    if (btnPlayPause.hasFocus() || btnPrev.hasFocus() || btnNext.hasFocus()
                        || btnSources.hasFocus() || btnSubtitles.hasFocus()
                        || btnAudio.hasFocus() || btnSettings.hasFocus()
                    ) {
                        seekBar.requestFocus()
                        return true
                    }
                }

                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (!isHudVisible) {
                        showHud()
                        btnPlayPause.requestFocus()
                        return true
                    }
                    if (seekBar.hasFocus()) {
                        if (isScrubbing && targetSeekMs != null) commitSeekNow()
                        btnPlayPause.requestFocus()
                        return true
                    }
                }

                KeyEvent.KEYCODE_BACK -> {
                    if (isHudVisible) {
                        hideHud()
                        return true
                    }
                    saveCurrentPlaybackState()
                    finish()
                    return true
                }

                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                    togglePlayPause()
                    return true
                }

                KeyEvent.KEYCODE_MEDIA_PLAY -> {
                    player?.play()
                    updatePlayPauseIcon()
                    return true
                }

                KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                    player?.pause()
                    updatePlayPauseIcon()
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun handleSeekBarScrub(isForward: Boolean) {
        val p = player ?: return
        val duration = p.duration
        if (duration <= 0) return

        isScrubbing = true
        mainHandler.removeCallbacks(commitSeekRunnable)

        val currentTarget = targetSeekMs ?: p.currentPosition
        val nextTarget = (currentTarget + (if (isForward) 10_000L else -10_000L)).coerceIn(0L, duration)
        targetSeekMs = nextTarget

        seekBar.progress = ((nextTarget.toDouble() / duration.toDouble()) * 1000).toInt()
        tvTimeCurrent.text = formatTime(nextTarget)

        val diffSec = (nextTarget - p.currentPosition) / 1000
        val sign = if (diffSec >= 0) "+" else ""
        showSeekOsd("$sign${diffSec}s (${formatTime(nextTarget)})")

        mainHandler.postDelayed(commitSeekRunnable, 400)
        scheduleHideHud()
    }

    private fun commitSeekNow() {
        mainHandler.removeCallbacks(commitSeekRunnable)
        val target = targetSeekMs ?: return
        val p = player ?: return
        p.seekTo(target)
        targetSeekMs = null
        showSeekOsd(formatTime(target))
        mainHandler.postDelayed({ isScrubbing = false }, 350)
    }

    private fun loadSubtitlesInBackground() {
        backgroundExecutor.execute {
            val subs = SubtitleRepository.fetchSubtitles(mediaType, mediaId, seasonNum, episodeNum)
            mainHandler.post { openSubtitlesList = subs }
        }
    }

    private fun resolveAndPlayStream() {
        showBuffering("Finding best stream...")
        hasResumedInitialPosition = false
        backgroundExecutor.execute {
            JewEngineManager.ensureRunning(this@PlayerActivity)
            val streams = StreamRepository.fetchStreams(
                type = mediaType,
                id = mediaId,
                season = seasonNum,
                episode = episodeNum,
                context = this@PlayerActivity
            )
            availableStreams = streams
            currentStreamIndex = 0
            val savedPos = WatchHistoryManager.getPlaybackPosition(
                this@PlayerActivity, mediaType, mediaId, seasonNum, episodeNum
            )

            mainHandler.post {
                if (streams.isNotEmpty()) {
                    playStreamItem(streams[0], resumePositionMs = savedPos)
                    if (savedPos > 10_000) showSeekOsd("Resuming (${formatTime(savedPos)})")
                } else {
                    hideBuffering()
                    Toast.makeText(
                        this,
                        "No streams found for $mediaTitle S$seasonNum:E$episodeNum",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun playStreamItem(stream: StreamItem, resumePositionMs: Long = 0L) {
        playbackRequestTimestamp = System.currentTimeMillis()
        isFirstFrameRendered = false

        showBuffering("Connecting ${stream.quality} source...")
        tvPlayerStreamBadge.text = "${stream.quality} • Initializing..."
        backgroundExecutor.execute {
            JewEngineManager.ensureRunning(this@PlayerActivity)
            val streamUrl = StreamRepository.resolvePlayableUrl(stream)
            mainHandler.post {
                if (streamUrl.isNotEmpty()) {
                    playMediaInternal(streamUrl, stream.audioUrl, currentSubtitleConfig, resumePositionMs)
                } else {
                    hideBuffering()
                    Toast.makeText(this@PlayerActivity, "Failed to resolve stream URL", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun playDirectUrl(url: String, audioUrl: String? = null, resumePositionMs: Long = 0L) {
        playMediaInternal(url, audioUrl, currentSubtitleConfig, resumePositionMs)
    }

    private fun playMediaInternal(
        videoUrl: String,
        audioUrl: String?,
        subConfig: MediaItem.SubtitleConfiguration?,
        resumePositionMs: Long
    ) {
        val p = player ?: return
        val factory = mediaSourceFactory ?: return
        val dsf = dataSourceFactory ?: return

        currentPlayingUri = videoUrl
        currentAudioUri = audioUrl
        currentSubtitleConfig = subConfig
        hasAutoSelectedAudio = false

        p.stop()
        p.clearMediaItems()

        val rawVideoSource: MediaSource = factory.createMediaSource(MediaItem.fromUri(Uri.parse(videoUrl)))
        val baseSource: MediaSource = if (!audioUrl.isNullOrEmpty()) {
            val rawAudioSource = factory.createMediaSource(MediaItem.fromUri(Uri.parse(audioUrl)))
            MergingMediaSource(
                true,
                true,
                FilteringMediaSource(rawVideoSource, C.TRACK_TYPE_VIDEO),
                FilteringMediaSource(rawAudioSource, C.TRACK_TYPE_AUDIO)
            )
        } else {
            rawVideoSource
        }

        val finalSource: MediaSource = if (subConfig != null) {
            val subSource = SingleSampleMediaSource.Factory(dsf).createMediaSource(subConfig, C.TIME_UNSET)
            MergingMediaSource(baseSource, subSource)
        } else {
            baseSource
        }

        p.setMediaSource(finalSource)
        if (resumePositionMs > 0) {
            p.seekTo(resumePositionMs)
        }
        p.prepare()
        p.playWhenReady = true
        scheduleHideHud()
    }

    private fun togglePlayPause() {
        val p = player ?: return
        if (p.isPlaying) p.pause() else p.play()
        updatePlayPauseIcon()
    }

    private fun updatePlayPauseIcon() {
        val isPlaying = player?.isPlaying == true
        btnPlayPause.setImageResource(
            if (isPlaying) R.drawable.ic_player_pause else R.drawable.ic_player_play
        )
    }

    private fun showSeekOsd(text: String) {
        tvSeekOsd.text = text
        tvSeekOsd.visibility = View.VISIBLE
        tvSeekOsd.alpha = 1f
        mainHandler.removeCallbacksAndMessages(tvSeekOsd)
        mainHandler.postDelayed({
            tvSeekOsd.animate().alpha(0f).setDuration(250).withEndAction {
                tvSeekOsd.visibility = View.GONE
            }.start()
        }, 700)
    }

    private fun showHud() {
        isHudVisible = true
        hudControls.animate().cancel()
        vignetteBottom.animate().cancel()
        hudTopBar.animate().cancel()
        vignetteTop.animate().cancel()

        hudControls.alpha = 1f
        hudControls.visibility = View.VISIBLE
        vignetteBottom.alpha = 0.85f
        vignetteBottom.visibility = View.VISIBLE
        hudTopBar.alpha = 1f
        hudTopBar.visibility = View.VISIBLE
        vignetteTop.alpha = 0.85f
        vignetteTop.visibility = View.VISIBLE
    }

    private fun hideHud() {
        if (!isHudVisible || menuOverlay.visibility == View.VISIBLE) return
        isHudVisible = false
        hudControls.animate().alpha(0f).setDuration(300).withEndAction { hudControls.visibility = View.GONE }.start()
        vignetteBottom.animate().alpha(0f).setDuration(300).withEndAction { vignetteBottom.visibility = View.GONE }.start()
        hudTopBar.animate().alpha(0f).setDuration(300).withEndAction { hudTopBar.visibility = View.GONE }.start()
        vignetteTop.animate().alpha(0f).setDuration(300).withEndAction { vignetteTop.visibility = View.GONE }.start()
    }

    private fun scheduleHideHud() {
        cancelHideHud()
        if (player?.isPlaying == true) {
            mainHandler.postDelayed(hideHudRunnable, 4000)
        }
    }

    private fun cancelHideHud() {
        mainHandler.removeCallbacks(hideHudRunnable)
    }

    // ==============================================================
    // IN-PLAYER SOURCE SWITCHER
    // ==============================================================

    private fun showSourcesMenu() {
        cancelHideHud()
        val p = player ?: return
        val options = mutableListOf<MenuOption>()

        if (availableStreams.isEmpty()) {
            options.add(MenuOption("Re-fetch Stream Sources") {
                closeMenu()
                resolveAndPlayStream()
            })
        } else {
            availableStreams.forEachIndexed { idx, stream ->
                val tag = "${stream.quality} • ${stream.sizeText.ifEmpty { "Direct" }} • ${if (stream.isDirect) "Direct" else "${stream.seeders}p"}"
                val title = stream.releaseTitle.ifEmpty { stream.title }.take(38)
                options.add(MenuOption("[$tag]\n$title", isSelected = idx == currentStreamIndex) {
                    val curPos = p.currentPosition
                    currentStreamIndex = idx
                    closeMenu()
                    playStreamItem(stream, resumePositionMs = curPos)
                })
            }
        }
        openMenu("Switch Stream Source", options)
    }

    // ==============================================================
    // SUBTITLES CUSTOMIZATION & STYLING
    // ==============================================================

    private data class StyleColors(val bg: Int, val window: Int, val edgeType: Int, val edgeColor: Int)

    private fun applySubtitleCustomization() {
        val prefs = getSharedPreferences("jewflix_sub_prefs", MODE_PRIVATE)
        val bgStyle = prefs.getInt("sub_bg_style", 0)
        val position = prefs.getInt("sub_position", 0)
        val textSize = prefs.getInt("sub_text_size", 1)
        val textColorIndex = prefs.getInt("sub_text_color", 0)

        val textColor = when (textColorIndex) {
            1 -> Color.parseColor("#FFE600")
            2 -> Color.parseColor("#80DEEA")
            3 -> Color.parseColor("#A5D6A7")
            else -> Color.WHITE
        }

        val styleColors = when (bgStyle) {
            1 -> StyleColors(Color.TRANSPARENT, Color.TRANSPARENT, CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW, Color.BLACK)
            2 -> StyleColors(Color.argb(140, 0, 0, 0), Color.TRANSPARENT, CaptionStyleCompat.EDGE_TYPE_NONE, Color.TRANSPARENT)
            3 -> StyleColors(Color.BLACK, Color.TRANSPARENT, CaptionStyleCompat.EDGE_TYPE_NONE, Color.TRANSPARENT)
            else -> StyleColors(Color.TRANSPARENT, Color.TRANSPARENT, CaptionStyleCompat.EDGE_TYPE_OUTLINE, Color.BLACK)
        }

        val bottomPadding = when (position) {
            1 -> 0.14f
            2 -> 0.24f
            3 -> 0.82f
            else -> 0.06f
        }

        val textFraction = when (textSize) {
            0 -> 0.040f
            2 -> 0.068f
            3 -> 0.084f
            else -> 0.053f
        }

        val captionStyle = CaptionStyleCompat(
            textColor,
            styleColors.bg,
            styleColors.window,
            styleColors.edgeType,
            styleColors.edgeColor,
            null
        )

        playerView.subtitleView?.apply {
            setStyle(captionStyle)
            setBottomPaddingFraction(bottomPadding)
            setFractionalTextSize(textFraction)
            setApplyEmbeddedStyles(false)
            setApplyEmbeddedFontSizes(false)
        }
    }

    private fun showSubtitleCustomizationMenu() {
        val prefs = getSharedPreferences("jewflix_sub_prefs", MODE_PRIVATE)
        val currentBg = prefs.getInt("sub_bg_style", 0)
        val currentPos = prefs.getInt("sub_position", 0)
        val currentSize = prefs.getInt("sub_text_size", 1)
        val currentColor = prefs.getInt("sub_text_color", 0)

        val options = listOf(
            MenuOption("« Back to Subtitles") { showSubtitlesMenu() },
            MenuOption("Background: Outline Only (No Box) ★", isSelected = currentBg == 0) {
                prefs.edit().putInt("sub_bg_style", 0).apply(); applySubtitleCustomization(); showSubtitleCustomizationMenu()
            },
            MenuOption("Background: Drop Shadow", isSelected = currentBg == 1) {
                prefs.edit().putInt("sub_bg_style", 1).apply(); applySubtitleCustomization(); showSubtitleCustomizationMenu()
            },
            MenuOption("Background: Translucent Box", isSelected = currentBg == 2) {
                prefs.edit().putInt("sub_bg_style", 2).apply(); applySubtitleCustomization(); showSubtitleCustomizationMenu()
            },
            MenuOption("Background: Solid Black Box", isSelected = currentBg == 3) {
                prefs.edit().putInt("sub_bg_style", 3).apply(); applySubtitleCustomization(); showSubtitleCustomizationMenu()
            },
            MenuOption("Position: Standard Bottom (6%)", isSelected = currentPos == 0) {
                prefs.edit().putInt("sub_position", 0).apply(); applySubtitleCustomization(); showSubtitleCustomizationMenu()
            },
            MenuOption("Position: Raised Above HUD (14%)", isSelected = currentPos == 1) {
                prefs.edit().putInt("sub_position", 1).apply(); applySubtitleCustomization(); showSubtitleCustomizationMenu()
            },
            MenuOption("Position: Lower-Third (24%)", isSelected = currentPos == 2) {
                prefs.edit().putInt("sub_position", 2).apply(); applySubtitleCustomization(); showSubtitleCustomizationMenu()
            },
            MenuOption("Text Size: Small", isSelected = currentSize == 0) {
                prefs.edit().putInt("sub_text_size", 0).apply(); applySubtitleCustomization(); showSubtitleCustomizationMenu()
            },
            MenuOption("Text Size: Normal", isSelected = currentSize == 1) {
                prefs.edit().putInt("sub_text_size", 1).apply(); applySubtitleCustomization(); showSubtitleCustomizationMenu()
            },
            MenuOption("Text Size: Large", isSelected = currentSize == 2) {
                prefs.edit().putInt("sub_text_size", 2).apply(); applySubtitleCustomization(); showSubtitleCustomizationMenu()
            },
            MenuOption("Text Size: Extra Large", isSelected = currentSize == 3) {
                prefs.edit().putInt("sub_text_size", 3).apply(); applySubtitleCustomization(); showSubtitleCustomizationMenu()
            },
            MenuOption("Color: Crisp White", isSelected = currentColor == 0) {
                prefs.edit().putInt("sub_text_color", 0).apply(); applySubtitleCustomization(); showSubtitleCustomizationMenu()
            },
            MenuOption("Color: Anime Yellow", isSelected = currentColor == 1) {
                prefs.edit().putInt("sub_text_color", 1).apply(); applySubtitleCustomization(); showSubtitleCustomizationMenu()
            },
            MenuOption("Color: Soft Cyan", isSelected = currentColor == 2) {
                prefs.edit().putInt("sub_text_color", 2).apply(); applySubtitleCustomization(); showSubtitleCustomizationMenu()
            }
        )
        openMenu("Subtitle Appearance", options)
    }

    private fun showSubtitlesMenu() {
        cancelHideHud()
        val p = player ?: return
        val currentTracks = p.currentTracks
        val options = mutableListOf<MenuOption>()

        options.add(MenuOption("⚙ Subtitle Appearance & Size") {
            showSubtitleCustomizationMenu()
        })

        options.add(MenuOption("Subtitles Off", isSelected = activeSubtitleId == "off") {
            p.trackSelectionParameters = p.trackSelectionParameters
                .buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .build()
            activeSubtitleId = "off"
            activeSubtitleLang = null
            closeMenu()
        })

        for (group in currentTracks.groups) {
            if (group.type == C.TRACK_TYPE_TEXT) {
                for (i in 0 until group.length) {
                    val format = group.getTrackFormat(i)
                    val label = format.label ?: format.language ?: "Embedded Subtitle #${i + 1}"
                    val isSelected = group.isTrackSelected(i) && activeSubtitleId == "embedded_$i"
                    options.add(MenuOption("Embedded: $label", isSelected) {
                        p.trackSelectionParameters = p.trackSelectionParameters
                            .buildUpon()
                            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, i))
                            .build()
                        activeSubtitleId = "embedded_$i"
                        activeSubtitleLang = format.language
                        closeMenu()
                    })
                }
            }
        }

        for (sub in openSubtitlesList) {
            options.add(MenuOption("OpenSubtitles: ${sub.langLabel}", activeSubtitleId == sub.id) {
                applyOpenSubtitle(sub)
                closeMenu()
            })
        }

        openMenu("Subtitles & Languages", options)
    }

    private fun detectSubMime(url: String): String {
        val lower = url.lowercase(Locale.US)
        return when {
            lower.contains(".vtt") -> MimeTypes.TEXT_VTT
            lower.contains(".ass") || lower.contains(".ssa") -> MimeTypes.TEXT_SSA
            else -> MimeTypes.APPLICATION_SUBRIP
        }
    }

    private fun applyOpenSubtitle(sub: SubtitleItem) {
        val p = player ?: return
        val currentUri = currentPlayingUri ?: return
        val currentPos = p.currentPosition

        showBuffering("Loading ${sub.langLabel} subtitles...")
        backgroundExecutor.execute {
            val mimeType = detectSubMime(sub.url)
            val ext = when (mimeType) {
                MimeTypes.TEXT_VTT -> "vtt"
                MimeTypes.TEXT_SSA -> "ass"
                else -> "srt"
            }
            val safeId = sub.id.replace(Regex("[^a-zA-Z0-9_-]"), "")
            val cacheSubFile = File(cacheDir, "sub_${safeId}.$ext")
            var fileReady = false
            try {
                if (!cacheSubFile.exists() || cacheSubFile.length() == 0L) {
                    val conn = URL(sub.url).openConnection() as HttpURLConnection
                    conn.connectTimeout = 5000
                    conn.readTimeout = 5000
                    conn.setRequestProperty("User-Agent", "JewFlix/2.0 (Android TV)")
                    if (conn.responseCode in 200..299) {
                        conn.inputStream.use { input ->
                            FileOutputStream(cacheSubFile).use { output -> input.copyTo(output) }
                        }
                    }
                    conn.disconnect()
                }
                fileReady = cacheSubFile.exists() && cacheSubFile.length() > 0
            } catch (_: Exception) {}

            mainHandler.post {
                hideBuffering()
                if (!fileReady && !sub.url.startsWith("http")) {
                    Toast.makeText(this@PlayerActivity, "Failed to download subtitle file", Toast.LENGTH_SHORT).show()
                    return@post
                }

                val subUri = if (fileReady) Uri.fromFile(cacheSubFile) else Uri.parse(sub.url)
                val subConfig = MediaItem.SubtitleConfiguration.Builder(subUri)
                    .setMimeType(mimeType)
                    .setLanguage(sub.langCode)
                    .setLabel("OpenSubtitles: ${sub.langLabel}")
                    .setSelectionFlags(C.SELECTION_FLAG_DEFAULT or C.SELECTION_FLAG_FORCED)
                    .build()

                activeSubtitleId = sub.id
                activeSubtitleLang = sub.langCode

                p.trackSelectionParameters = p.trackSelectionParameters
                    .buildUpon()
                    .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                    .setPreferredTextLanguage(sub.langCode)
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                    .build()

                // Preserve MergingMediaSource if playing dual-audio (e.g. Arabic dub + BluRay video)
                playMediaInternal(currentUri, currentAudioUri, subConfig, currentPos)
                Toast.makeText(this@PlayerActivity, "Subtitles: ${sub.langLabel} active", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showAudioMenu() {
        cancelHideHud()
        val p = player ?: return
        val currentTracks = p.currentTracks
        val options = mutableListOf<MenuOption>()

        for (group in currentTracks.groups) {
            if (group.type == C.TRACK_TYPE_AUDIO) {
                for (i in 0 until group.length) {
                    val format = group.getTrackFormat(i)
                    val rawLang = (format.language ?: format.label ?: "").lowercase()
                    val isSelected = group.isTrackSelected(i)

                    val cleanLabel = when {
                        rawLang.contains("ar") -> "Arabic Dub (العربية)"
                        rawLang.contains("jp") || rawLang.contains("ja") -> "Japanese (Original)"
                        rawLang.contains("en") -> "English"
                        format.label != null -> format.label!!
                        format.language != null -> format.language!!.uppercase()
                        else -> "Audio Track #${i + 1}"
                    }

                    options.add(MenuOption(cleanLabel, isSelected) {
                        p.trackSelectionParameters = p.trackSelectionParameters
                            .buildUpon()
                            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, i))
                            .build()
                        closeMenu()
                    })
                }
            }
        }

        if (options.isEmpty()) {
            options.add(MenuOption("Single audio track in this release", isSelected = true) { closeMenu() })
            options.add(MenuOption("Switch Stream Source for Different Audio →") { showSourcesMenu() })
        }

        openMenu("Audio Language", options)
    }

    private fun showSettingsMenu() {
        cancelHideHud()
        val p = player ?: return
        val options = listOf(
            MenuOption("Switch Stream Source (${availableStreams.size} available)") { showSourcesMenu() },
            MenuOption("Speed: 1.0x (Normal)") { p.setPlaybackSpeed(1.0f); closeMenu() },
            MenuOption("Speed: 1.25x") { p.setPlaybackSpeed(1.25f); closeMenu() },
            MenuOption("Speed: 1.5x") { p.setPlaybackSpeed(1.5f); closeMenu() },
            MenuOption("Aspect: Fit (Original)") { playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT; closeMenu() },
            MenuOption("Aspect: Zoom (Fill Screen)") { playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM; closeMenu() },
            MenuOption("Aspect: Stretch (16:9)") { playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL; closeMenu() }
        )
        openMenu("Playback Settings", options)
    }

    private fun openMenu(title: String, options: List<MenuOption>) {
        tvMenuTitle.text = title
        rvMenuOptions.adapter = MenuOptionAdapter(options)
        menuOverlay.visibility = View.VISIBLE
        menuOverlay.alpha = 1f
        rvMenuOptions.post {
            rvMenuOptions.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() ?: rvMenuOptions.requestFocus()
        }
    }

    private fun closeMenu() {
        menuOverlay.visibility = View.GONE
        btnPlayPause.requestFocus()
        scheduleHideHud()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        scheduleHideHud()
        return super.onKeyDown(keyCode, event)
    }

    private fun formatTime(millis: Long): String {
        if (millis <= 0) {
            val shouldShowHours = (player?.duration ?: 0) >= 3600_000L
            return if (shouldShowHours) "0:00:00" else "00:00"
        }
        val totalSec = millis / 1000
        val sec = totalSec % 60
        val min = (totalSec / 60) % 60
        val hours = totalSec / 3600
        return if (hours > 0 || (player?.duration ?: 0) >= 3600_000L) {
            String.format(Locale.US, "%d:%02d:%02d", hours, min, sec)
        } else {
            String.format(Locale.US, "%02d:%02d", min, sec)
        }
    }

    private fun saveCurrentPlaybackState() {
        val p = player ?: return
        val pos = p.currentPosition
        val dur = p.duration
        if (dur > 0 && pos > 0) {
            WatchHistoryManager.savePlaybackPosition(
                context = this,
                type = mediaType,
                id = mediaId,
                season = seasonNum,
                episode = episodeNum,
                positionMs = pos,
                durationMs = dur,
                title = mediaTitle.substringBefore(" — "),
                poster = mediaPoster,
                backdrop = mediaBackdrop
            )
        }
    }

    override fun onPause() {
        super.onPause()
        saveCurrentPlaybackState()
        player?.pause()
    }

    override fun onStop() {
        super.onStop()
        saveCurrentPlaybackState()
        if (isFinishing) {
            JewEngineManager.wipeCache(this)
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        val directUrl = intent?.getStringExtra(EXTRA_STREAM_URL)
        if (!directUrl.isNullOrEmpty()) {
            showBuffering("Starting engine & stream...")
            backgroundExecutor.execute {
                JewEngineManager.ensureRunning(this@PlayerActivity)
                mainHandler.post {
                    playDirectUrl(directUrl, null, 0L)
                }
            }
        }
    }

    override fun onDestroy() {
        isStatsPolling = false
        saveCurrentPlaybackState()
        super.onDestroy()
        mainHandler.removeCallbacksAndMessages(null)
        hideBuffering()
        player?.release()
        player = null
        JewEngineManager.wipeCache(this)
    }

    companion object {
        const val EXTRA_MEDIA_TYPE = "extra_media_type"
        const val EXTRA_MEDIA_ID = "extra_media_id"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_SEASON = "extra_season"
        const val EXTRA_EPISODE = "extra_episode"
        const val EXTRA_STREAM_URL = "extra_stream_url"
        const val EXTRA_POSTER = "extra_poster"
        const val EXTRA_BACKDROP = "extra_backdrop"

        @Volatile
        private var pendingStreamsId: String = ""
        @Volatile
        private var pendingStreams: List<StreamItem>? = null
        @Volatile
        private var pendingStreamIndex: Int = 0

        fun start(
            context: Context,
            type: String,
            id: String,
            title: String,
            season: Int = 1,
            episode: Int = 1,
            streamUrl: String? = null,
            poster: String = "",
            backdrop: String = "",
            preloadedStreams: List<StreamItem>? = null,
            selectedStreamIndex: Int = 0
        ) {
            if (preloadedStreams != null) {
                pendingStreamsId = "${type}_${id}_s${season}_e${episode}"
                pendingStreams = preloadedStreams
                pendingStreamIndex = selectedStreamIndex
            } else {
                pendingStreams = null
                pendingStreamsId = ""
            }
            val intent = Intent(context, PlayerActivity::class.java).apply {
                putExtra(EXTRA_MEDIA_TYPE, type)
                putExtra(EXTRA_MEDIA_ID, id)
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_SEASON, season)
                putExtra(EXTRA_EPISODE, episode)
                putExtra(EXTRA_STREAM_URL, streamUrl)
                putExtra(EXTRA_POSTER, poster)
                putExtra(EXTRA_BACKDROP, backdrop)
            }
            context.startActivity(intent)
        }
    }

    private data class MenuOption(
        val label: String,
        val isSelected: Boolean = false,
        val onClick: () -> Unit
    )

    private class MenuOptionAdapter(
        private val options: List<MenuOption>
    ) : RecyclerView.Adapter<MenuOptionAdapter.VH>() {

        class VH(val tv: TextView) : RecyclerView.ViewHolder(tv)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val tv = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_menu_option, parent, false) as TextView
            return VH(tv)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val opt = options[position]
            holder.tv.text = if (opt.isSelected) "✓  ${opt.label}" else opt.label
            holder.tv.setOnClickListener { opt.onClick() }
        }

        override fun getItemCount(): Int = options.size
    }
}
