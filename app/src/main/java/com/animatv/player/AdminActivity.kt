package com.animatv.player

import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import com.animatv.player.extra.AdminManager
import com.animatv.player.extra.CategoryOrderManager
import com.animatv.player.extra.LicenseManager
import com.animatv.player.extra.MenuManager
import com.animatv.player.extra.OfflineCache
import com.animatv.player.extra.Preferences

class AdminActivity : AppCompatActivity() {

    private val prefs = Preferences()

    // Feature names untuk UI
    private val featureLabels = mapOf(
        "toggle_sleep_timer"       to "Sleep Timer",
        "toggle_speed"             to "Playback Speed",
        "toggle_gesture"           to "Gesture Control",
        "toggle_double_tap"        to "Double Tap Rewind/Forward",
        "toggle_auto_quality"      to "Auto Quality",
        "toggle_mini_channel"      to "Mini Channel Panel",
        "toggle_anime_bg"          to "Anime Background",
        "toggle_anime_quote"       to "Quote of the Day",
        "toggle_sakura"            to "Sakura Effect",
        "toggle_anime_guide"       to "Anime Character Guide",
        "toggle_continue_watching" to "Continue Watching",
        "toggle_recently_watched"  to "Recently Watched",
        "toggle_channel_shortcut"  to "Channel Shortcut",
        "toggle_live_now"          to "Live Now Banner",
    )

    private val featureKeys = mapOf(
        "toggle_sleep_timer"       to "sleep_timer",
        "toggle_speed"             to "playback_speed",
        "toggle_gesture"           to "gesture_control",
        "toggle_double_tap"        to "double_tap",
        "toggle_auto_quality"      to "auto_quality",
        "toggle_mini_channel"      to "mini_channel",
        "toggle_anime_bg"          to "anime_background",
        "toggle_anime_quote"       to "anime_quote",
        "toggle_sakura"            to "sakura_effect",
        "toggle_anime_guide"       to "anime_guide",
        "toggle_continue_watching" to "continue_watching",
        "toggle_recently_watched"  to "recently_watched",
        "toggle_channel_shortcut"  to "channel_shortcut",
        "toggle_live_now"          to "live_now_banner",
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        setContentView(R.layout.activity_admin)

        // Cek admin auth
        if (!AdminManager.isAdminUnlocked) {
            finish()
            return
        }

        setupHeader()
        setupCategoryOrder()
        setupMenuManagement()
        setupFeatureToggles()
        setupRemoteConfig()
        setupAnnouncement()
        setupMaintenance()
        setupPlaylistOverride()
        setupDangerZone()
        setupLicenseManagement()
    }

    // ============================================================
    // KELOLA URUTAN KATEGORI SIDEBAR
    // ============================================================
    // ============================================================
    // KELOLA MENU DROPDOWN SIDEBAR
    // ============================================================
    private fun setupMenuManagement() {
        findViewById<Button>(R.id.btn_manage_menus)?.setOnClickListener {
            showMenuManagementDialog()
        }
        findViewById<Button>(R.id.btn_reset_menus)?.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Reset Menu")
                .setMessage("Hapus semua override menu? App akan pakai menu dari channels.json.")
                .setPositiveButton("Reset") { _, _ ->
                    MenuManager.clearAdminOverride()
                    toast("Menu direset! Restart app untuk melihat perubahan.")
                }
                .setNegativeButton("Batal", null)
                .show()
        }
    }

    private fun showMenuManagementDialog() {
        val currentMenus = MenuManager.getMenus()
        val allCats = com.animatv.player.model.Playlist.cached.categories
            .map { it.name ?: "" }.filter { it.isNotBlank() }

        val scrollView = android.widget.ScrollView(this).apply {
            setPadding(8, 8, 8, 8)
        }
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(40, 24, 40, 24)
        }

        // Info
        val info = TextView(this).apply {
            text = "Menu saat ini dari channels.json:\n(Tambah field \"menu\":\"id_menu\" di setiap channel)\n\nContoh:\n\"menu\": \"sport\" → masuk menu SPORT\n\"menu\": \"live_event\" → masuk menu LIVE EVENT\n\nMenu yang terdeteksi:"
            setTextColor(0xFFCCCCCC.toInt())
            textSize = 12f
            setPadding(0, 0, 0, 16)
        }
        container.addView(info)

        // Tampilkan menu yang ada
        if (currentMenus.isEmpty()) {
            val noMenu = TextView(this).apply {
                text = "Belum ada menu. Tambahkan field \"menu\" di channels.json."
                setTextColor(0xFF888888.toInt())
                textSize = 12f
                setPadding(0, 0, 0, 16)
            }
            container.addView(noMenu)
        } else {
            currentMenus.forEach { menu ->
                val menuInfo = TextView(this).apply {
                    text = "• ${menu.label} (id: ${menu.id})\n  Sub-kategori: ${menu.subCategories.joinToString(", ")}"
                    setTextColor(0xFFFFFFFF.toInt())
                    textSize = 12f
                    setPadding(0, 4, 0, 8)
                }
                container.addView(menuInfo)
            }
        }

        // Divider
        container.addView(View(this).apply {
            setBackgroundColor(0x44FFFFFF)
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 1).apply { topMargin = 16; bottomMargin = 16 }
        })

        // Input tambah menu baru
        val labelAdd = TextView(this).apply {
            text = "Tambah / Override Menu Baru:"
            setTextColor(0xFFE91E8C.toInt())
            textSize = 13f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, 8)
        }
        container.addView(labelAdd)

        val etMenuId = EditText(this).apply {
            hint = "ID menu (contoh: sport)"
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFF888888.toInt())
            textSize = 12f
            setPadding(16, 12, 16, 12)
            background = androidx.core.content.ContextCompat.getDrawable(
                this@AdminActivity, R.drawable.search_bg)
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 8 }
        }
        container.addView(etMenuId)

        val etMenuLabel = EditText(this).apply {
            hint = "Label tampil (contoh: SPORT)"
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFF888888.toInt())
            textSize = 12f
            setPadding(16, 12, 16, 12)
            background = androidx.core.content.ContextCompat.getDrawable(
                this@AdminActivity, R.drawable.search_bg)
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 8 }
        }
        container.addView(etMenuLabel)

        val etSubCats = EditText(this).apply {
            hint = "Sub-kategori (pisah koma)\nContoh: CHANNEL CLUB BOLA,ALL SPORTS,CHANNEL SPORTS INDO"
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFF888888.toInt())
            textSize = 12f
            setPadding(16, 12, 16, 12)
            minLines = 3
            isSingleLine = false
            background = androidx.core.content.ContextCompat.getDrawable(
                this@AdminActivity, R.drawable.search_bg)
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 8 }
        }
        container.addView(etSubCats)

        // Hint kategori yang tersedia
        val labelAvail = TextView(this).apply {
            text = "Kategori tersedia:\n${allCats.joinToString(", ")}"
            setTextColor(0xFF888888.toInt())
            textSize = 11f
            setPadding(0, 0, 0, 8)
        }
        container.addView(labelAvail)

        scrollView.addView(container)

        AlertDialog.Builder(this)
            .setTitle("Kelola Menu Sidebar")
            .setView(scrollView)
            .setPositiveButton("Tambah/Update Menu") { _, _ ->
                val menuId = etMenuId.text.toString().trim().lowercase()
                val menuLabel = etMenuLabel.text.toString().trim().uppercase()
                val subCatsText = etSubCats.text.toString().trim()

                if (menuId.isBlank() || menuLabel.isBlank()) {
                    toast("ID dan Label menu tidak boleh kosong!")
                    return@setPositiveButton
                }

                val subCats = subCatsText.split(",")
                    .map { it.trim() }.filter { it.isNotBlank() }

                // Tambah ke menu yang ada atau buat baru
                val updatedMenus = currentMenus.toMutableList()
                val existing = updatedMenus.indexOfFirst { it.id == menuId }
                val newMenu = com.animatv.player.model.MenuConfig(
                    id = menuId,
                    label = menuLabel,
                    subCategories = subCats.toMutableList()
                )

                if (existing >= 0) updatedMenus[existing] = newMenu
                else updatedMenus.add(newMenu)

                MenuManager.saveAdminMenus(updatedMenus)

                // Update mapping cat -> menuId
                val catMap = mutableMapOf<String, String>()
                updatedMenus.forEach { menu ->
                    menu.subCategories.forEach { cat ->
                        catMap[cat.lowercase().trim()] = menu.id
                    }
                }
                MenuManager.saveAdminCatMap(catMap)

                toast("Menu \"$menuLabel\" disimpan!\nSub-kategori: ${subCats.joinToString(", ")}\n\nRestart app untuk melihat perubahan.")
            }
            .setNegativeButton("Tutup", null)
            .create()
            .show()
    }

    private fun setupCategoryOrder() {
        // Tombol lihat/edit urutan kategori
        findViewById<Button>(R.id.btn_manage_categories)?.setOnClickListener {
            showCategoryOrderDialog()
        }
        // Tombol reset urutan ke default
        findViewById<Button>(R.id.btn_reset_categories)?.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Reset Urutan Kategori")
                .setMessage("Urutan kategori akan dikembalikan ke default (sesuai channels.json).")
                .setPositiveButton("Reset") { _, _ ->
                    CategoryOrderManager.clearAll()
                    toast("Urutan direset! Restart app untuk melihat perubahan.")
                }
                .setNegativeButton("Batal", null)
                .show()
        }
    }

    private fun showCategoryOrderDialog() {
        val savedOrder = CategoryOrderManager.getOrder().toMutableList()
        val hidden = CategoryOrderManager.getHidden().toMutableSet()

        val allCats = com.animatv.player.model.Playlist.cached.categories
            .map { it.name ?: "" }
            .filter { it.isNotBlank() }
            .distinct()

        val displayList = mutableListOf<String>()
        for (name in savedOrder) { if (allCats.contains(name)) displayList.add(name) }
        for (name in allCats) { if (!displayList.contains(name)) displayList.add(name) }

        if (displayList.isEmpty()) {
            toast("Tidak ada kategori. Buka app dulu untuk load channel.")
            return
        }

        // Buat custom scrollable view
        val scrollView = android.widget.ScrollView(this).apply {
            setPadding(8, 8, 8, 8)
        }

        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(40, 24, 40, 24)
        }

        // Label urutan saat ini
        val labelUrutan = TextView(this).apply {
            text = "Urutan kategori saat ini:"
            setTextColor(0xFFE91E8C.toInt())
            textSize = 13f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, 8)
        }
        container.addView(labelUrutan)

        // Tampilkan list kategori
        displayList.forEachIndexed { i, name ->
            val isHidden = hidden.contains(name)
            val tv = TextView(this).apply {
                text = "${i+1}. $name${if (isHidden) "  [TERSEMBUNYI]" else ""}"
                setTextColor(if (isHidden) 0xFF888888.toInt() else 0xFFFFFFFF.toInt())
                textSize = 13f
                setPadding(0, 4, 0, 4)
            }
            container.addView(tv)
        }

        // Spacer
        container.addView(View(this).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 24)
        })

        // Label input
        val labelInput = TextView(this).apply {
            text = "Masukkan urutan baru (pisah dengan koma):"
            setTextColor(0xFFCCCCCC.toInt())
            textSize = 12f
            setPadding(0, 0, 0, 8)
        }
        container.addView(labelInput)

        // Input field
        val input = EditText(this).apply {
            hint = "NASIONAL,LIVE EVENT,KIDS,ANIME"
            setText(displayList.joinToString(","))
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFF888888.toInt())
            textSize = 12f
            setPadding(16, 12, 16, 12)
            minLines = 3
            isSingleLine = false
            background = androidx.core.content.ContextCompat.getDrawable(
                this@AdminActivity, R.drawable.search_bg)
        }
        container.addView(input)

        // Tombol Sembunyikan Kategori
        val btnHide = Button(this).apply {
            text = "Sembunyikan Kategori"
            setBackgroundColor(0xFF444444.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 16 }
        }
        container.addView(btnHide)

        scrollView.addView(container)

        val dialog = AlertDialog.Builder(this)
            .setTitle("Kelola Urutan Kategori")
            .setView(scrollView)
            .setPositiveButton("Simpan") { _, _ ->
                val newOrder = input.text.toString()
                    .split(",")
                    .map { it.trim() }
                    .filter { it.isNotBlank() }

                if (newOrder.isEmpty()) {
                    toast("Urutan tidak valid!")
                    return@setPositiveButton
                }

                CategoryOrderManager.saveOrder(newOrder)
                toast("Urutan disimpan!\nRestart app untuk melihat perubahan.")
            }
            .setNegativeButton("Batal", null)
            .create()

        btnHide.setOnClickListener {
            dialog.dismiss()
            showHideCategoryDialog(displayList, hidden)
        }

        dialog.show()
    }

    private fun showHideCategoryDialog(cats: List<String>, hidden: MutableSet<String>) {
        val items = cats.toTypedArray()
        val checked = cats.map { hidden.contains(it) }.toBooleanArray()

        AlertDialog.Builder(this)
            .setTitle("Sembunyikan Kategori")
            .setMultiChoiceItems(items, checked) { _, which, isChecked ->
                if (isChecked) hidden.add(cats[which])
                else hidden.remove(cats[which])
            }
            .setPositiveButton("Simpan") { _, _ ->
                CategoryOrderManager.saveHidden(hidden)
                toast("Kategori tersembunyi disimpan!\nRestart app untuk melihat perubahan.")
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun setupHeader() {
        val config = AdminManager.getConfig()
        findViewById<TextView>(R.id.txt_admin_version)?.text =
            "Config v${config.configVersion}"

        findViewById<Button>(R.id.btn_admin_logout)?.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Keluar Admin Mode")
                .setMessage("Yakin mau keluar dari Admin Panel?")
                .setPositiveButton("Keluar") { _, _ ->
                    AdminManager.lockAdmin()
                    finish()
                }
                .setNegativeButton("Batal", null)
                .show()
        }
    }

    private fun setupFeatureToggles() {
        val config = AdminManager.getConfig()

        // Setup tiap toggle
        featureLabels.forEach { (viewId, label) ->
            val resId = resources.getIdentifier(viewId, "id", packageName)
            val toggleView = findViewById<View>(resId) ?: return@forEach
            val featureKey = featureKeys[viewId] ?: return@forEach

            // Set label
            toggleView.findViewById<TextView>(R.id.txt_feature_name)?.text = label

            // Set state dari config
            val switch = toggleView.findViewById<SwitchCompat>(R.id.switch_feature)
            switch?.isChecked = AdminManager.isFeatureEnabled(featureKey)
        }

        // Tombol simpan
        findViewById<Button>(R.id.btn_save_features)?.setOnClickListener {
            saveFeatureChanges()
        }
    }

    private fun saveFeatureChanges() {
        // Baca semua toggle state dan buat JSON baru untuk di-push ke GitHub
        val sb = StringBuilder("{\n")
        sb.append("  \"configVersion\": ${AdminManager.getConfig().configVersion + 1},\n")
        sb.append("  \"lastUpdated\": \"${java.text.SimpleDateFormat("yyyy-MM-dd").format(java.util.Date())}\",\n")

        featureKeys.forEach { (viewId, featureKey) ->
            val resId = resources.getIdentifier(viewId, "id", packageName)
            val toggleView = findViewById<View>(resId) ?: return@forEach
            val isEnabled = toggleView.findViewById<SwitchCompat>(R.id.switch_feature)?.isChecked ?: true

            val jsonKey = when (featureKey) {
                "sleep_timer" -> "featureSleepTimer"
                "playback_speed" -> "featurePlaybackSpeed"
                "gesture_control" -> "featureGestureControl"
                "double_tap" -> "featureDoubleTap"
                "auto_quality" -> "featureAutoQuality"
                "mini_channel" -> "featureMiniChannelPanel"
                "anime_background" -> "featureAnimeBackground"
                "anime_quote" -> "featureAnimeQuote"
                "sakura_effect" -> "featureSakuraEffect"
                "anime_guide" -> "featureAnimeGuide"
                "continue_watching" -> "featureContinueWatching"
                "recently_watched" -> "featureRecentlyWatched"
                "channel_shortcut" -> "featureChannelShortcut"
                "live_now_banner" -> "featureLiveNowBanner"
                else -> featureKey
            }
            sb.append("  \"$jsonKey\": $isEnabled,\n")
        }
        sb.append("  \"maintenanceMode\": false\n}")

        // Tampilkan JSON yang perlu di-update ke GitHub
        AlertDialog.Builder(this)
            .setTitle("Update Config")
            .setMessage("Salin JSON ini ke file config/features.json di GitHub:\n\n${sb}")
            .setPositiveButton("Salin") { _, _ ->
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("config", sb.toString()))
                toast("JSON berhasil disalin!")
                // Clear cache supaya app ambil config baru
                AdminManager.clearConfigCache()
            }
            .setNegativeButton("Tutup", null)
            .show()
    }

    private fun setupRemoteConfig() {
        val statusTxt = findViewById<TextView>(R.id.txt_config_status)

        val config = AdminManager.getConfig()
        statusTxt?.text = "Config v${config.configVersion} | Update: ${config.lastUpdated}"

        findViewById<Button>(R.id.btn_fetch_config)?.setOnClickListener {
            statusTxt?.text = "Mengambil config dari GitHub..."
            AdminManager.clearConfigCache()
            AdminManager.fetchConfigAsync { newConfig ->
                statusTxt?.text = "Config v${newConfig.configVersion} | Update: ${newConfig.lastUpdated} [OK]"
                toast("Config berhasil diperbarui!")
                setupFeatureToggles() // refresh toggles
            }
        }

        findViewById<Button>(R.id.btn_clear_cache)?.setOnClickListener {
            AdminManager.clearConfigCache()
            toast("Cache config dihapus")
            statusTxt?.text = "Cache dihapus - akan fetch ulang saat dibuka"
        }
    }

    private fun setupAnnouncement() {
        val config = AdminManager.getConfig()
        val etAnnouncement = findViewById<EditText>(R.id.et_announcement)
        val switchAnn = findViewById<SwitchCompat>(R.id.switch_announcement)

        etAnnouncement?.setText(config.appAnnouncement)
        switchAnn?.isChecked = config.announcementEnabled

        findViewById<Button>(R.id.btn_save_announcement)?.setOnClickListener {
            val msg = etAnnouncement?.text?.toString() ?: ""
            val enabled = switchAnn?.isChecked ?: false
            toast("Pengumuman disimpan! Update config/features.json di GitHub dengan:\n\"appAnnouncement\": \"$msg\",\n\"announcementEnabled\": $enabled")
        }
    }

    private fun setupMaintenance() {
        val config = AdminManager.getConfig()
        val switchMaint = findViewById<SwitchCompat>(R.id.switch_maintenance)
        val etMsg = findViewById<EditText>(R.id.et_maintenance_msg)

        switchMaint?.isChecked = config.maintenanceMode
        etMsg?.setText(config.maintenanceMessage)

        findViewById<Button>(R.id.btn_save_maintenance)?.setOnClickListener {
            val isOn = switchMaint?.isChecked ?: false
            val msg = etMsg?.text?.toString() ?: ""

            if (isOn) {
                AlertDialog.Builder(this)
                    .setTitle("Aktifkan Maintenance?")
                    .setMessage("Semua user tidak bisa streaming sampai maintenance dimatikan!")
                    .setPositiveButton("Aktifkan") { _, _ ->
                        toast("Update config/features.json:\n\"maintenanceMode\": true,\n\"maintenanceMessage\": \"$msg\"")
                    }
                    .setNegativeButton("Batal") { _, _ -> switchMaint?.isChecked = false }
                    .show()
            } else {
                toast("Maintenance OFF. Update config/features.json:\n\"maintenanceMode\": false")
            }
        }
    }

    private fun setupPlaylistOverride() {
        val config = AdminManager.getConfig()
        val etPlaylist = findViewById<EditText>(R.id.et_playlist_url)
        val etBackup = findViewById<EditText>(R.id.et_backup_url)

        etPlaylist?.setText(config.playlistUrl)
        etBackup?.setText(config.backupPlaylistUrl)

        findViewById<Button>(R.id.btn_save_playlist)?.setOnClickListener {
            val url = etPlaylist?.text?.toString() ?: ""
            val backup = etBackup?.text?.toString() ?: ""
            // CATATAN: field ini sekarang BENAR-BENAR dipakai (lihat
            // Preferences.kt) untuk override source 1 & 2 secara remote,
            // bukan cuma dekorasi. Tapi mengubahnya harus lewat
            // config/features.json di GitHub -- tombol ini sengaja TIDAK
            // menulis ke GitHub langsung dari dalam APK, supaya token
            // GitHub tidak perlu disimpan di aplikasi (itu akan jadi
            // celah keamanan kalau APK dibongkar orang lain). Pakai Panel
            // Channels (web app terpisah) untuk mengubah nilai ini.
            toast("Field ini sekarang aktif dipakai aplikasi.\nUbah nilainya lewat Panel Channels (web), bukan dari sini, supaya tidak perlu simpan token GitHub di dalam APK.\n\nplaylistUrl: $url\nbackupPlaylistUrl: $backup")
        }
    }

    private fun setupDangerZone() {
        findViewById<Button>(R.id.btn_reset_app)?.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Reset Semua Setting?")
                .setMessage("Ini akan menghapus semua setting, favorit, dan riwayat tontonan semua user!")
                .setPositiveButton("Reset") { _, _ ->
                    // Reset SharedPreferences
                    val sp = getSharedPreferences("animatv_admin", MODE_PRIVATE)
                    sp.edit().clear().apply()
                    toast("Setting admin di-reset")
                }
                .setNegativeButton("Batal", null)
                .show()
        }

        findViewById<Button>(R.id.btn_force_update)?.setOnClickListener {
            AdminManager.clearConfigCache()
            toast("Cache dihapus - app akan fetch playlist terbaru")
        }
    }


    private fun setupLicenseManagement() {
        // Load saved token
        val savedToken = getSharedPreferences("animatv_admin", MODE_PRIVATE)
            .getString("github_token", "") ?: ""
        findViewById<android.widget.EditText>(R.id.et_github_token)?.setText(savedToken)

        // Generate kode lisensi baru
        findViewById<android.widget.Button>(R.id.btn_generate_key)?.setOnClickListener {
            val resultTxt = findViewById<android.widget.TextView>(R.id.txt_generated_key)
            resultTxt?.text = "Generating..."
            resultTxt?.visibility = android.view.View.VISIBLE

            // Generate kode OFFLINE
            val buyerName = findViewById<android.widget.EditText>(R.id.et_buyer_name)
                ?.text?.toString()?.trim() ?: "User"
            val newCode = LicenseManager.generateLicenseKey()
            resultTxt?.text = newCode
            resultTxt?.visibility = android.view.View.VISIBLE
            // Simpan ke history
            saveLicenseToHistory(newCode, buyerName)
            // Copy otomatis ke clipboard
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("license", newCode))
            toast("Kode: $newCode\nDisalin! Kirim ke: $buyerName")
        }

        // Lihat semua lisensi
        // Lihat semua kode yang pernah di-generate
        findViewById<android.widget.Button>(R.id.btn_view_licenses)?.setOnClickListener {
            val history = getLicenseHistory()
            val msg = if (history.isEmpty()) {
                "Belum ada kode yang pernah di-generate."
            } else {
                history.joinToString("\n\n")
            }
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Riwayat Kode (${history.size})")
                .setMessage(msg)
                .setPositiveButton("Salin Semua") { _, _ ->
                    val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("history", msg))
                    toast("Riwayat disalin!")
                }
                .setNegativeButton("Tutup", null)
                .show()
        }

        // Cek validitas kode
        findViewById<android.widget.Button>(R.id.btn_revoke_key)?.setOnClickListener {
            val code = findViewById<android.widget.EditText>(R.id.et_revoke_code)
                ?.text?.toString()?.trim()?.uppercase() ?: ""

            if (code.isBlank()) {
                toast("Masukkan kode format ANIM-XXXX-XXXX-XXXX")
                return@setOnClickListener
            }

            if (!code.startsWith("ANIM-")) {
                toast("Format salah! Harus ANIM-XXXX-XXXX-XXXX")
                return@setOnClickListener
            }

            val isValid = LicenseManager.isValidCode(code)
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Cek Kode")
                .setMessage(
                    if (isValid) "Kode: $code\n\nSTATUS: VALID\nKode ini resmi dan bisa digunakan."
                    else "Kode: $code\n\nSTATUS: TIDAK VALID\nKode ini tidak dikenal atau salah."
                )
                .setPositiveButton("OK", null)
                .show()
        }
    }

    // Simpan kode ke history
    private fun saveLicenseToHistory(code: String, buyerName: String) {
        val prefs = getSharedPreferences("animatv_license_history", MODE_PRIVATE)
        val date = java.text.SimpleDateFormat("dd/MM/yyyy HH:mm").format(java.util.Date())
        val entry = "$code | $buyerName | $date"
        val existing = prefs.getStringSet("history", mutableSetOf())?.toMutableSet() ?: mutableSetOf()
        existing.add(entry)
        prefs.edit().putStringSet("history", existing).apply()
    }

    // Ambil history kode
    private fun getLicenseHistory(): List<String> {
        val prefs = getSharedPreferences("animatv_license_history", MODE_PRIVATE)
        return prefs.getStringSet("history", emptySet())
            ?.sortedDescending() ?: emptyList()
    }

    private fun saveAndGetToken(): String {
        val token = findViewById<android.widget.EditText>(R.id.et_github_token)
            ?.text?.toString()?.trim() ?: ""
        if (token.isNotBlank()) {
            getSharedPreferences("animatv_admin", MODE_PRIVATE)
                .edit().putString("github_token", token).apply()
        }
        return token
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }
}
