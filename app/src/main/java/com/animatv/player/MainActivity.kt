package com.animatv.player

import android.annotation.SuppressLint
import android.content.*
import java.text.SimpleDateFormat
import java.util.*
import android.content.pm.ActivityInfo
import android.os.*
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.core.widget.TextViewCompat
import androidx.core.content.ContextCompat
import com.google.android.material.snackbar.Snackbar
import com.animatv.player.adapter.CategoryAdapter
import com.animatv.player.adapter.SidebarAdapter
import androidx.databinding.DataBindingUtil
import com.animatv.player.databinding.ActivityMainBinding
import com.animatv.player.dialog.SearchDialog
import com.animatv.player.dialog.SettingDialog
import com.animatv.player.extension.*
import com.animatv.player.extra.*
import com.animatv.player.extra.CategoryOrderManager
import com.animatv.player.extra.LocaleHelper
import com.animatv.player.model.*

open class MainActivity : AppCompatActivity() {
    private var doubleBackToExitPressedOnce = false
    private val isTelevision by lazy { UiMode().isTelevision() }
    private val preferences by lazy { Preferences() }
    private val helper by lazy { PlaylistHelper() }
    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: CategoryAdapter
    private var sidebarAdapter: SidebarAdapter? = null
    private val clockHandler = Handler(Looper.getMainLooper())
    private val clockRunnable = object : Runnable {
        override fun run() {
            updateClock()
            clockHandler.postDelayed(this, 1000)
        }
    }

    // Category display names (tanpa emoji supaya aman di semua device)
    private val catNames = mapOf(
        "nasional"      to "[TV] NASIONAL",
        "berita"        to "[NWS] BERITA",
        "hiburan"       to "[ENT] HIBURAN",
        "olahraga"      to "[SPT] OLAHRAGA",
        "internasional" to "[INT] INTERNASIONAL",
        "jepang"        to "[JPN] JEPANG",
        "vision+"       to "[VIS] VISION+ DRM",
        "vision"        to "[VIS] VISION+ DRM",
        "indihome"      to "[IND] INDIHOME DRM",
        "custom"        to "[CST] CUSTOM",
        "favorit"       to "\u2605 FAVORIT",
        "favorite"      to "\u2605 FAVORIT"
    )

    private val guideHandler = Handler(Looper.getMainLooper())
    private val guideMessages = listOf(
        "Selamat datang di StarVision TV! ",
        "Gunakan remote untuk navigasi channel ",
        "Long press channel untuk tambah favorit ",
        "Swipe untuk ganti channel di player ",
        "Tap < untuk mini channel list ",
        "Semangat nonton!  "
    )
    private var guideIndex = 0

    // ── TV REMOTE SYSTEM ── (sistem terpisah, tidak ubah kode lain)
    private val tvMainRemote by lazy {
        com.animatv.player.tv.TvMainRemote(
            object : com.animatv.player.tv.TvRemoteContract.MainHost {
                override fun isTvDevice()         = isTelevision
                override fun isDropdownMenuOpen() = isDropdownOpen
                override fun isFocusInSidebar()   =
                    currentFocus?.let { isViewInSidebar(it) } ?: false
                override fun isFocusInTopbar()    = run {
                    val f = currentFocus ?: return@run false
                    isViewInTopbar(f)
                }
                override fun openDropdown()       = this@MainActivity.openDropdown()
                override fun closeDropdown()      = this@MainActivity.closeDropdown()
                override fun focusSidebar()       {
                    // Minta fokus ke RecyclerView, lalu ke child pertama yang focusable
                    val rv = binding.rvSidebar
                    rv.post {
                        rv.requestFocus()
                        // Scroll ke atas dulu biar item 0 visible
                        rv.scrollToPosition(0)
                        val child = rv.getChildAt(0)
                        child?.requestFocus() ?: rv.requestFocus()
                    }
                }
                override fun focusSidebarDelayed() {
                    binding.rvSidebar.postDelayed({
                        binding.rvSidebar.scrollToPosition(0)
                        binding.rvSidebar.requestFocus()
                        binding.rvSidebar.getChildAt(0)?.requestFocus()
                    }, 300)
                }
                override fun focusChannelGrid()   {
                    val rv = binding.rvCategory
                    rv.post {
                        rv.requestFocus()
                        if (rv.findFocus() == null) rv.getChildAt(0)?.requestFocus()
                    }
                }
                override fun focusTopbar()        {
                    binding.buttonSearch.requestFocus()
                }
                override fun openSearch()         = this@MainActivity.openSearch()
                override fun openSettings()       = this@MainActivity.openSettings()
                override fun exitApp()            = finish()
            }
        )
    }
    // ── END TV REMOTE SYSTEM ──

    private val broadcastReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent) {
            when(intent.getStringExtra(MAIN_CALLBACK)) {
                UPDATE_PLAYLIST -> updatePlaylist(false)
                INSERT_FAVORITE -> adapter.insertOrUpdateFavorite()
                REMOVE_FAVORITE -> adapter.removeFavorite()
            }
        }
    }

    companion object {
        const val MAIN_CALLBACK = "MAIN_CALLBACK"
        const val UPDATE_PLAYLIST = "UPDATE_PLAYLIST"
        const val INSERT_FAVORITE = "REFRESH_FAVORITE"
        const val REMOVE_FAVORITE = "REMOVE_FAVORITE"
    }

    @SuppressLint("DefaultLocale")
    override fun attachBaseContext(base: android.content.Context) {
        val lang = LocaleHelper.getLanguageCode(base)
        super.attachBaseContext(LocaleHelper.setLocale(base, lang))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        binding = DataBindingUtil.setContentView(this, R.layout.activity_main)
        startClock()

        binding.buttonSearch.setOnClickListener { openSearch() }
        binding.buttonRefresh.setOnClickListener { updatePlaylist(false) }
        binding.buttonSettings.setOnClickListener { openSettings() }
        binding.buttonExit.setOnClickListener { finish() }
        binding.searchHint?.setOnClickListener { openSearch() }

        // Tap logo 7x untuk buka Admin Panel (rahasia!)
        binding.txtAppTitle?.setOnClickListener {
            if (AdminManager.onLogoTapped()) {
                AdminManager.resetTapCount()
                showAdminLoginDialog()
            }
        }

        // Resolution buttons - apply via broadcast to PlayerActivity
        setupResolutionButtons()

        LocalBroadcastManager.getInstance(this)
            .registerReceiver(broadcastReceiver, IntentFilter(MAIN_CALLBACK))

        if (!Playlist.cached.isCategoriesEmpty()) setPlaylistToAdapter(Playlist.cached)
        else showAlertPlaylistError(getString(R.string.null_playlist))

        // ===== SETUP ANIME THEME =====
        setupAnimeTheme()
    }

    private fun setLoadingPlaylist(show: Boolean) {
        if (show) {
            binding.loading.startShimmer()
            binding.loading.visibility = View.VISIBLE
        } else {
            binding.loading.stopShimmer()
            binding.loading.visibility = View.GONE
        }
    }



    // ===== ADMIN PANEL =====
    private fun showAdminLoginDialog() {
        if (AdminManager.isAdminUnlocked) {
            // Langsung buka kalau sudah unlock
            openAdminPanel()
            return
        }

        val input = android.widget.EditText(this).apply {
            hint = "Masukkan kode admin"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                        android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setPadding(40, 20, 40, 20)
        }

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Admin Panel")
            .setMessage("Masukkan kode untuk mengakses Admin Panel")
            .setView(input)
            .setPositiveButton("Masuk") { _, _ ->
                val code = input.text.toString()
                if (AdminManager.tryUnlockAdmin(code)) {
                    openAdminPanel()
                } else {
                    android.widget.Toast.makeText(this,
                        "Kode salah!", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun openAdminPanel() {
        startActivity(android.content.Intent(this, AdminActivity::class.java))
    }

    // ============================================================
    // BAGIAN 1: TEMA ANIME
    // ============================================================

    private fun setupAnimeTheme() {
        // Fetch remote config di background
        AdminManager.fetchConfigAsync { config ->
            // Config sudah di-cache, fitur akan pakai config terbaru
            android.util.Log.d("StarVisionTV", "Config v${config.configVersion} loaded")

            // Kalau kategori sudah sempat dimuat sebelum config remote
            // ini datang (kondisi ini hanya terjadi di launch pertama
            // sebelum cache config terisi), susun ulang sidebar supaya
            // urutan/kategori tersembunyi dari remote config langsung
            // kepakai tanpa perlu buka & tutup app lagi.
            if (!isFinishing && allCategories.isNotEmpty()) {
                setupSidebar(Playlist.cached)
            }
        }

        // Setup fitur berdasarkan config
        if (AdminManager.isFeatureEnabled("anime_background")) setupBackgroundRotator()
        if (AdminManager.isFeatureEnabled("anime_quote")) setupQuoteOfDay()
        if (AdminManager.isFeatureEnabled("sakura_effect")) setupSakuraEffect()
        if (AdminManager.isFeatureEnabled("anime_guide")) setupAnimeCharacterGuide()
    }

    // 1. ANIME BACKGROUND ROTATOR
    private fun setupBackgroundRotator() {
        val imgBg = binding.imgAnimeBg
        // Load background awal
        com.bumptech.glide.Glide.with(this)
            .load(R.drawable.bg_anime_1)
            .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.RESOURCE)
            .into(imgBg)
        imgBg.alpha = 0.18f
        // Mulai rotasi tiap 30 detik
        AnimeThemeManager.startBackgroundRotator(this, imgBg, 30)
    }

    // 2. QUOTE OF THE DAY
    private fun setupQuoteOfDay() {
        val quote = AnimeThemeManager.getQuoteOfDay(this) ?: return
        binding.txtQuoteAnime.text = quote.anime.uppercase()
        binding.txtQuoteContent.text = "\"${quote.quote}\""
        binding.txtQuoteCharacter.text = "- ${quote.character}"

        // Tap quote = ganti ke quote random
        binding.quoteCard.setOnClickListener {
            val random = AnimeThemeManager.getRandomQuote(this) ?: return@setOnClickListener
            binding.txtQuoteAnime.text = random.anime.uppercase()
            binding.txtQuoteContent.text = "\"${random.quote}\""
            binding.txtQuoteCharacter.text = "- ${random.character}"

            // Animasi fade
            binding.quoteCard.animate()?.alpha(0f)?.setDuration(200)?.withEndAction {
                binding.quoteCard.animate()?.alpha(1f)?.setDuration(300)?.start()
            }?.start()
        }
    }

    // 3. SAKURA EFFECT
    private fun setupSakuraEffect() {
        val container = binding.sakuraContainer
        // Delay sedikit supaya layout sudah siap
        Handler(Looper.getMainLooper()).postDelayed({
            AnimeThemeManager.startSakuraEffect(container as ViewGroup)
        }, 1000)
    }

    // 4. ANIME CHARACTER GUIDE
    private fun setupAnimeCharacterGuide() {
        val layout = binding.characterGuideLayout
        val bubble = binding.guideBubble
        val txtMsg = binding.txtGuideMessage

        // Tampilkan setelah 2 detik
        guideHandler.postDelayed({
            layout.visibility = android.view.View.VISIBLE
            layout.alpha = 0f
            layout.animate().alpha(1f).setDuration(600).start()
            showNextGuideMessage(txtMsg, layout)
        }, 2000)
    }

    private fun showNextGuideMessage(
        txtMsg: android.widget.TextView,
        layout: android.view.View
    ) {
        val message = guideMessages[guideIndex % guideMessages.size]
        guideIndex++

        txtMsg.text = message

        // Sembunyikan setelah 5 detik
        guideHandler.postDelayed({
            layout.animate().alpha(0f).setDuration(500).withEndAction {
                layout.visibility = android.view.View.GONE
            }.start()
        }, 5000)

        // Muncul lagi setelah 30 detik dengan message berikutnya
        guideHandler.postDelayed({
            if (!isFinishing) {
                layout.visibility = android.view.View.VISIBLE
                layout.alpha = 0f
                layout.animate().alpha(1f).setDuration(600).start()
                showNextGuideMessage(txtMsg, layout)
            }
        }, 35000)
    }

    // === DROPDOWN MENU SIDEBAR (seperti GVision) ===
    private var allCategories: ArrayList<Category> = ArrayList()
    private var primaryCategories: ArrayList<Category> = ArrayList()
    private var isDropdownOpen = false

    // Kategori yang tampil di sidebar luar (bukan bagian dari menu apapun)
    private val primaryCatKeywords = listOf(
        "nasional", "movies", "entertainment", "hiburan", "daerah",
        "kids", "anime", "jepang", "sport", "olahraga", "berita",
        "internasional", "vision", "indihome", "custom", "favorit", "favorite"
    )

    private fun setupSidebar(playlistSet: Playlist) {
        allCategories = ArrayList(playlistSet.categories)

        val ordered = CategoryOrderManager.applySavedOrder(allCategories.toList()) { it.name ?: "" }
        allCategories = ArrayList(ordered)

        // Kategori primary = yang tidak masuk menu manapun dari MenuManager
        // ATAU jika tidak ada menu config, pakai keyword matching
        val menus = MenuManager.getMenus()
        primaryCategories = if (menus.isNotEmpty()) {
            // Kategori yang tidak terdaftar di menu manapun
            ArrayList(allCategories.filter { cat ->
                !MenuManager.isInAnyMenu(cat.name ?: "")
            })
        } else {
            // Fallback keyword matching
            ArrayList(allCategories.filter { cat ->
                val name = cat.name?.lowercase() ?: ""
                primaryCatKeywords.any { name.contains(it) }
            })
        }

        // Kalau primaryCategories kosong, tampilkan semua
        if (primaryCategories.isEmpty()) primaryCategories = allCategories

        sidebarAdapter = SidebarAdapter(primaryCategories) { cat, _ ->
            val catIndex = Playlist.cached.categories.indexOfFirst {
                it.name?.trim().equals(cat.name?.trim(), ignoreCase = true)
            }
            adapter.showCategory(if (catIndex >= 0) catIndex else 0)
            binding.rvCategory.scrollToPosition(0)
            closeDropdown()
        }
        binding.rvSidebar.layoutManager = LinearLayoutManager(this)
        binding.rvSidebar.adapter = sidebarAdapter
        // TV: pastikan RecyclerView bisa menerima fokus dan navigasi DPAD
        binding.rvSidebar.isFocusable = true
        binding.rvSidebar.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        binding.rvSidebar.itemAnimator = null

        // Tampilkan dropdown header kalau ada menu
        if (menus.isNotEmpty()) {
            binding.layoutDropdownHeader?.visibility = View.VISIBLE
            binding.layoutDropdownHeader?.setOnClickListener { toggleDropdown() }
            // TV Remote: ENTER/DPAD_CENTER buka/tutup dropdown
            binding.layoutDropdownHeader?.setOnKeyListener { _, keyCode, event ->
                if (event.action == android.view.KeyEvent.ACTION_DOWN &&
                    (keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER ||
                     keyCode == android.view.KeyEvent.KEYCODE_ENTER ||
                     keyCode == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER)) {
                    toggleDropdown()
                    true
                } else false
            }
            // TV Remote: focus highlight
            binding.layoutDropdownHeader?.setOnFocusChangeListener { v, hasFocus ->
                v.isSelected = hasFocus
            }
        } else {
            binding.layoutDropdownHeader?.visibility = View.GONE
        }

        if (primaryCategories.isNotEmpty()) {
            val catIndex = Playlist.cached.categories.indexOfFirst {
                it.name?.trim().equals(primaryCategories[0].name?.trim(), ignoreCase = true)
            }
            adapter.showCategory(if (catIndex >= 0) catIndex else 0)
        }
    }

    private fun toggleDropdown() {
        if (isDropdownOpen) closeDropdown() else openDropdown()
    }

    private fun openDropdown() {
        isDropdownOpen = true
        binding.dropdownMenuContainer?.visibility = View.VISIBLE
        binding.txtDropdownArrow?.text = "\u25b4"
        setupDropdownMenuItems()
        // TV Remote: pindah fokus ke item pertama di dropdown setelah terbuka
        binding.dropdownItemsContainer?.post {
            val firstItem = binding.dropdownItemsContainer?.getChildAt(0)
            firstItem?.requestFocus()
        }
    }

    private fun closeDropdown() {
        isDropdownOpen = false
        binding.dropdownMenuContainer?.visibility = View.GONE
        binding.txtDropdownArrow?.text = "\u25be"
        // TV Remote: kembalikan fokus ke header setelah dropdown ditutup
        binding.layoutDropdownHeader?.requestFocus()
    }

    private fun setupDropdownMenuItems() {
        val container = binding.dropdownItemsContainer ?: return
        container.removeAllViews()

        val menus = MenuManager.getMenus()

        for (menu in menus) {
            addDropdownItem(container, menu.label) {
                binding.txtCurrentMenu?.text = menu.label
                // Tampilkan sub-kategori dari menu ini di sidebar
                val subCats = ArrayList(MenuManager.getSubCategories(menu.id))
                if (subCats.isNotEmpty()) {
                    // Update sidebar dengan sub-kategori
                    sidebarAdapter?.updateCategories(subCats)
                    // Tampilkan channel dari sub-kategori pertama
                    val catIndex = Playlist.cached.categories.indexOfFirst {
                        it.name?.trim().equals(subCats[0].name?.trim(), ignoreCase = true)
                    }
                    adapter.showCategory(if (catIndex >= 0) catIndex else 0)
                    binding.rvSidebar.scrollToPosition(0)
                    binding.rvCategory.scrollToPosition(0)
                }
                closeDropdown()
            }
        }

        // Divider + tombol kembali ke Live TV
        if (menus.isNotEmpty()) {
            val divider = View(this).apply {
                setBackgroundColor(0x88E91E8C.toInt())
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(2))
            }
            container.addView(divider)

            addDropdownItem(container, "LIVE TV") {
                binding.txtCurrentMenu?.text = "LIVE TV"
                // Kembalikan sidebar ke kategori primary
                sidebarAdapter?.updateCategories(primaryCategories)
                if (primaryCategories.isNotEmpty()) {
                    val catIndex = Playlist.cached.categories.indexOfFirst {
                        it.name?.trim().equals(primaryCategories[0].name?.trim(), ignoreCase = true)
                    }
                    adapter.showCategory(if (catIndex >= 0) catIndex else 0)
                    sidebarAdapter?.selectCategory(0)
                }
                binding.rvSidebar.scrollToPosition(0)
                binding.rvCategory.scrollToPosition(0)
                closeDropdown()
            }
        }
    }

    private fun addDropdownItem(
        container: android.widget.LinearLayout,
        label: String,
        onClick: () -> Unit
    ) {
        val tv = android.widget.TextView(this).apply {
            text = label
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 13f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dpToPx(20), 0, dpToPx(16), 0)
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(48))
            isClickable = true
            isFocusable = true
            isFocusableInTouchMode = false
            background = androidx.core.content.ContextCompat.getDrawable(
                this@MainActivity, R.drawable.dropdown_item_bg)

            // Klik mouse/touch
            setOnClickListener { onClick() }

            // TV Remote: fokus highlight — ubah warna teks
            setOnFocusChangeListener { _, hasFocus ->
                setTextColor(if (hasFocus) 0xFFFFD700.toInt() else 0xFFFFFFFF.toInt())
            }

            // TV Remote: ENTER pilih item, BACK tutup dropdown,
            //            DPAD UP/DOWN navigasi antar item
            setOnKeyListener { v, keyCode, event ->
                if (event.action != android.view.KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                when (keyCode) {
                    android.view.KeyEvent.KEYCODE_DPAD_CENTER,
                    android.view.KeyEvent.KEYCODE_ENTER,
                    android.view.KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                        onClick()
                        true
                    }
                    android.view.KeyEvent.KEYCODE_BACK,
                    android.view.KeyEvent.KEYCODE_ESCAPE -> {
                        closeDropdown()
                        true
                    }
                    android.view.KeyEvent.KEYCODE_DPAD_UP -> {
                        // Cari item sebelumnya yang bisa difokus
                        val idx = container.indexOfChild(v)
                        var prev = idx - 1
                        while (prev >= 0) {
                            val c = container.getChildAt(prev)
                            if (c != null && c.isFocusable && c.visibility == View.VISIBLE) {
                                c.requestFocus(); return@setOnKeyListener true
                            }
                            prev--
                        }
                        // Sudah di atas → kembalikan fokus ke header
                        binding.layoutDropdownHeader?.requestFocus()
                        true
                    }
                    android.view.KeyEvent.KEYCODE_DPAD_DOWN -> {
                        // Cari item berikutnya yang bisa difokus
                        val idx = container.indexOfChild(v)
                        var next = idx + 1
                        while (next < container.childCount) {
                            val c = container.getChildAt(next)
                            if (c != null && c.isFocusable && c.visibility == View.VISIBLE) {
                                c.requestFocus(); return@setOnKeyListener true
                            }
                            next++
                        }
                        true
                    }
                    else -> false
                }
            }
        }
        container.addView(tv)
        val sep = View(this).apply {
            setBackgroundColor(0x33FFFFFF)
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 1)
        }
        container.addView(sep)
    }

    private fun dpToPx(dp: Int) = (dp * resources.displayMetrics.density).toInt()

    private fun showSingleCategoryDirect(cat: Category) {
        val tempList = ArrayList<Category>().apply { add(cat) }
        val tempAdapter = CategoryAdapter(tempList)
        binding.catAdapter = tempAdapter
    }

    private fun updateSidebarCats(cats: ArrayList<Category>) {
        sidebarAdapter?.updateCategories(cats)
        if (cats.isNotEmpty()) {
            val catIndex = Playlist.cached.categories.indexOfFirst {
                it.name?.trim().equals(cats[0].name?.trim(), ignoreCase = true)
            }
            adapter.showCategory(if (catIndex >= 0) catIndex else 0)
        }
        binding.rvSidebar.scrollToPosition(0)
        binding.rvCategory.scrollToPosition(0)
    }

    private fun updateSidebarCatsLegacy(cats: ArrayList<Category>) {
        // kept for compatibility
    }

    @SuppressLint("SetTextI18n")
    private fun updateStats(playlistSet: Playlist) {
        try {
            val total = playlistSet.categories.sumOf { it.channels?.size ?: 0 }
            val customCount = playlistSet.categories
                .filter { it.name?.lowercase()?.contains("custom") == true }
                .sumOf { it.channels?.size ?: 0 }
            binding.statTotal?.text = total.toString()
            binding.statCustom?.text = customCount.toString()

            val firstCat = playlistSet.categories.firstOrNull()
            if (firstCat != null) {
                val key = firstCat.name?.lowercase()?.trim() ?: ""
                val title = catNames.entries.firstOrNull { key.contains(it.key) }?.value
                    ?: firstCat.name?.uppercase() ?: ""
                binding.textCurrentCat?.text = title
            }
        } catch (e: Exception) { }
    }

    private fun setPlaylistToAdapter(playlistSet: Playlist) {
        if (preferences.sortCategory) playlistSet.sortCategories()
        if (preferences.sortChannel) playlistSet.sortChannels()
        playlistSet.trimChannelWithEmptyStreamUrl()

        val fav = helper.readFavorites().trimNotExistFrom(playlistSet)
        if (preferences.sortFavorite) fav.sort()
        if (fav?.channels?.isNotEmpty() == true)
            playlistSet.insertFavorite(fav.channels)
        else playlistSet.removeFavorite()

        adapter = CategoryAdapter(playlistSet.categories)
        binding.catAdapter = adapter

        Playlist.cached = playlistSet
        helper.writeCache(playlistSet)

        setupSidebar(playlistSet)
        updateStats(playlistSet)
        setLoadingPlaylist(false)
        Toast.makeText(applicationContext, R.string.playlist_updated, Toast.LENGTH_SHORT).show()

        // TV: fokus awal ke sidebar item pertama setelah layout selesai
        binding.rvCategory.isFocusable = true
        binding.rvCategory.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        binding.rvCategory.itemAnimator = null
        if (isTelevision) {
            tvMainRemote  // inisialisasi lazy supaya siap
            binding.rvSidebar.postDelayed({
                binding.rvSidebar.scrollToPosition(0)
                binding.rvSidebar.requestFocus()
                // Coba fokus ke child pertama, jika belum ada view-nya retry
                val child = binding.rvSidebar.getChildAt(0)
                if (child != null) child.requestFocus()
                else binding.rvSidebar.postDelayed({
                    binding.rvSidebar.getChildAt(0)?.requestFocus()
                        ?: binding.rvSidebar.requestFocus()
                }, 200)
            }, 300)
        }

        if (preferences.playLastWatched && PlayerActivity.isFirst) {
            val intent = Intent(this, PlayerActivity::class.java)
            intent.putExtra(PlayData.VALUE, preferences.watched)
            this.startActivity(intent)
        }
    }

    private fun updatePlaylist(useCache: Boolean) {
        setLoadingPlaylist(true)
        binding.catAdapter?.clear()
        val playlistSet = Playlist()

        SourcesReader().set(preferences.sources, object : SourcesReader.Result {
            override fun onError(source: String, error: String) {
                val snackbar = Snackbar.make(binding.root, "[${error.uppercase()}] $source", Snackbar.LENGTH_INDEFINITE)
                snackbar.setAction(android.R.string.ok) { snackbar.dismiss() }
                snackbar.show()
            }

            override fun onResponse(playlist: Playlist?) {
                if (playlist != null) playlistSet.mergeWith(playlist)
                else Toast.makeText(applicationContext, R.string.playlist_cant_be_parsed, Toast.LENGTH_SHORT).show()
            }

            override fun onFinish() {
                if (!playlistSet.isCategoriesEmpty()) setPlaylistToAdapter(playlistSet)
                else showAlertPlaylistError(getString(R.string.null_playlist))
            }
        }).process(useCache)
    }

    private fun showAlertPlaylistError(message: String?) {
        val alert = AlertDialog.Builder(this).apply {
            setTitle(R.string.alert_title_playlist_error)
            setMessage(message)
            setCancelable(false)
            setNeutralButton(R.string.settings) { _, _ -> openSettings() }
            setPositiveButton(R.string.dialog_retry) { _, _ -> updatePlaylist(true) }
        }
        val cache = helper.readCache()
        if (cache != null) {
            alert.setNegativeButton(R.string.dialog_cached) { _, _ -> setPlaylistToAdapter(cache) }
        }
        alert.create().show()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) window.setFullScreenFlags()
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_MENU,
            KeyEvent.KEYCODE_SETTINGS -> openSettings()
            KeyEvent.KEYCODE_SEARCH   -> openSearch()
            else -> return super.onKeyUp(keyCode, event)
        }
        return true
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // ── TV REMOTE: tangani navigasi topbar ────────────────────
        if (tvMainRemote.dispatchKeyEvent(event)) return true

        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)

        val keyCode = event.keyCode
        val focus   = currentFocus

        when (keyCode) {

            // ── OK / ENTER ────────────────────────────────────────
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (isDropdownOpen) return super.dispatchKeyEvent(event)
                focus?.performClick()
                return true
            }

            // ── DPAD RIGHT: sidebar → channel grid ────────────────
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (isDropdownOpen) return true
                if (focus != null && isViewInSidebar(focus)) {
                    moveToChannelGrid()
                    return true
                }
                return super.dispatchKeyEvent(event)
            }

            // ── DPAD LEFT: channel grid → sidebar ─────────────────
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (isDropdownOpen) { closeDropdown(); return true }
                if (focus != null && !isViewInSidebar(focus) && !isViewInTopbar(focus)) {
                    moveToSidebar()
                    return true
                }
                return super.dispatchKeyEvent(event)
            }

            // ── DPAD UP ───────────────────────────────────────────
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (isDropdownOpen) return super.dispatchKeyEvent(event)
                if (focus != null && isViewInSidebar(focus)) {
                    // Cek apakah fokus ada di item pertama sidebar
                    val lm = binding.rvSidebar.layoutManager
                        as? androidx.recyclerview.widget.LinearLayoutManager
                    val itemView = getRecyclerViewItemForView(binding.rvSidebar, focus)
                    val pos = if (itemView != null)
                        binding.rvSidebar.getChildAdapterPosition(itemView) else -1
                    if (pos == 0) {
                        // Naik ke dropdown header atau topbar
                        val header = binding.layoutDropdownHeader
                        if (header != null && header.visibility == View.VISIBLE) {
                            header.requestFocus()
                        } else {
                            binding.buttonSearch.requestFocus()
                        }
                        return true
                    }
                }
                return super.dispatchKeyEvent(event)
            }

            // ── DPAD DOWN ─────────────────────────────────────────
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (isDropdownOpen) return super.dispatchKeyEvent(event)
                if (focus == binding.layoutDropdownHeader || (focus != null && isViewInTopbar(focus))) {
                    moveToSidebar()
                    return true
                }
                return super.dispatchKeyEvent(event)
            }

            // ── BACK ──────────────────────────────────────────────
            KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_ESCAPE -> {
                if (isDropdownOpen) { closeDropdown(); return true }
                onBackPressed()
                return true
            }

            // ── Menu / Settings / Search ──────────────────────────
            KeyEvent.KEYCODE_MENU,
            KeyEvent.KEYCODE_SETTINGS -> { openSettings(); return true }
            KeyEvent.KEYCODE_SEARCH   -> { openSearch();   return true }
        }

        return super.dispatchKeyEvent(event)
    }

    // ── Helper: pindah fokus ke channel grid ──────────────────────
    private fun moveToChannelGrid() {
        val rv = binding.rvCategory
        rv.post {
            rv.requestFocus()
            // Cari RecyclerView channel pertama yang visible
            val catRv = rv.findChildRecyclerView()
            if (catRv != null) {
                catRv.requestFocus()
                catRv.getChildAt(0)?.requestFocus()
            } else {
                rv.getChildAt(0)?.requestFocus()
            }
        }
    }

    // ── Helper: pindah fokus ke sidebar ──────────────────────────
    private fun moveToSidebar() {
        binding.rvSidebar.post {
            binding.rvSidebar.scrollToPosition(0)
            binding.rvSidebar.requestFocus()
            binding.rvSidebar.getChildAt(0)?.requestFocus()
        }
    }

    // ── Helper: cari RecyclerView pertama di dalam ViewGroup ─────
    private fun android.view.ViewGroup.findChildRecyclerView():
            androidx.recyclerview.widget.RecyclerView? {
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child is androidx.recyclerview.widget.RecyclerView) return child
            if (child is android.view.ViewGroup) {
                val found = child.findChildRecyclerView()
                if (found != null) return found
            }
        }
        return null
    }

    // ── Helper: dapatkan item RecyclerView langsung dari focused view ─
    private fun getRecyclerViewItemForView(
        rv: androidx.recyclerview.widget.RecyclerView,
        view: android.view.View
    ): android.view.View? {
        var cur: android.view.View = view
        while (cur.parent != null) {
            if (cur.parent == rv) return cur
            cur = (cur.parent as? android.view.View) ?: return null
        }
        return null
    }



    private fun isViewInSidebar(view: android.view.View): Boolean {
        var parent = view.parent
        while (parent != null) {
            if (parent == binding.sidebar) return true
            parent = (parent as? android.view.View)?.parent
        }
        return false
    }

    // ── TV REMOTE helper: cek apakah fokus ada di topbar (Search/Refresh/Settings/Exit)
    private fun isViewInTopbar(view: android.view.View): Boolean {
        return view == binding.buttonSearch ||
               view == binding.buttonRefresh ||
               view == binding.buttonSettings ||
               view == binding.buttonExit
    }

    override fun onBackPressed() {
        if (isTelevision || doubleBackToExitPressedOnce) {
            super.onBackPressed()
            finish()
            return
        }
        doubleBackToExitPressedOnce = true
        Toast.makeText(this, getString(R.string.press_back_twice_exit_app), Toast.LENGTH_SHORT).show()
        Handler(Looper.getMainLooper()).postDelayed({ doubleBackToExitPressedOnce = false }, 2000)
    }

    override fun onDestroy() {
        clockHandler.removeCallbacks(clockRunnable)
        LocalBroadcastManager.getInstance(this).unregisterReceiver(broadcastReceiver)
        super.onDestroy()
    }

    private fun startClock() {
        clockHandler.post(clockRunnable)
    }

    @SuppressLint("DefaultLocale")
    private fun updateClock() {
        val now = Calendar.getInstance()
        val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
        val dayFormat = SimpleDateFormat("EEE, dd MMM", Locale.getDefault())
        binding.textClock?.text = timeFormat.format(now.time)
        binding.textDate?.text = dayFormat.format(now.time)
    }

    private fun openSettings() {
        SettingDialog().show(supportFragmentManager.beginTransaction(), null)
    }

    private fun openSearch() {
        SearchDialog().show(supportFragmentManager.beginTransaction(), null)
    }

    @SuppressLint("DefaultLocale")
    private fun setupResolutionButtons() {
        val btns = listOf(
            binding.btn360, binding.btn720, binding.btn1080, binding.btn4k, binding.btnAuto
        )
        val maxBitrates = listOf(800_000, 2_500_000, 5_000_000, 20_000_000, Int.MAX_VALUE)
        val maxHeights  = listOf(360, 720, 1080, 2160, Int.MAX_VALUE)

        // Restore saved selection (default 720p = index 1)
        val savedIdx = preferences.resolutionIndex
        btns.forEachIndexed { i, btn ->
            if (btn != null)
                TextViewCompat.setTextAppearance(btn,
                    if (i == savedIdx) R.style.ResBtnActive else R.style.ResBtn)
        }

        btns.forEachIndexed { idx, btn ->
            btn?.setOnClickListener {
                preferences.resolutionIndex = idx
                btns.forEachIndexed { i, b ->
                    if (b != null)
                        TextViewCompat.setTextAppearance(b,
                            if (i == idx) R.style.ResBtnActive else R.style.ResBtn)
                }
                // Broadcast to PlayerActivity if running
                LocalBroadcastManager.getInstance(this).sendBroadcast(
                    Intent(PlayerActivity.PLAYER_CALLBACK)
                        .putExtra(PlayerActivity.PLAYER_CALLBACK, PlayerActivity.RETRY_PLAYBACK)
                )
            }
        }
    }
}
