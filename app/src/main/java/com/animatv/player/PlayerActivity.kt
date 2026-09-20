package com.animatv.player

import android.app.AlertDialog
import android.content.*
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.media.MediaDrm
import android.media.AudioManager
import android.provider.Settings
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.WindowManager
import androidx.core.view.GestureDetectorCompat
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.*
import android.widget.ImageButton
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.android.exoplayer2.*
import com.google.android.exoplayer2.source.DefaultMediaSourceFactory
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector.ParametersBuilder
import com.google.android.exoplayer2.trackselection.MappingTrackSelector.MappedTrackInfo
import com.google.android.exoplayer2.upstream.DefaultAllocator
import com.google.android.exoplayer2.upstream.DefaultDataSourceFactory
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource
import com.animatv.player.databinding.ActivityPlayerBinding
import com.animatv.player.databinding.CustomControlBinding
import com.animatv.player.dialog.TrackSelectionDialog
import com.animatv.player.adapter.MiniChannelAdapter
import com.animatv.player.extension.*
import com.animatv.player.extra.*
import com.animatv.player.extra.LocaleHelper
import com.animatv.player.model.Category
import com.animatv.player.model.Channel
import com.animatv.player.model.PlayData
import com.animatv.player.model.Playlist
import com.google.android.exoplayer2.util.MimeTypes
import java.net.URLDecoder
import java.util.*

class PlayerActivity : AppCompatActivity() {
    private var doubleBackToExitPressedOnce = false
    private val isTelevision by lazy { UiMode().isTelevision() }
    private val preferences by lazy { Preferences() }
    private val network by lazy { Network() }
    private var category: Category? = null
    private var current: Channel? = null
    private var player: com.google.android.exoplayer2.ExoPlayer? = null
    private lateinit var mediaItem: MediaItem
    private lateinit var trackSelector: DefaultTrackSelector
    private lateinit var bindingRoot: ActivityPlayerBinding
    private lateinit var bindingControl: CustomControlBinding
    private var handlerInfo: Handler? = null
    private var errorCounter = 0
    private var isLocked = false

    // ===== BAGIAN 2: PLAYER CANGGIH =====
    // Sleep Timer
    // Auto Quality
    private var autoQualityHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var lastBufferHealth = 100

    private var sleepTimerHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var sleepTimerRunnable: Runnable? = null
    private var sleepTimerSeconds = 0

    // Playback Speed
    private val speedLevels = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
    private var speedIndex = 2 // default 1.0x

    // Gesture Control
    private var gestureDetector: GestureDetectorCompat? = null
    private var gestureHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var initialBrightness = -1f
    private var initialVolume = 0
    private var gestureStartY = 0f
    private var gestureStartX = 0f
    private var isGestureBrightness = false
    private var isGestureVolume = false

    private var miniChannelAdapter: MiniChannelAdapter? = null
    private var isMiniPanelVisible = false

    // ── TV REMOTE SYSTEM ── (sistem terpisah, tidak ubah kode lain)
    private val tvHost by lazy {
        com.animatv.player.tv.TvPlayerHostImpl.create(
            activity             = this,
            isControllerVisible  = { bindingRoot.playerView.isControllerVisible },
            isMiniPanelVisible   = { isMiniPanelVisible },
            isLocked             = { isLocked },
            isLive               = { player?.isCurrentMediaItemLive == true },
            isPlaying            = { player?.isPlaying == true },
            reverseNav           = { preferences.reverseNavigation },
            showController       = { bindingRoot.playerView.showController() },
            hideController       = { bindingRoot.playerView.hideController() },
            switchChannel        = { mode -> switchChannel(mode) },
            toggleMiniPanel      = { toggleMiniChannelPanel() },
            openTrackSelector    = { showTrackSelector() },
            showBtnLockOverlay   = { bindingRoot.btnLockOverlay.visibility = View.VISIBLE },
            jumpToChannel        = { idx ->
                category?.channels?.getOrNull(idx)?.let { ch ->
                    current = ch
                    errorCounter = 0
                    player?.playWhenReady = false
                    player?.release()
                    playChannel()
                    updateMiniChannelActive()
                }
            },
            play                 = { player?.play() },
            pause                = { player?.pause() },
            seekBack             = { player?.seekBack() },
            seekForward          = { player?.seekForward() },
        )
    }
    private val tvRemote by lazy { com.animatv.player.tv.TvPlayerRemote(tvHost) }
    // ── END TV REMOTE SYSTEM ──

    private val broadcastReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent) {
            when(intent.getStringExtra(PLAYER_CALLBACK)) {
                RETRY_PLAYBACK -> retryPlayback(true)
                CLOSE_PLAYER -> finish()
            }
        }
    }

    companion object {
        var isFirst = true
        var isPipMode = false
        const val PLAYER_CALLBACK = "PLAYER_CALLBACK"
        const val RETRY_PLAYBACK = "RETRY_PLAYBACK"
        const val CLOSE_PLAYER = "CLOSE_PLAYER"
        private const val CHANNEL_NEXT = 0
        private const val CHANNEL_PREVIOUS = 1
        private const val CATEGORY_UP = 2
        private const val CATEGORY_DOWN = 3
    }

    override fun attachBaseContext(base: android.content.Context) {
        val lang = LocaleHelper.getLanguageCode(base)
        super.attachBaseContext(LocaleHelper.setLocale(base, lang))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        bindingRoot = ActivityPlayerBinding.inflate(layoutInflater)
        bindingControl = CustomControlBinding.bind(bindingRoot.root.findViewById(R.id.custom_control))
        setContentView(bindingRoot.root)

        // set this is not first time
        isFirst = false

        // verify playlist
        if (Playlist.cached.isCategoriesEmpty()) {
            Log.e("PLAYER", getString(R.string.player_no_playlist))
            Toast.makeText(this, R.string.player_no_playlist, Toast.LENGTH_SHORT).show()
            this.finish()
            return
        }

        // get categories & channel to play
        try {
            val parcel: PlayData? = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(PlayData.VALUE, PlayData::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(PlayData.VALUE)
            }
            category = parcel?.let { Playlist.cached.categories.getOrNull(it.catId) }
            current = parcel?.let { category?.channels?.getOrNull(it.chId) }
        }
        catch (e: Exception) {
            Log.e("PLAYER", getString(R.string.player_playdata_error))
            Toast.makeText(this, R.string.player_playdata_error, Toast.LENGTH_SHORT).show()
            this.finish()
            return
        }

        // verify
        if (category == null || current == null) {
            Log.e("PLAYER", getString(R.string.player_no_channel))
            Toast.makeText(this, R.string.player_no_channel, Toast.LENGTH_SHORT).show()
            this.finish()
            return
        }

        // set listener
        bindingListener()

        // play the channel
        playChannel()

        // local broadcast receiver to update playlist
        LocalBroadcastManager.getInstance(this)
            .registerReceiver(broadcastReceiver, IntentFilter(PLAYER_CALLBACK))
    }

    private fun bindingListener() {
        bindingRoot.playerView.apply {
            setOnTouchListener(object : OnSwipeTouchListener() {
                override fun onSwipeDown() { switchChannel(CATEGORY_UP) }
                override fun onSwipeUp() { switchChannel(CATEGORY_DOWN) }
                override fun onSwipeLeft() { switchChannel(CHANNEL_NEXT) }
                override fun onSwipeRight() { switchChannel(CHANNEL_PREVIOUS) }
            })
            setControllerVisibilityListener {
                setChannelInformation (it == View.VISIBLE)
                if (!isLocked) {
                    // Tidak locked, semua ikut show/hide normal
                    bindingRoot.btnMiniChannelToggle.visibility = it
                }
                // Tombol kunci di custom_control ikut show/hide saat tidak locked
                if (!isLocked) bindingControl.buttonLock.visibility = it
            }
        }
        bindingControl.trackSelection.setOnClickListener { showTrackSelector() }
        bindingControl.buttonExit.apply {
            visibility = if (isTelevision) View.GONE else View.VISIBLE
            setOnClickListener { finish() }
        }
        bindingControl.buttonPrevious.setOnClickListener { switchChannel(CHANNEL_PREVIOUS) }
        bindingControl.buttonRewind.setOnClickListener { player?.seekBack() }
        bindingControl.buttonForward.setOnClickListener { player?.seekForward() }
        bindingControl.buttonNext.setOnClickListener { switchChannel(CHANNEL_NEXT) }
        bindingControl.screenMode.setOnClickListener { showScreenMenu(it) }
        bindingControl.trackSelection.setOnClickListener { showTrackSelector() }

        // Tombol kunci di custom_control (saat tidak locked)
        bindingControl.buttonLock.apply {
            visibility = if (isTelevision) View.GONE else View.VISIBLE
            setOnClickListener {
                (it as ImageButton).setImageResource(R.drawable.ic_lock)
                lockControl(true)
            }
        }

        // Overlay tombol kunci TERPISAH - hanya muncul saat locked, tidak dikendalikan ExoPlayer
        val handlerLockOverlay = Handler(Looper.getMainLooper())
        bindingRoot.btnLockOverlay.setOnClickListener {
            (it as ImageButton).setImageResource(R.drawable.ic_lock_open)
            lockControl(false)
            bindingRoot.btnLockOverlay.visibility = View.GONE
        }

        // Setup mini channel panel toggle button
        bindingRoot.btnMiniChannelToggle.setOnClickListener {
            toggleMiniChannelPanel()
        }

        // ===== BAGIAN 2: PLAYER CANGGIH =====
        setupSleepTimer()
        setupPlaybackSpeed()
        setupDoubleTap()
        setupGestureControl()
        setupAutoQuality()
    }

    private fun setChannelInformation(visible: Boolean) {
        if (isLocked) return
        bindingRoot.layoutInfo.visibility =
            if (visible && !isPipMode) View.VISIBLE else View.INVISIBLE

        if (isPipMode) return
        if (visible == bindingRoot.playerView.isControllerVisible) return
        if (visible) bindingRoot.playerView.clearFocus()
        else return

        if (handlerInfo == null)
            handlerInfo = Handler(Looper.getMainLooper())

        handlerInfo?.removeCallbacksAndMessages(null)
        handlerInfo?.postDelayed({
                if (bindingRoot.playerView.isControllerVisible) return@postDelayed
                bindingRoot.layoutInfo.visibility = View.INVISIBLE
            },
            bindingRoot.playerView.controllerShowTimeoutMs.toLong()
        )
    }

    private fun lockControl(setLocked: Boolean) {
        isLocked = setLocked
        val visibility = if (setLocked) View.INVISIBLE else View.VISIBLE
        bindingRoot.layoutInfo.visibility = visibility
        bindingControl.buttonExit.visibility = visibility
        bindingControl.layoutControl.visibility = visibility
        bindingControl.screenMode.visibility = visibility
        bindingControl.trackSelection.visibility = visibility
        bindingControl.btnSpeed.visibility = visibility
        bindingControl.btnSleep.visibility = visibility
        bindingControl.buttonLock.visibility = if (setLocked) View.GONE else View.VISIBLE
        bindingRoot.btnMiniChannelToggle.visibility = if (setLocked) View.GONE else View.VISIBLE
        // Overlay kunci: sembunyikan dulu, tap layar yang akan munculkan
        bindingRoot.btnLockOverlay.visibility = View.GONE
        switchLiveOrVideo()
    }

    private fun switchLiveOrVideo() { switchLiveOrVideo(false) }
    private fun switchLiveOrVideo(reset: Boolean) {
        var visibility = when {
            reset -> View.GONE
            isLocked -> View.INVISIBLE
            player?.isCurrentMediaItemLive == true -> View.GONE
            else -> View.VISIBLE
        }
        bindingControl.layoutSeekbar.visibility = visibility
        bindingControl.spacerControl.visibility = visibility
        // override visibility if not seekable
        if (player?.isCurrentMediaItemSeekable == false) visibility = View.GONE
        bindingControl.buttonRewind.visibility = visibility
        bindingControl.buttonForward.visibility = visibility
    }

    private fun hexToBytes(hex: String): ByteArray {
        val len = hex.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(hex[i], 16) shl 4) + Character.digit(hex[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }

    private fun isDrmWidevineSupported(): Boolean {
        if (MediaDrm.isCryptoSchemeSupported(C.WIDEVINE_UUID)) return true
        AlertDialog.Builder(this).apply {
            setTitle(R.string.player_playback_error)
            setMessage(R.string.device_not_support_widevine)
            setCancelable(false)
            setPositiveButton(getString(R.string.btn_next_channel)) { _,_ -> switchChannel(CHANNEL_NEXT) }
            setNegativeButton(R.string.btn_close) { _,_ -> finish() }
            create()
            show()
        }
        return false
    }

    private fun playChannel() {
        // Release player lama jika masih ada untuk cegah memory leak di Android 5
        if (player != null) {
            try {
                player?.release()
            } catch (e: Exception) {
                Log.w("PLAYER", "release old player error: ${e.message}")
            }
            player = null
        }
        // reset view
        switchLiveOrVideo(true)

        // set category & channel name
        bindingRoot.categoryName.text = category?.name?.trim()
        bindingRoot.channelName.text = current?.name?.trim()

        // split streamurl with referer, user-agent
        var streamUrl = URLDecoder.decode(current?.streamUrl, "utf-8")
        var userAgent = streamUrl.findPattern(".*user-agent=(.+?)(\\|.*)?")
        val referer = streamUrl.findPattern(".*referer=(.+?)(\\|.*)?")

        // clean streamurl
        streamUrl = streamUrl.findPattern("(.+?)(\\|.*)?") ?: streamUrl

        // if null set User-Agent with existing resources
        if (userAgent == null) {
            val userAgents = listOf(*resources.getStringArray(R.array.user_agent))
            userAgent = userAgents.firstOrNull {
                current?.streamUrl?.contains(
                    it.substring(0, it.indexOf("/")).lowercase(Locale.getDefault())
                ) == true
            }
            if (userAgent.isNullOrEmpty()) {
                userAgent = userAgents[Random().nextInt(userAgents.size)]
            }
        }

        val drmLicense = Playlist.cached.drmLicenses.firstOrNull {
            current?.drmName?.equals(it.name) == true
        }?.url

        // Deteksi MimeType dari URL supaya ExoPlayer tahu format DASH vs HLS
        val mimeType = when {
            streamUrl.contains(".mpd", ignoreCase = true) -> MimeTypes.APPLICATION_MPD
            streamUrl.contains("/dash", ignoreCase = true) -> MimeTypes.APPLICATION_MPD
            streamUrl.contains(".m3u8", ignoreCase = true) -> MimeTypes.APPLICATION_M3U8
            streamUrl.contains("playlist.m3u8", ignoreCase = true) -> MimeTypes.APPLICATION_M3U8
            streamUrl.contains("master.m3u8", ignoreCase = true) -> MimeTypes.APPLICATION_M3U8
            streamUrl.contains("index.m3u8", ignoreCase = true) -> MimeTypes.APPLICATION_M3U8
            else -> null
        }

        // HTTP factory dengan User-Agent dan Referer
        // Timeout lebih panjang untuk Android 5 dengan koneksi lambat
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setUserAgent(userAgent)
            .setConnectTimeoutMs(15_000)   // 15 detik connect timeout
            .setReadTimeoutMs(20_000)      // 20 detik read timeout
        if (referer != null) httpDataSourceFactory.setDefaultRequestProperties(mapOf(Pair("referer", referer)))
        val dataSourceFactory = DefaultDataSourceFactory(this, httpDataSourceFactory)

        // Build DrmSessionManager dan MediaItem sesuai tipe DRM
        val isClearKey = current?.drmName?.startsWith("clearkey_") == true
        val isWidevine = current?.drmName?.startsWith("widevine_") == true
        val hasDrm = !current?.drmName.isNullOrBlank() && !drmLicense.isNullOrBlank()

        var drmSessionManager: com.google.android.exoplayer2.drm.DrmSessionManager =
            com.google.android.exoplayer2.drm.DrmSessionManager.DRM_UNSUPPORTED

        if (hasDrm && isClearKey && !drmLicense.isNullOrBlank()) {
            // ClearKey: build JSON response langsung, pakai LocalMediaDrmCallback
            // Ini cara yang benar - tidak butuh network request untuk license
            try {
                fun hexToBytes(hex: String): ByteArray {
                    val len = hex.length
                    val data = ByteArray(len / 2)
                    for (i in 0 until len / 2)
                        data[i] = ((Character.digit(hex[i * 2], 16) shl 4) + Character.digit(hex[i * 2 + 1], 16)).toByte()
                    return data
                }
                val pairs = drmLicense.split(",")
                val keysJson = StringBuilder("{\"keys\":[")
                pairs.forEachIndexed { i, pair ->
                    val kv = pair.trim().split(":")
                    if (kv.size == 2) {
                        val kidB64 = android.util.Base64.encodeToString(
                            hexToBytes(kv[0].trim()),
                            android.util.Base64.NO_PADDING or android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP)
                        val keyB64 = android.util.Base64.encodeToString(
                            hexToBytes(kv[1].trim()),
                            android.util.Base64.NO_PADDING or android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP)
                        if (i > 0) keysJson.append(",")
                        keysJson.append("{\"kty\":\"oct\",\"kid\":\"$kidB64\",\"k\":\"$keyB64\"}")
                    }
                }
                keysJson.append("],\"type\":\"temporary\"}")
                val licenseBytes = keysJson.toString().toByteArray(Charsets.UTF_8)
                // LocalMediaDrmCallback: langsung inject license JSON tanpa network
                val drmCallback = com.google.android.exoplayer2.drm.LocalMediaDrmCallback(licenseBytes)
                drmSessionManager = com.google.android.exoplayer2.drm.DefaultDrmSessionManager.Builder()
                    .setUuidAndExoMediaDrmProvider(C.CLEARKEY_UUID, com.google.android.exoplayer2.drm.FrameworkMediaDrm.DEFAULT_PROVIDER)
                    .setMultiSession(false)
                    .build(drmCallback)
                Log.d("DRM_DEBUG", "ClearKey DrmSessionManager built OK")
            } catch (e: Exception) {
                Log.e("DRM_DEBUG", "ClearKey build error: ${e.message}")
            }
        } else if (hasDrm && isWidevine) {
            if (!isDrmWidevineSupported()) return
            // Widevine: pakai HttpMediaDrmCallback dengan license server URL
            val drmCallback = com.google.android.exoplayer2.drm.HttpMediaDrmCallback(
                drmLicense, httpDataSourceFactory)
            drmSessionManager = com.google.android.exoplayer2.drm.DefaultDrmSessionManager.Builder()
                .setUuidAndExoMediaDrmProvider(C.WIDEVINE_UUID, com.google.android.exoplayer2.drm.FrameworkMediaDrm.DEFAULT_PROVIDER)
                .setMultiSession(true)
                .build(drmCallback)
            Log.d("DRM_DEBUG", "Widevine DrmSessionManager built, licUrl=$drmLicense")
        } else if (!current?.drmName.isNullOrBlank() && drmLicense == null) {
            Log.e("DRM_DEBUG", "DRM channel but license NOT FOUND!")
            Toast.makeText(applicationContext, "DRM license tidak ditemukan, coba refresh playlist", Toast.LENGTH_LONG).show()
        }

        // MediaItem - cukup set URI dan MimeType, DRM dihandle lewat DrmSessionManager
        mediaItem = MediaItem.Builder()
            .setUri(Uri.parse(streamUrl))
            .also { if (mimeType != null) it.setMimeType(mimeType) }
            .build()

        // MediaSourceFactory dengan DrmSessionManager yang sudah dikonfigurasi
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)
            .setDrmSessionManagerProvider { drmSessionManager }

        // create trackselector with resolution constraint from sidebar buttons
        trackSelector = DefaultTrackSelector(this).apply {
            val maxHeights = listOf(360, 720, 1080, 2160, Int.MAX_VALUE)
            val maxBitrates = listOf(800_000, 2_500_000, 5_000_000, 20_000_000, Int.MAX_VALUE)
            val idx = preferences.resolutionIndex.coerceIn(0, maxHeights.size - 1)
            parameters = ParametersBuilder(applicationContext)
                .setMaxVideoSize(Int.MAX_VALUE, maxHeights[idx])
                .setMaxVideoBitrate(maxBitrates[idx])
                .build()
        }

        // LoadControl yang stabil untuk Android 5 dengan RAM terbatas
        // min=2s, max=10s, playback_resume=1s, rebuffer=2s
        // Buffer dioptimalkan untuk Android 5 TV Box RAM terbatas
        val loadControl: LoadControl = DefaultLoadControl.Builder()
            .setAllocator(DefaultAllocator(true, 16))
            .setBufferDurationsMs(
                3_000,   // minBufferMs
                15_000,  // maxBufferMs - 15 detik cukup untuk live stream
                1_500,   // bufferForPlaybackMs
                3_000    // bufferForPlaybackAfterRebufferMs
            )
            .setTargetBufferBytes(4 * 1024 * 1024) // 4MB buffer
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        // enable extension renderer
        // EXTENSION_RENDERER_MODE_OFF hemat RAM di Android 5 TV Box
        val renderersFactory = DefaultRenderersFactory(this)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)

        // set player builder - selalu pakai loadControl yang stabil
        val playerBuilder = com.google.android.exoplayer2.ExoPlayer.Builder(this, renderersFactory)
            .setMediaSourceFactory(mediaSourceFactory)
            .setTrackSelector(trackSelector)
            .setLoadControl(loadControl)

        // create player & set listener
        try {
            player = playerBuilder.build()
            player?.addListener(PlayerListener())

            // set player view
            bindingRoot.playerView.player = player
            bindingRoot.playerView.resizeMode = preferences.resizeMode
            bindingRoot.playerView.requestFocus()

            // play
            player?.playWhenReady = true
            player?.setMediaItem(mediaItem)
            player?.prepare()
        } catch (e: Exception) {
            android.util.Log.e("PLAYER", "Build/play error: ${e.message}", e)
            // Retry setelah 2 detik kalau player gagal build
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                if (!isDestroyed) retryPlayback(true)
            }, 2000)
        }
    }

    private fun switchChannel(mode: Int): Boolean {
        if (isLocked) return true
        switchChannel(mode, false)
        bindingRoot.playerView.hideController()
        return true
    }

    private fun switchChannel(mode: Int, lastCh: Boolean) {
        val catId = Playlist.cached.categories.indexOf(category)
        val chId = category?.channels?.indexOf(current) ?: -1
        when(mode) {
            CATEGORY_UP -> {
                val previous = catId - 1
                if (previous > -1) {
                    category = Playlist.cached.categories[previous]
                    current = if (lastCh) category?.channels?.get(category?.channels?.size?.minus(1) ?: 0)
                    else category?.channels?.get(0)
                }
                else {
                    Toast.makeText(this, R.string.top_category, Toast.LENGTH_SHORT).show()
                    return
                }
            }
            CATEGORY_DOWN -> {
                val next = catId + 1
                if (next < Playlist.cached.categories.size) {
                    category = Playlist.cached.categories[next]
                    current = category?.channels?.get(0)
                }
                else {
                    Toast.makeText(this, R.string.bottom_category, Toast.LENGTH_SHORT).show()
                    return
                }
            }
            CHANNEL_PREVIOUS -> {
                val previous = chId - 1
                if (previous > -1) {
                    current = category?.channels?.get(previous)
                }
                else {
                    switchChannel(CATEGORY_UP, true)
                    return
                }
            }
            CHANNEL_NEXT -> {
                val next = chId + 1
                if (next < category?.channels?.size ?: 0) {
                    current = category?.channels?.get(next)
                }
                else {
                    switchChannel(CATEGORY_DOWN)
                    return
                }
            }
        }

        // reset player & play
        errorCounter = 0
        try {
            player?.playWhenReady = false
            player?.stop()
            player?.clearMediaItems()
        } catch (e: Exception) {
            Log.w("PLAYER", "switchChannel stop error: ${e.message}")
        }
        playChannel()
    }

    private fun retryPlayback(force: Boolean) {
        if (force) {
            if (player == null) {
                // Player sudah di-release, perlu build ulang
                playChannel()
                return
            }
            try {
                player?.playWhenReady = true
                player?.setMediaItem(mediaItem)
                player?.prepare()
            } catch (e: Exception) {
                Log.e("PLAYER", "retryPlayback error: ${e.message}")
                // Fallback: rebuild player
                player?.release()
                player = null
                playChannel()
            }
            return
        }

        AsyncSleep().task(object : AsyncSleep.Task {
            override fun onFinish() {
                retryPlayback(true)
            }
        }).start(2) // delay 2 detik sebelum retry
    }

    private inner class PlayerListener : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            val trackHaveContent = TrackSelectionDialog.willHaveContent(trackSelector)
            bindingControl.trackSelection.visibility =
                if (trackHaveContent) View.VISIBLE else View.GONE
            when (state) {
                Player.STATE_READY -> {
                    errorCounter = 0
                    val catId = Playlist.cached.categories.indexOf(category)
                    val chId = category?.channels?.indexOf(current) ?: -1
                    preferences.watched = PlayData(catId, chId)
                    switchLiveOrVideo()
                    // Update highlight channel aktif di mini panel
                    updateMiniChannelActive()
                    // Cek track support
                    val mappedTrackInfo = trackSelector.currentMappedTrackInfo
                    if (mappedTrackInfo != null) {
                        val isVideoProblem = mappedTrackInfo.getTypeSupport(C.TRACK_TYPE_VIDEO) == MappedTrackInfo.RENDERER_SUPPORT_UNSUPPORTED_TRACKS
                        val isAudioProblem = mappedTrackInfo.getTypeSupport(C.TRACK_TYPE_AUDIO) == MappedTrackInfo.RENDERER_SUPPORT_UNSUPPORTED_TRACKS
                        if (isVideoProblem || isAudioProblem) {
                            val problem = when {
                                isVideoProblem && isAudioProblem -> "video & audio"
                                isVideoProblem -> "video"
                                else -> "audio"
                            }
                            val msg = String.format(getString(R.string.error_unsupported), problem)
                            if (isVideoProblem) showMessage(msg, false)
                            else Toast.makeText(applicationContext, msg, Toast.LENGTH_LONG).show()
                        }
                    }
                }
                Player.STATE_ENDED -> {
                    // Live stream TIDAK boleh auto-retry saat ENDED
                    // karena bisa menyebabkan keluar dari player di Android 5
                    val isLive = player?.isCurrentMediaItemLive == true
                    if (!isLive) retryPlayback(true)
                    // Kalau live, abaikan - stream mungkin sedang rebuffering
                }
                else -> { }
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            super.onIsPlayingChanged(isPlaying)
            if (isPlaying) setChannelInformation(true)
        }

        override fun onPlayerError(error: PlaybackException) {
            val errorMsg = error.message ?: "Unknown error"
            Log.e("PLAYER_ERROR", "code=${error.errorCode} name=${error.errorCodeName} msg=$errorMsg")

            // BEHIND_LIVE_WINDOW: seek ke live position - sering di Android 5
            if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                player?.seekToDefaultPosition()
                player?.prepare()
                return
            }

            // IO Error saat live stream = jaringan putus sebentar, retry otomatis
            val isIoError = error.errorCode >= PlaybackException.ERROR_CODE_IO_UNSPECIFIED &&
                            error.errorCode <= PlaybackException.ERROR_CODE_IO_NO_PERMISSION
            val isLive = player?.isCurrentMediaItemLive ?: false

            // Untuk live stream: retry lebih banyak, lebih sabar
            val maxRetry = if (isLive) 15 else 8

            if (errorCounter < maxRetry && network.isConnected()) {
                errorCounter++
                val delaySeconds = when {
                    errorCounter <= 3 -> 2
                    errorCounter <= 8 -> 4
                    else -> 6
                }
                AsyncSleep().task(object : AsyncSleep.Task {
                    override fun onFinish() { retryPlayback(true) }
                }).start(delaySeconds)
            } else {
                showMessage(
                    String.format(getString(R.string.player_error_message),
                        error.errorCode, error.errorCodeName, errorMsg), true
                )
            }
        }

        // onTracksChanged dihapus - handled via onPlaybackStateChanged
        // untuk kompatibilitas ExoPlayer 2.18.x
    }


    // Notifikasi ringan pakai Toast (bukan error dialog)
    private fun showInfo(message: String) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun showMessage(message: String, autoretry: Boolean) {
        val waitInSecond = 30
        val btnRetryText = if (autoretry) String.format(getString(R.string.btn_retry_count), waitInSecond) else getString(R.string.btn_retry)
        val builder = AlertDialog.Builder(this).apply {
            setTitle(R.string.player_playback_error)
            setMessage(message)
            setCancelable(false)
            setNegativeButton(getString(R.string.btn_next_channel)) { di,_ ->
                switchChannel(CHANNEL_NEXT)
                di.dismiss()
            }
            setPositiveButton(btnRetryText) { di,_ ->
                retryPlayback(true)
                di.dismiss()
            }
            setNeutralButton(R.string.btn_close) { di,_ ->
                di.dismiss()
                finish()
            }
            create()
        }
        val dialog = builder.show()

        if (!autoretry) return
        AsyncSleep().task(object : AsyncSleep.Task{
            override fun onCountDown(count: Int) {
                val text = if (count <= 0) getString(R.string.btn_retry)
                else String.format(getString(R.string.btn_retry_count), count)
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).text = text
            }

            override fun onFinish() {
                dialog.dismiss()
                retryPlayback(true)
            }
        }).start(waitInSecond)
    }

    private fun showTrackSelector(): Boolean {
        TrackSelectionDialog.createForTrackSelector(trackSelector) { }
            .show(supportFragmentManager, "TrackSelection")
        return true
    }



    // ============================================================
    // BAGIAN 2: PLAYER CANGGIH
    // ============================================================

    // ===== 1. SLEEP TIMER =====
    private fun setupSleepTimer() {
        bindingControl.btnSleep?.setOnClickListener { showSleepTimerMenu() }
    }

    private fun showSleepTimerMenu() {
        val options = arrayOf(
            "Matikan Timer",
            "Tidur 15 menit",
            "Tidur 30 menit",
            "Tidur 45 menit",
            "Tidur 1 jam",
            "Tidur 2 jam"
        )
        val minutes = listOf(0, 15, 30, 45, 60, 120)
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Sleep Timer")
            .setItems(options) { _, idx ->
                setSleepTimer(minutes[idx])
            }
            .show()
    }

    private fun setSleepTimer(minutes: Int) {
        // Cancel timer lama
        sleepTimerRunnable?.let { sleepTimerHandler.removeCallbacks(it) }
        sleepTimerRunnable = null

        if (minutes == 0) {
            bindingControl.txtSleepTimer?.visibility = android.view.View.GONE
            return
        }

        sleepTimerSeconds = minutes * 60
        bindingControl.txtSleepTimer?.visibility = android.view.View.VISIBLE
        updateSleepTimerDisplay()

        sleepTimerRunnable = object : Runnable {
            override fun run() {
                sleepTimerSeconds--
                if (sleepTimerSeconds <= 0) {
                    // Waktunya habis - tutup player
                    player?.pause()
                    finish()
                } else {
                    updateSleepTimerDisplay()
                    sleepTimerHandler.postDelayed(this, 1000)
                }
            }
        }
        sleepTimerHandler.postDelayed(sleepTimerRunnable ?: return, 1000)
    }

    private fun updateSleepTimerDisplay() {
        val mins = sleepTimerSeconds / 60
        val secs = sleepTimerSeconds % 60
        bindingControl.txtSleepTimer?.text = "ZZZ %02d:%02d".format(mins, secs)
    }

    // ===== 2. PLAYBACK SPEED =====
    private fun setupPlaybackSpeed() {
        bindingControl.btnSpeed?.setOnClickListener {
            speedIndex = (speedIndex + 1) % speedLevels.size
            val speed = speedLevels[speedIndex]
            player?.setPlaybackSpeed(speed)
            val label = if (speed == 1.0f) "1x" else "${speed}x"
            bindingControl.btnSpeed?.text = label
            showInfo("Kecepatan: ${label}")
        }
    }

    // ===== 3. DOUBLE TAP REWIND/FORWARD =====
    private fun setupDoubleTap() {
        val screenWidth = resources.displayMetrics.widthPixels

        val handlerLockBtn = Handler(Looper.getMainLooper())
        bindingRoot.playerView.setOnTouchListener { _, event ->
            if (isLocked) {
                if (event.action == MotionEvent.ACTION_UP) {
                    // Munculkan overlay tombol kunci
                    bindingRoot.btnLockOverlay.setImageResource(R.drawable.ic_lock)
                    bindingRoot.btnLockOverlay.visibility = View.VISIBLE
                    // Auto hide setelah 3 detik
                    handlerLockBtn.removeCallbacksAndMessages(null)
                    handlerLockBtn.postDelayed({
                        bindingRoot.btnLockOverlay.visibility = View.GONE
                    }, 3000)
                }
                return@setOnTouchListener true
            }
            gestureDetector?.onTouchEvent(event)
            handleGestureEvent(event, screenWidth)
            false
        }

        gestureDetector = GestureDetectorCompat(this,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDoubleTap(e: MotionEvent): Boolean {
                    val screenWidth = resources.displayMetrics.widthPixels
                    if (e.x < screenWidth / 2) {
                        // Double tap kiri = rewind 10 detik
                        player?.seekBack()
                        showDoubleTapFeedback(false)
                    } else {
                        // Double tap kanan = forward 10 detik
                        player?.seekForward()
                        showDoubleTapFeedback(true)
                    }
                    return true
                }
            })
    }

    private fun showDoubleTapFeedback(isForward: Boolean) {
        val view = if (isForward) bindingControl.txtDoubleTapRight
                   else bindingControl.txtDoubleTapLeft
        view?.visibility = android.view.View.VISIBLE
        view?.animate()?.alpha(1f)?.setDuration(100)?.withEndAction {
            view.animate()?.alpha(0f)?.setDuration(400)?.withEndAction {
                view.visibility = android.view.View.GONE
                view.alpha = 1f
            }?.start()
        }?.start()
    }

    // ===== 4. GESTURE CONTROL (Brightness & Volume) =====
    private fun setupGestureControl() {
        // Init brightness awal
        try {
            initialBrightness = Settings.System.getInt(
                contentResolver,
                Settings.System.SCREEN_BRIGHTNESS
            ) / 255f
        } catch (e: Exception) {
            initialBrightness = 0.5f
        }
        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        initialVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
    }

    private fun handleGestureEvent(event: MotionEvent, screenWidth: Int) {
        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                gestureStartY = event.y
                gestureStartX = event.x
                isGestureBrightness = false
                isGestureVolume = false
                initialVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                try {
                    initialBrightness = Settings.System.getInt(
                        contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f
                } catch (e: Exception) { initialBrightness = 0.5f }
            }

            MotionEvent.ACTION_MOVE -> {
                val deltaX = event.x - gestureStartX
                val deltaY = gestureStartY - event.y
                val absDX = Math.abs(deltaX)
                val absDY = Math.abs(deltaY)

                // Tentukan mode gesture dari arah dominan pertama
                if (!isGestureBrightness && !isGestureVolume) {
                    if (absDX > 30 || absDY > 30) {
                        // Swipe atas/bawah = brightness, swipe kiri/kanan = volume
                        isGestureBrightness = absDY > absDX
                        isGestureVolume = absDX >= absDY
                    }
                }

                if (isGestureBrightness && absDY > 20) {
                    // Swipe atas/bawah = brightness
                    val sensitivity = resources.displayMetrics.heightPixels / 2f
                    val delta = deltaY / sensitivity
                    val newBrightness = (initialBrightness + delta).coerceIn(0.01f, 1f)
                    val lp = window.attributes
                    lp.screenBrightness = newBrightness
                    window.attributes = lp
                    val percent = (newBrightness * 100).toInt()
                    bindingControl.layoutBrightness?.visibility = android.view.View.VISIBLE
                    bindingControl.txtBrightnessValue?.text = "$percent%"
                    gestureHandler.removeCallbacksAndMessages(null)
                    gestureHandler.postDelayed({
                        bindingControl.layoutBrightness?.visibility = android.view.View.GONE
                    }, 1500)
                }

                if (isGestureVolume && absDX > 20) {
                    // Swipe kiri/kanan = volume
                    val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    val sensitivity = resources.displayMetrics.widthPixels / 2f
                    val delta = deltaX / sensitivity
                    val newVol = (initialVolume + (delta * maxVol.toFloat())).toInt().coerceIn(0, maxVol)
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVol, 0)
                    val percent = (newVol * 100 / maxVol)
                    bindingControl.layoutVolume?.visibility = android.view.View.VISIBLE
                    bindingControl.txtVolumeValue?.text = "$percent%"
                    gestureHandler.removeCallbacksAndMessages(null)
                    gestureHandler.postDelayed({
                        bindingControl.layoutVolume?.visibility = android.view.View.GONE
                    }, 1500)
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isGestureBrightness = false
                isGestureVolume = false
            }
        }
    }


    // ===== AUTO QUALITY =====
    private fun setupAutoQuality() {
        // Auto quality DINONAKTIFKAN untuk live streaming
        // bufferedPercentage selalu 0 untuk live stream -> salah deteksi
        // Biarkan ExoPlayer pilih kualitas otomatis (ABR built-in)
        autoQualityHandler.postDelayed(object : Runnable {
            override fun run() {
                val isLive = player?.isCurrentMediaItemLive ?: false
                if (!isLive) checkAndAdjustQuality()
                autoQualityHandler.postDelayed(this, 15000) // cek tiap 15 detik
            }
        }, 15000)
    }

    private fun checkAndAdjustQuality() {
        // Hanya untuk VOD (bukan live stream)
        val p = player ?: return
        val buffered = p.bufferedPercentage
        if (buffered < 5 && p.isPlaying) {
            val params = trackSelector.parameters.buildUpon()
            val currentMaxHeight = trackSelector.parameters.maxVideoHeight
            if (currentMaxHeight == Int.MAX_VALUE || currentMaxHeight > 720) {
                params.setMaxVideoSize(1280, 720)
                trackSelector.setParameters(params)
            }
        }
    }

    // ===== MINI CHANNEL PANEL =====

    private fun setupMiniChannelPanel() {
        val channels = category?.channels ?: return
        val currentIdx = channels.indexOf(current).coerceAtLeast(0)

        miniChannelAdapter = MiniChannelAdapter(channels) { idx ->
            // Klik channel di mini list -> langsung putar
            if (idx != channels.indexOf(current)) {
                current = channels[idx]
                errorCounter = 0
                player?.playWhenReady = false
                player?.release()
                playChannel()
            }
        }
        miniChannelAdapter?.setActiveChannel(currentIdx)

        bindingRoot.rvMiniChannels.apply {
            layoutManager = androidx.recyclerview.widget.LinearLayoutManager(
                this@PlayerActivity
            )
            adapter = miniChannelAdapter
            isFocusable = true
            isFocusableInTouchMode = false
            descendantFocusability = android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS
            itemAnimator = null
            // Scroll ke channel aktif
            post {
                (layoutManager as? androidx.recyclerview.widget.LinearLayoutManager)
                    ?.scrollToPositionWithOffset(currentIdx, 100)
            }
        }
    }

    private fun toggleMiniChannelPanel() {
        if (isLocked) return
        isMiniPanelVisible = !isMiniPanelVisible

        if (isMiniPanelVisible) {
            setupMiniChannelPanel()
            bindingRoot.miniChannelPanel.visibility = View.VISIBLE
            // Rotate toggle icon
            bindingRoot.btnMiniChannelToggle.animate()
                .rotation(180f).setDuration(200).start()
            // TV: otomatis pindah fokus ke mini channel list saat panel dibuka
            if (UiMode().isTelevision()) {
                bindingRoot.rvMiniChannels.post {
                    bindingRoot.rvMiniChannels.requestFocus()
                    bindingRoot.rvMiniChannels.getChildAt(
                        miniChannelAdapter?.getActiveIndex() ?: 0
                    )?.requestFocus()
                }
            }
        } else {
            bindingRoot.miniChannelPanel.visibility = View.GONE
            bindingRoot.btnMiniChannelToggle.animate()
                .rotation(0f).setDuration(200).start()
            // TV: kembalikan fokus ke player view
            if (UiMode().isTelevision()) {
                bindingRoot.playerView.requestFocus()
            }
        }
    }

    private fun updateMiniChannelActive() {
        val channels = category?.channels ?: return
        val idx = channels.indexOf(current).coerceAtLeast(0)
        miniChannelAdapter?.setActiveChannel(idx)
        // Scroll ke channel aktif
        if (isMiniPanelVisible) {
            (bindingRoot.rvMiniChannels.layoutManager
                as? androidx.recyclerview.widget.LinearLayoutManager)
                ?.scrollToPositionWithOffset(idx, 100)
        }
    }

    private fun showScreenMenu(view: View) {
        val timeout = bindingRoot.playerView.controllerShowTimeoutMs
        bindingRoot.playerView.controllerShowTimeoutMs = 0
        PopupMenu(this, view).apply {
            inflate(R.menu.screen_resize_mode)
            setOnMenuItemClickListener { m: MenuItem ->
                val mode = when(m.itemId) {
                    R.id.mode_fixed_width -> 1
                    R.id.mode_fixed_height -> 2
                    R.id.mode_fill -> 3
                    R.id.mode_zoom -> 4
                    else -> 0
                }
                if (bindingRoot.playerView.resizeMode != mode) {
                    bindingRoot.playerView.resizeMode = mode
                    preferences.resizeMode = mode
                }
                true
            }
            setOnDismissListener {
                bindingRoot.playerView.controllerShowTimeoutMs = timeout
            }
            show()
        }
    }

    override fun onResume() {
        super.onResume()
        player?.playWhenReady = true
    }

    override fun onPause() {
        super.onPause()
        player?.playWhenReady = false
    }

    @Suppress("DEPRECATION")
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (player?.isPlaying == false) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val params = android.app.PictureInPictureParams.Builder().build()
                enterPictureInPictureMode(params)
            }
            else {
                enterPictureInPictureMode()
            }
        }
    }

    override fun onPictureInPictureModeChanged(pip: Boolean, config: Configuration) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            super.onPictureInPictureModeChanged(pip, config)
        }
        isPipMode = pip
        setChannelInformation(!pip)
        bindingRoot.playerView.useController = !pip
        player?.playWhenReady = true
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) window.setFullScreenFlags()
    }

    // ── TV REMOTE: intercept sebelum view hierarchy ────────────────
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (tvRemote.dispatchKeyEvent(event)) return true
        return super.dispatchKeyEvent(event)
    }
    // ── END TV REMOTE ──────────────────────────────────────────────

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        // ── TV REMOTE: tangani dulu, baru lanjut kode lama ─────────
        if (tvRemote.onKeyUp(keyCode, event)) return true
        // ── END TV REMOTE ──────────────────────────────────────────
        // [1] Tutup mini channel panel dulu dengan BACK atau ESCAPE
        if (isMiniPanelVisible &&
            (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE)) {
            toggleMiniChannelPanel()
            return true
        }

        // [2] DPAD_CENTER / ENTER saat controller tidak tampil → tampilkan controller
        //     DPAD_CENTER / ENTER saat controller tampil → toggle play/pause
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
            keyCode == KeyEvent.KEYCODE_ENTER ||
            keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            if (!bindingRoot.playerView.isControllerVisible) {
                bindingRoot.playerView.showController()
            } else {
                if (player?.isPlaying == false) player?.play() else player?.pause()
            }
            return true
        }

        // [3] Jika terkunci, hanya izinkan tombol di atas
        if (isLocked) return true

        // [4] Tombol media & menu – selalu ditangani
        when (keyCode) {
            KeyEvent.KEYCODE_MENU,
            KeyEvent.KEYCODE_SETTINGS -> return showTrackSelector()
            KeyEvent.KEYCODE_PAGE_UP   -> return switchChannel(CATEGORY_UP)
            KeyEvent.KEYCODE_PAGE_DOWN -> return switchChannel(CATEGORY_DOWN)
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> return switchChannel(CHANNEL_PREVIOUS)
            KeyEvent.KEYCODE_MEDIA_NEXT     -> return switchChannel(CHANNEL_NEXT)
            KeyEvent.KEYCODE_MEDIA_PLAY -> { player?.play(); return true }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> { player?.pause(); return true }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                if (player?.isPlaying == false) player?.play() else player?.pause()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_STOP -> { player?.pause(); return true }
        }

        // [5] Seek untuk konten non-live
        if (player?.isCurrentMediaItemLive == false) {
            when (keyCode) {
                KeyEvent.KEYCODE_MEDIA_REWIND,
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                    // Pastikan controller terlihat agar user tahu progress
                    if (!bindingRoot.playerView.isControllerVisible)
                        bindingRoot.playerView.showController()
                    if (keyCode == KeyEvent.KEYCODE_MEDIA_REWIND) player?.seekBack()
                    else player?.seekForward()
                    return true
                }
            }
        }

        // [6] Saat controller ExoPlayer tampil → DPAD sepenuhnya untuk navigasi
        //     tombol-tombol controller. TIDAK ada ganti channel via DPAD.
        //     Untuk ganti channel gunakan mini channel panel (tombol OK/ENTER saat controller sembunyi).
        if (bindingRoot.playerView.isControllerVisible) {
            return super.onKeyUp(keyCode, event)
        }

        // [7] Controller tersembunyi → DPAD ganti channel/kategori langsung
        if (!preferences.reverseNavigation) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP    -> return switchChannel(CHANNEL_PREVIOUS)
                KeyEvent.KEYCODE_DPAD_DOWN  -> return switchChannel(CHANNEL_NEXT)
                KeyEvent.KEYCODE_DPAD_LEFT  -> return switchChannel(CATEGORY_UP)
                KeyEvent.KEYCODE_DPAD_RIGHT -> return switchChannel(CATEGORY_DOWN)
            }
        } else {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP    -> return switchChannel(CHANNEL_NEXT)
                KeyEvent.KEYCODE_DPAD_DOWN  -> return switchChannel(CHANNEL_PREVIOUS)
                KeyEvent.KEYCODE_DPAD_LEFT  -> return switchChannel(CATEGORY_DOWN)
                KeyEvent.KEYCODE_DPAD_RIGHT -> return switchChannel(CATEGORY_UP)
            }
        }

        return super.onKeyUp(keyCode, event)
    }

    override fun onBackPressed() {
        // [1] Tutup mini panel dulu
        if (isMiniPanelVisible) {
            toggleMiniChannelPanel()
            return
        }
        // [2] Jika terkunci, tidak bisa keluar
        if (isLocked) return
        // [3] Jika controller tampil, sembunyikan dulu (bukan keluar)
        if (bindingRoot.playerView.isControllerVisible) {
            bindingRoot.playerView.hideController()
            return
        }
        // [4] TV box: langsung keluar (double back tidak praktis dengan remote)
        if (isTelevision || doubleBackToExitPressedOnce) {
            super.onBackPressed()
            finish(); return
        }
        doubleBackToExitPressedOnce = true
        Toast.makeText(this, getString(R.string.press_back_twice_exit_player), Toast.LENGTH_SHORT).show()
        Handler(Looper.getMainLooper()).postDelayed({ doubleBackToExitPressedOnce = false }, 2000)
    }

    override fun onDestroy() {
        // Cleanup Player Canggih
        sleepTimerRunnable?.let { sleepTimerHandler.removeCallbacks(it) }
        gestureHandler.removeCallbacksAndMessages(null)
        autoQualityHandler.removeCallbacksAndMessages(null)
        player?.release()
        LocalBroadcastManager.getInstance(this)
            .unregisterReceiver(broadcastReceiver)
        tvRemote.onDestroy() // ── TV REMOTE cleanup ──
        super.onDestroy()
    }
}
