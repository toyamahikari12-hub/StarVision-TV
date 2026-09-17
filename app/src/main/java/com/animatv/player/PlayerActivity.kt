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
import com.google.android.exoplayer2.upstream.HttpDataSource
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
import java.net.URLDecoder
import java.util.Locale
import java.util.*
import java.io.File
import java.text.SimpleDateFormat

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

    // TARUH KODE PARSER DI SINI
    private data class ParsedStreamRequest(
        val url: String,
        val userAgent: String?,
        val referer: String?,
        val origin: String?
    )

    private fun cleanHeaderValue(value: String?): String? {
        if (value.isNullOrBlank()) return null

        var v = value.trim()

        val prefixes = listOf(
            "http-user-agent=",
            "user-agent=",
            "ua=",
            "http-referer=",
            "http-referrer=",
            "referer=",
            "referrer=",
            "http-origin=",
            "origin="
        )

        for (prefix in prefixes) {
            if (v.startsWith(prefix, ignoreCase = true)) {
                v = v.substring(prefix.length).trim()
                break
            }
        }

        return v.takeIf { it.isNotBlank() }
    }

    private fun parseStreamRequest(raw: String?): ParsedStreamRequest {
        val decoded = try {
            URLDecoder.decode(raw.orEmpty(), "UTF-8")
        } catch (_: Exception) {
            raw.orEmpty()
        }

        val parts = decoded.split("|")
        val url = parts.firstOrNull()?.trim().orEmpty()

        var userAgent: String? = null
        var referer: String? = null
        var origin: String? = null

        for (rawPart in parts.drop(1)) {
            val part = rawPart.trim()
            val separator = part.indexOf('=')

            if (separator <= 0) continue

            val key = part.substring(0, separator)
                .trim()
                .lowercase(Locale.US)

            val value = part.substring(separator + 1).trim()

            when (key) {
                "user-agent",
                "http-user-agent",
                "ua" -> {
                    userAgent = cleanHeaderValue(value)
                }

                "referer",
                "http-referer",
                "referrer",
                "http-referrer" -> {
                    referer = cleanHeaderValue(value)
                }

                "origin",
                "http-origin" -> {
                    origin = cleanHeaderValue(value)
                }
            }
        }

        return ParsedStreamRequest(
            url = url,
            userAgent = userAgent,
            referer = referer,
            origin = origin
        )
    }

    // function PlayerActivity lainnya mulai dari sini...
    // ===== PLAYER FILE LOGGER =====
    private fun savePlayerLog(message: String, error: Throwable? = null) {
        try {
            val dir = File(
                android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOWNLOADS
                ),
                "StarVision"
            )

            if (!dir.exists()) {
                dir.mkdirs()
            }

            val file = File(dir, "player_log.txt")

            val time = SimpleDateFormat(
                "yyyy-MM-dd HH:mm:ss.SSS",
                Locale.US
            ).format(Date())

            val text = StringBuilder()
                .append("[")
                .append(time)
                .append("] ")
                .append(message)

            if (error != null) {
                text.append("\n")
                text.append(Log.getStackTraceString(error))
            }

            text.append("\n")

            file.appendText(text.toString())

        } catch (e: Exception) {
            Log.e(
                "PLAYER_FILELOG",
                "Gagal menyimpan log: ${e.message}"
            )
        }
    }
   
    // ===== AUTO-DETEKSI SISTEM STREAMING =====
    private var formatFallbackQueue: MutableList<String?>? = null
    private var hasReachedReadyThisAttempt = false
    private var lastAttemptedMimeType: String? = null
    private val knownGoodMimeType = HashMap<String, String?>()
    private var currentCleanStreamUrl: String? = null

    // ===== BAGIAN 2: PLAYER CANGGIH =====
    private var autoQualityHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var lastBufferHealth = 100

    private var sleepTimerHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var sleepTimerRunnable: Runnable? = null
    private var sleepTimerSeconds = 0

    private val speedLevels = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
    private var speedIndex = 2

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

    // ── TV REMOTE SYSTEM ──
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

        isFirst = false

        if (Playlist.cached.isCategoriesEmpty()) {
            Log.e("PLAYER", getString(R.string.player_no_playlist))
            Toast.makeText(this, R.string.player_no_playlist, Toast.LENGTH_SHORT).show()
            this.finish()
            return
        }

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

        if (category == null || current == null) {
            Log.e("PLAYER", getString(R.string.player_no_channel))
            Toast.makeText(this, R.string.player_no_channel, Toast.LENGTH_SHORT).show()
            this.finish()
            return
        }

        bindingListener()
        playChannel()

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
                    bindingRoot.btnMiniChannelToggle.visibility = it
                }
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

        bindingControl.buttonLock.apply {
            visibility = if (isTelevision) View.GONE else View.VISIBLE
            setOnClickListener {
                (it as ImageButton).setImageResource(R.drawable.ic_lock)
                lockControl(true)
            }
        }

        val handlerLockOverlay = Handler(Looper.getMainLooper())
        bindingRoot.btnLockOverlay.setOnClickListener {
            (it as ImageButton).setImageResource(R.drawable.ic_lock_open)
            lockControl(false)
            bindingRoot.btnLockOverlay.visibility = View.GONE
        }

        bindingRoot.btnMiniChannelToggle.setOnClickListener {
            toggleMiniChannelPanel()
        }

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

    /**
     * Bangun JSON license ClearKey (W3C EME) dari string hex "kid:key".
     * WAJIB konversi hex -> Base64 URL-Safe, karena ExoPlayer LocalMediaDrmCallback
     * hanya menerima format Base64 URL-Safe (bukan hex mentah).
     */
    private fun buildClearKeyLicenseJson(license: String): ByteArray? {
        try {
            val rawLicense = license.substringBefore("|").trim()

            val pairs = rawLicense.split(Regex("[,/;\\s]+"))
                .map { it.trim() }
                .filter { it.isNotEmpty() && it.contains(":") }

            if (pairs.isEmpty()) {
                Log.w("DRM_DEBUG", "Tidak ada pair kid:key di '$license'")
                return null
            }

            val keysJson = StringBuilder("{\"keys\":[")
            var count = 0

            for (pair in pairs) {
                val colonIdx = pair.indexOf(':')
                if (colonIdx <= 0) continue

                val kidHex = pair.substring(0, colonIdx).trim()
                val keyHex = pair.substring(colonIdx + 1).trim()

                if (kidHex.length != 32 || keyHex.length != 32) {
                    Log.w("DRM_DEBUG", "Panjang invalid kid=${kidHex.length} key=${keyHex.length} pada '$pair'")
                    continue
                }
                val isHex = { s: String -> s.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' } }
                if (!isHex(kidHex) || !isHex(keyHex)) {
                    Log.w("DRM_DEBUG", "Bukan hex: '$pair'")
                    continue
                }

                val kidB64 = android.util.Base64.encodeToString(
                    hexToBytes(kidHex),
                    android.util.Base64.NO_PADDING or
                            android.util.Base64.URL_SAFE or
                            android.util.Base64.NO_WRAP)
                val keyB64 = android.util.Base64.encodeToString(
                    hexToBytes(keyHex),
                    android.util.Base64.NO_PADDING or
                            android.util.Base64.URL_SAFE or
                            android.util.Base64.NO_WRAP)

                if (count > 0) keysJson.append(",")
                keysJson.append("{\"kty\":\"oct\",\"kid\":\"$kidB64\",\"k\":\"$keyB64\"}")
                count++
            }

            keysJson.append("],\"type\":\"temporary\"}")

            if (count == 0) return null

            val json = keysJson.toString()
            Log.d("DRM_DEBUG", "ClearKey JSON ($count keys): $json")
            return json.toByteArray(Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e("DRM_DEBUG", "buildClearKeyLicenseJson error", e)
            return null
        }
    }

    private fun playChannel() {
        formatFallbackQueue = null
        playChannel(overrideMimeType = null, isFormatFallbackAttempt = false)
    }

    private fun playChannel(overrideMimeType: String?, isFormatFallbackAttempt: Boolean) {
        hasReachedReadyThisAttempt = false
        if (player != null) {
            try {
                player?.release()
            } catch (e: Exception) {
                Log.w("PLAYER", "release old player error: ${e.message}")
            }
            player = null
        }
        switchLiveOrVideo(true)

        bindingRoot.categoryName.text = category?.name?.trim()
        bindingRoot.channelName.text = current?.name?.trim()

        private fun playChannel() {
    formatFallbackQueue = null
    playChannel(overrideMimeType = null, isFormatFallbackAttempt = false)
}

private fun playChannel(overrideMimeType: String?, isFormatFallbackAttempt: Boolean) {
    hasReachedReadyThisAttempt = false

    if (player != null) {
        try {
            player?.release()
        } catch (e: Exception) {
            Log.w("PLAYER", "release old player error: ${e.message}")
        }
        player = null
    }

    switchLiveOrVideo(true)

    bindingRoot.categoryName.text = category?.name?.trim()
    bindingRoot.channelName.text = current?.name?.trim()

    // ==========================================
    // PARSING STREAM URL + HEADER
    // ==========================================

    val parsedRequest = parseStreamRequest(current?.streamUrl)

    var streamUrl = parsedRequest.url

    var userAgent = parsedRequest.userAgent
        ?: cleanHeaderValue(current?.userAgent)

    var referer = parsedRequest.referer
        ?: cleanHeaderValue(current?.referrer)

    var origin = parsedRequest.origin
        ?: cleanHeaderValue(current?.origin)

    if (userAgent.isNullOrBlank()) {
        userAgent = "StarVision-TV/1.0"
    }
        // Prioritas User-Agent dari field JSON channel ("ua")
        if (userAgent.isNullOrEmpty()) {
            userAgent = current?.userAgent
        }
        // Prioritas Referer dari field JSON channel
        if (referer.isNullOrEmpty()) {
            referer = current?.referrer
        }
        // Fallback terakhir: user-agent random dari resources
        if (userAgent.isNullOrEmpty()) {
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

        // License diambil dari cache ATAU langsung dari field inline channel
        val drmLicense = Playlist.cached.drmLicenses.firstOrNull {
            current?.drmName?.equals(it.name, ignoreCase = true) == true
        }?.url ?: current?.licenseKey

        // ============ DEBUG LOG ============
        Log.d("DRM_DEBUG", "=== CHANNEL DEBUG ===")
        Log.d("DRM_DEBUG", "channel.name       = ${current?.name}")
        Log.d("DRM_DEBUG", "channel.drmName    = ${current?.drmName}")
        Log.d("DRM_DEBUG", "channel.streamType = ${current?.streamType}")
        Log.d("DRM_DEBUG", "channel.licenseKey = ${current?.licenseKey}")
        Log.d("DRM_DEBUG", "resolved drmLicense = $drmLicense")
        Log.d("DRM_DEBUG", "userAgent = $userAgent")
        Log.d("DRM_DEBUG", "referer   = $referer")
        Log.d("DRM_DEBUG", "origin    = ${current?.origin}")
        // ====================================

        // Format MIME: pakai string literal, tidak bergantung ke class MimeTypes
        // supaya kompatibel dengan berbagai versi ExoPlayer.
        val mimeTypeFromField = when (current?.streamType?.lowercase(Locale.US)) {
            "dash", "mpd" -> "application/dash+xml"
            "hls", "m3u8" -> "application/x-mpegURL"
            "ss", "smoothstreaming" -> "application/vnd.ms-sstr+xml"
            "rtsp" -> "application/x-rtsp"
            "progressive", "mp4", "ts" -> "video/mp4"
            else -> null
        }

        val mimeType = when {
            isFormatFallbackAttempt -> overrideMimeType
            knownGoodMimeType.containsKey(streamUrl) -> knownGoodMimeType[streamUrl]
            mimeTypeFromField != null -> mimeTypeFromField
            else -> StreamFormatDetector.detect(streamUrl)
        }
        lastAttemptedMimeType = mimeType
        currentCleanStreamUrl = streamUrl

        Log.d("PLAYER_DEBUG", "========== REQUEST DEBUG ==========")
        Log.d("PLAYER_DEBUG", "channel   = ${current?.name}")
        Log.d("PLAYER_DEBUG", "streamUrl = $streamUrl")
        Log.d("PLAYER_DEBUG", "userAgent = $userAgent")
        Log.d("PLAYER_DEBUG", "referer   = $referer")
        Log.d("PLAYER_DEBUG", "origin    = ${current?.origin}")
        Log.d("PLAYER_DEBUG", "===================================")

        Log.d("PLAYER_FORMAT", "Channel='${current?.name}' format=$mimeType (fromField=${current?.streamType}) fallback=$isFormatFallbackAttempt")
       
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
       .setAllowCrossProtocolRedirects(true)
       .setUserAgent(userAgent)
       .setConnectTimeoutMs(15_000)
       .setReadTimeoutMs(20_000)

       val headers = mutableMapOf<String, String>()

       if (!referer.isNullOrBlank()) {
           headers["Referer"] = referer
       }

       if (!origin.isNullOrBlank()) {
           headers["Origin"] = origin
       }

       if (headers.isNotEmpty()) {
           httpDataSourceFactory.setDefaultRequestProperties(headers)
       }

        // Kirim header Referer + Origin (banyak CDN cubmu butuh Origin)
        val headers = mutableMapOf<String, String>()
        if (!referer.isNullOrEmpty()) headers["Referer"] = referer
        val origin = current?.origin
        if (!origin.isNullOrEmpty()) headers["Origin"] = origin
        if (headers.isNotEmpty()) httpDataSourceFactory.setDefaultRequestProperties(headers)

        val dataSourceFactory = DefaultDataSourceFactory(this, httpDataSourceFactory)

        // ================================================================
        // Build DrmSessionManager
        // ================================================================
        val drmName = current?.drmName?.lowercase(Locale.US).orEmpty()
        val isClearKey = drmName.contains("clearkey") || drmName.contains("clear_key") || drmName == "ck"
        val isWidevine = drmName.contains("widevine") || drmName == "wv"
        val hasDrm = drmName.isNotBlank() && !drmLicense.isNullOrBlank()

        Log.d("DRM_DEBUG", "drmName='$drmName' isClearKey=$isClearKey isWidevine=$isWidevine hasDrm=$hasDrm")

        var drmSessionManager: com.google.android.exoplayer2.drm.DrmSessionManager =
            com.google.android.exoplayer2.drm.DrmSessionManager.DRM_UNSUPPORTED

        if (hasDrm && isClearKey) {
            val rawLicense = drmLicense!!.substringBefore("|").trim()
            val looksLikeUrl = rawLicense.startsWith("http://", true) ||
                    rawLicense.startsWith("https://", true)

            try {
                if (looksLikeUrl) {
                    val drmCallback = com.google.android.exoplayer2.drm.HttpMediaDrmCallback(
                        rawLicense, httpDataSourceFactory)
                    drmSessionManager = com.google.android.exoplayer2.drm.DefaultDrmSessionManager.Builder()
                        .setUuidAndExoMediaDrmProvider(
                            C.CLEARKEY_UUID,
                            com.google.android.exoplayer2.drm.FrameworkMediaDrm.DEFAULT_PROVIDER)
                        .setMultiSession(true)
                        .build(drmCallback)
                    Log.d("DRM_DEBUG", "ClearKey via URL: $rawLicense")
                } else {
                    val licenseBytes = buildClearKeyLicenseJson(rawLicense)
                    if (licenseBytes == null) {
                        Log.e("DRM_DEBUG", "ClearKey parse FAILED: $rawLicense")
                        Toast.makeText(
                            applicationContext,
                            "ClearKey license tidak valid: $rawLicense",
                            Toast.LENGTH_LONG).show()
                    } else {
                        val drmCallback =
                            com.google.android.exoplayer2.drm.LocalMediaDrmCallback(licenseBytes)
                        drmSessionManager = com.google.android.exoplayer2.drm.DefaultDrmSessionManager.Builder()
                            .setUuidAndExoMediaDrmProvider(
                                C.CLEARKEY_UUID,
                                com.google.android.exoplayer2.drm.FrameworkMediaDrm.DEFAULT_PROVIDER)
                            .setMultiSession(true)
                            .build(drmCallback)
                        Log.d("DRM_DEBUG", "ClearKey direct OK: $rawLicense")
                    }
                }
            } catch (e: Exception) {
                Log.e("DRM_DEBUG", "ClearKey build error", e)
                Toast.makeText(applicationContext,
                    "ClearKey gagal inisialisasi: ${e.message}", Toast.LENGTH_LONG).show()
            }
        } else if (hasDrm && isWidevine) {
            if (!isDrmWidevineSupported()) return
            try {
                val rawLicense = drmLicense!!.substringBefore("|").trim()
                val drmCallback = com.google.android.exoplayer2.drm.HttpMediaDrmCallback(
                    rawLicense, httpDataSourceFactory)
                drmSessionManager = com.google.android.exoplayer2.drm.DefaultDrmSessionManager.Builder()
                    .setUuidAndExoMediaDrmProvider(
                        C.WIDEVINE_UUID,
                        com.google.android.exoplayer2.drm.FrameworkMediaDrm.DEFAULT_PROVIDER)
                    .setMultiSession(true)
                    .build(drmCallback)
                Log.d("DRM_DEBUG", "Widevine OK, url=$rawLicense")
            } catch (e: Exception) {
                Log.e("DRM_DEBUG", "Widevine build error", e)
            }
        } else if (drmName.isNotBlank() && drmLicense.isNullOrBlank()) {
            Log.e("DRM_DEBUG", "DRM channel ($drmName) but license NOT FOUND!")
            Toast.makeText(applicationContext,
                "DRM license tidak ditemukan (drmName='$drmName')", Toast.LENGTH_LONG).show()
        }
        // ================================================================
        // END DRM
        // ================================================================

        mediaItem = MediaItem.Builder()
            .setUri(Uri.parse(streamUrl))
            .also { if (mimeType != null) it.setMimeType(mimeType) }
            .build()

        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)
            .setDrmSessionManagerProvider { drmSessionManager }

        trackSelector = DefaultTrackSelector(this).apply {
            val maxHeights = listOf(360, 720, 1080, 2160, Int.MAX_VALUE)
            val maxBitrates = listOf(800_000, 2_500_000, 5_000_000, 20_000_000, Int.MAX_VALUE)
            val idx = preferences.resolutionIndex.coerceIn(0, maxHeights.size - 1)
            parameters = ParametersBuilder(applicationContext)
                .setMaxVideoSize(Int.MAX_VALUE, maxHeights[idx])
                .setMaxVideoBitrate(maxBitrates[idx])
                .setAllowVideoMixedMimeTypeAdaptiveness(true)
                .setAllowAudioMixedMimeTypeAdaptiveness(true)
                .setExceedRendererCapabilitiesIfNecessary(true)
                .setExceedVideoConstraintsIfNecessary(true)
                .setExceedAudioConstraintsIfNecessary(true)
                .build()
        }

        val loadControl: LoadControl = DefaultLoadControl.Builder()
            .setAllocator(DefaultAllocator(true, 16))
            .setBufferDurationsMs(
                3_000,
                15_000,
                1_500,
                3_000
            )
            .setTargetBufferBytes(4 * 1024 * 1024)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val renderersFactory = DefaultRenderersFactory(this)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
            .setEnableDecoderFallback(true)

        val playerBuilder = com.google.android.exoplayer2.ExoPlayer.Builder(this, renderersFactory)
            .setMediaSourceFactory(mediaSourceFactory)
            .setTrackSelector(trackSelector)
            .setLoadControl(loadControl)

        try {
            player = playerBuilder.build()
            player?.addListener(PlayerListener())

            bindingRoot.playerView.player = player
            bindingRoot.playerView.resizeMode = preferences.resizeMode
            bindingRoot.playerView.requestFocus()

            player?.playWhenReady = true
            player?.setMediaItem(mediaItem)
            player?.prepare()
        } catch (e: Exception) {
            android.util.Log.e("PLAYER", "Build/play error: ${e.message}", e)
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
                playChannel()
                return
            }
            try {
                player?.playWhenReady = true
                player?.setMediaItem(mediaItem)
                player?.prepare()
            } catch (e: Exception) {
                Log.e("PLAYER", "retryPlayback error: ${e.message}")
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
        }).start(2)
    }

    private inner class PlayerListener : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            val trackHaveContent = TrackSelectionDialog.willHaveContent(trackSelector)
            bindingControl.trackSelection.visibility =
                if (trackHaveContent) View.VISIBLE else View.GONE
            when (state) {
                Player.STATE_READY -> {
                    errorCounter = 0
                    hasReachedReadyThisAttempt = true
                    formatFallbackQueue = null
                    currentCleanStreamUrl?.let { knownGoodMimeType[it] = lastAttemptedMimeType }
                    val catId = Playlist.cached.categories.indexOf(category)
                    val chId = category?.channels?.indexOf(current) ?: -1
                    preferences.watched = PlayData(catId, chId)
                    switchLiveOrVideo()
                    updateMiniChannelActive()
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
                    val isLive = player?.isCurrentMediaItemLive == true
                    if (!isLive) retryPlayback(true)
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

    Log.e(
        "PLAYER_ERROR",
        "channel=${current?.name} | " +
        "code=${error.errorCode} | " +
        "name=${error.errorCodeName} | " +
        "msg=$errorMsg"
    )

    // ============================================================
    // DETAIL HTTP ERROR
    // Mencari InvalidResponseCodeException di seluruh cause chain.
    // ============================================================
    var cause: Throwable? = error.cause
    var httpError: HttpDataSource.InvalidResponseCodeException? = null

    while (cause != null) {
        if (cause is HttpDataSource.InvalidResponseCodeException) {
            httpError = cause
            break
        }
        cause = cause.cause
    }

    if (httpError != null) {
    if (httpError != null) {
    val httpLog = buildString {
        append("HTTP ERROR\n")
        append("channel=${current?.name}\n")
        append("responseCode=${httpError.responseCode}\n")
        append("message=${httpError.message}\n")
        append("url=$currentCleanStreamUrl\n")

        // Gunakan nilai header yang sudah dibersihkan
        // dari parser stream.
        append("userAgent=${cleanHeaderValue(current?.userAgent)}\n")
        append("referer=${cleanHeaderValue(current?.referrer)}\n")
        append("origin=${cleanHeaderValue(current?.origin)}\n")

        append("responseHeaders=${httpError.headerFields}\n")
    }

    Log.e("PLAYER_HTTP_ERROR", httpLog)

    try {
        savePlayerLog(httpLog)
    } catch (e: Exception) {
        Log.e(
            "PLAYER_HTTP_ERROR",
            "Gagal menyimpan HTTP log: ${e.message}"
        )
    }
}

    // ============================================================
    // BEHIND LIVE WINDOW
    // ============================================================
    if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
        player?.seekToDefaultPosition()
        player?.prepare()
        return
    }

    // ============================================================
    // FORMAT ERROR
    // ============================================================
    val isFormatError =
        error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED ||
        error.errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED ||
        error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ||
        error.errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED

    if (isFormatError && !hasReachedReadyThisAttempt) {

        if (formatFallbackQueue == null) {
            formatFallbackQueue =
                StreamFormatDetector.FORMAT_FALLBACK_LADDER
                    .filter { it != lastAttemptedMimeType }
                    .toMutableList()
        }

        val queue = formatFallbackQueue

        if (!queue.isNullOrEmpty()) {
            val nextMimeType = queue.removeAt(0)

            Log.w(
                "PLAYER_FORMAT",
                "Format ${StreamFormatDetector.label(lastAttemptedMimeType)} gagal, " +
                "coba ${StreamFormatDetector.label(nextMimeType)} " +
                "untuk channel '${current?.name}'"
            )

            Handler(Looper.getMainLooper()).post {
                if (isDestroyed) return@post

                try {
                    player?.release()
                } catch (e: Exception) {
                    // abaikan
                }

                player = null

                playChannel(
                    overrideMimeType = nextMimeType,
                    isFormatFallbackAttempt = true
                )
            }

            return
        }
    }

    // ============================================================
    // IO ERROR / RETRY
    // ============================================================
    val isIoError =
        error.errorCode >= PlaybackException.ERROR_CODE_IO_UNSPECIFIED &&
        error.errorCode <= PlaybackException.ERROR_CODE_IO_NO_PERMISSION

    val isLive = player?.isCurrentMediaItemLive ?: false

    val maxRetry = if (isLive) 15 else 8

    if (errorCounter < maxRetry && network.isConnected()) {
        errorCounter++

        val delaySeconds = when {
            errorCounter <= 3 -> 2
            errorCounter <= 8 -> 4
            else -> 6
        }

        AsyncSleep().task(object : AsyncSleep.Task {
            override fun onFinish() {
                retryPlayback(true)
            }
        }).start(delaySeconds)

    } else {
        showMessage(
            String.format(
                getString(R.string.player_error_message),
                error.errorCode,
                error.errorCodeName,
                errorMsg
            ),
            true
        )
    }
}
}

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
                    bindingRoot.btnLockOverlay.setImageResource(R.drawable.ic_lock)
                    bindingRoot.btnLockOverlay.visibility = View.VISIBLE
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
                        player?.seekBack()
                        showDoubleTapFeedback(false)
                    } else {
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

    // ===== 4. GESTURE CONTROL =====
    private fun setupGestureControl() {
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

                if (!isGestureBrightness && !isGestureVolume) {
                    if (absDX > 30 || absDY > 30) {
                        isGestureBrightness = absDY > absDX
                        isGestureVolume = absDX >= absDY
                    }
                }

                if (isGestureBrightness && absDY > 20) {
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
        autoQualityHandler.postDelayed(object : Runnable {
            override fun run() {
                val isLive = player?.isCurrentMediaItemLive ?: false
                if (!isLive) checkAndAdjustQuality()
                autoQualityHandler.postDelayed(this, 15000)
            }
        }, 15000)
    }

    private fun checkAndAdjustQuality() {
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
            bindingRoot.btnMiniChannelToggle.animate()
                .rotation(180f).setDuration(200).start()
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
            if (UiMode().isTelevision()) {
                bindingRoot.playerView.requestFocus()
            }
        }
    }

    private fun updateMiniChannelActive() {
        val channels = category?.channels ?: return
        val idx = channels.indexOf(current).coerceAtLeast(0)
        miniChannelAdapter?.setActiveChannel(idx)
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

    // ── TV REMOTE ──
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (tvRemote.dispatchKeyEvent(event)) return true
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (tvRemote.onKeyUp(keyCode, event)) return true

        if (isMiniPanelVisible &&
            (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE)) {
            toggleMiniChannelPanel()
            return true
        }

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

        if (isLocked) return true

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

        if (player?.isCurrentMediaItemLive == false) {
            when (keyCode) {
                KeyEvent.KEYCODE_MEDIA_REWIND,
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                    if (!bindingRoot.playerView.isControllerVisible)
                        bindingRoot.playerView.showController()
                    if (keyCode == KeyEvent.KEYCODE_MEDIA_REWIND) player?.seekBack()
                    else player?.seekForward()
                    return true
                }
            }
        }

        if (bindingRoot.playerView.isControllerVisible) {
            return super.onKeyUp(keyCode, event)
        }

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
        if (isMiniPanelVisible) {
            toggleMiniChannelPanel()
            return
        }
        if (isLocked) return
        if (bindingRoot.playerView.isControllerVisible) {
            bindingRoot.playerView.hideController()
            return
        }
        if (isTelevision || doubleBackToExitPressedOnce) {
            super.onBackPressed()
            finish(); return
        }
        doubleBackToExitPressedOnce = true
        Toast.makeText(this, getString(R.string.press_back_twice_exit_player), Toast.LENGTH_SHORT).show()
        Handler(Looper.getMainLooper()).postDelayed({ doubleBackToExitPressedOnce = false }, 2000)
    }

    override fun onDestroy() {
        sleepTimerRunnable?.let { sleepTimerHandler.removeCallbacks(it) }
        gestureHandler.removeCallbacksAndMessages(null)
        autoQualityHandler.removeCallbacksAndMessages(null)
        player?.release()
        LocalBroadcastManager.getInstance(this)
            .unregisterReceiver(broadcastReceiver)
        tvRemote.onDestroy()
        super.onDestroy()
    }
}
