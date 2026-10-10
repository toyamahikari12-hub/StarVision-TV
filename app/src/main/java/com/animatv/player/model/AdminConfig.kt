package com.animatv.player.model

// Satu entri sumber playlist tambahan. "label" cuma buat memudahkan kamu
// mengenali sumber ini di Panel Channels, tidak dipakai aplikasi.
data class ExtraSourceConfig(
    val url: String = "",
    val active: Boolean = true,
    val label: String = ""
)

data class AdminConfig(
    // Feature Flags - toggle fitur ON/OFF
    val featureSleepTimer: Boolean = true,
    val featurePlaybackSpeed: Boolean = true,
    val featureGestureControl: Boolean = true,
    val featureDoubleTap: Boolean = true,
    val featureAutoQuality: Boolean = true,
    val featureMiniChannelPanel: Boolean = true,
    val featureAnimeBackground: Boolean = true,
    val featureAnimeQuote: Boolean = true,
    val featureSakuraEffect: Boolean = true,
    val featureAnimeGuide: Boolean = true,

    // Home Screen Modern
    val featureContinueWatching: Boolean = true,
    val featureRecentlyWatched: Boolean = true,
    val featureChannelShortcut: Boolean = true,
    val featureLiveNowBanner: Boolean = true,

    // App Config
    val appAnnouncement: String = "",       // Pengumuman dari admin
    val announcementEnabled: Boolean = false,
    val maintenanceMode: Boolean = false,   // Matikan semua stream
    val maintenanceMessage: String = "Sedang maintenance, coba lagi nanti.",
    val forceUpdateVersion: Int = 0,        // Paksa update kalau < versi ini

    // Playlist Config
    val playlistUrl: String = "",           // Override URL playlist
    val backupPlaylistUrl: String = "",     // Backup kalau utama down

    // Theme Config  
    val themeColorPrimary: String = "#E91E8C",
    val themeColorAccent: String = "#00BCD4",
    val bgRotatorInterval: Int = 30,        // Detik ganti background

    // Kategori (opsional): kalau diisi di config remote, ini menang atas
    // DEFAULT_ORDER yang tertanam di kode, jadi bisa diubah kapan saja
    // tanpa build ulang -- cukup edit file config JSON di GitHub.
    // Kosongkan (list default) untuk pakai DEFAULT_ORDER di kode.
    val categoryOrder: List<String> = emptyList(),
    val categoryHidden: List<String> = emptyList(),

    // Sumber playlist TAMBAHAN di luar Source 1 (playlistUrl) dan
    // Source 2 (backupPlaylistUrl). Tiap entri aktif (active=true) otomatis
    // ditambahkan sebagai source, berlaku di SEMUA perangkat tanpa build
    // ulang. active=false = disembunyikan sementara (data tetap ada, gampang
    // diaktifkan lagi kalau sumbernya hidup lagi); hapus dari list = permanen.
    val extraSources: List<ExtraSourceConfig> = emptyList(),

    // Matikan/hidupkan Source 1 (channels.json bawaan / override playlistUrl)
    // dan Source 2 (Vision+ bawaan / override backupPlaylistUrl) tanpa perlu
    // sentuh strings.xml maupun build ulang. false = source itu diperlakukan
    // seperti tidak ada sama sekali (channel-channelnya tidak akan muncul).
    val source1Active: Boolean = true,
    val source2Active: Boolean = true,

    // Admin info
    val configVersion: Int = 1,
    val lastUpdated: String = ""
)
