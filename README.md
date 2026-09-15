# AnimeTV 📺✨

Aplikasi TV IPTV modern bertema Anime untuk Android.  
Support **DASH + DRM** (ClearKey & Widevine).

## 🔍 Auto-Deteksi Sistem Streaming

Pemutar sekarang otomatis mengenali sistem/format streaming sebuah channel (HLS, DASH,
SmoothStreaming, RTSP, atau progressive MP4/TS/MKV) langsung dari URL-nya - tidak perlu
menulis kode pemutar baru tiap ada channel dengan sistem streaming baru:

1. **Deteksi awal** (`StreamFormatDetector`) menebak format dari ekstensi/pola URL
   (`.m3u8`, `.mpd`, `.ism`, `rtsp://`, parameter `type=m3u8`/`type=m3u_plus` yang umum
   dipakai panel Xtream Codes, dll).
2. **Fallback ladder otomatis** - kalau tebakan pertama ternyata salah (ExoPlayer gagal
   parse manifest/container-nya), pemutar otomatis mencoba format lain secara berurutan
   (HLS → DASH → SmoothStreaming → Progressive) sampai salah satu berhasil, tanpa perlu
   restart manual.
3. Format yang terbukti berhasil untuk sebuah channel "diingat" selama sesi berjalan,
   supaya reconnect saat live stream putus-nyambung tidak menebak ulang dari awal.
4. Track selector dikonfigurasi lebih toleran (`setAllowVideoMixedMimeTypeAdaptiveness`,
   `setExceedRendererCapabilitiesIfNecessary`, `setEnableDecoderFallback`) supaya channel
   dengan manifest berisi campuran codec (mis. rendition H.264 & HEVC/4K dalam satu
   channel) tetap bisa memilih rendition yang kompatibel dengan perangkat, dan decoder
   hardware yang gagal init otomatis mencoba decoder alternatif di perangkat.
5. Dukungan **RTSP** ditambahkan (`exoplayer-rtsp`) - URL berskema `rtsp://` otomatis
   ditangani tanpa kode tambahan.

**Catatan jujur soal batasannya:** kalau error yang muncul persis "*Media mengandung
track video & audio yang tidak didukung perangkat ini*", itu bukan soal salah tebak
format lagi - itu artinya ExoPlayer BERHASIL baca manifest-nya tapi codec video/audio
di dalam stream itu sendiri (mis. HEVC Main10, AV1, atau audio AC-3/E-AC-3/DTS) memang
tidak punya decoder hardware/software di TV box tsb. Perbaikan di atas membantu kalau
channel punya rendition lain yang kompatibel dalam manifest yang sama, tapi kalau
seluruh stream cuma dikirim dalam satu codec yang memang tidak didukung perangkat,
satu-satunya jalan adalah menambah modul software decoder ExoPlayer (mis.
`extension-ffmpeg` untuk audio) yang harus di-build sendiri dari source dengan NDK -
di luar cakupan patch ini karena butuh proses build native terpisah.

[![Build AnimeTV APK](https://github.com/manakayuuna123-dot/AnimeTV/actions/workflows/build.yml/badge.svg)](https://github.com/manakayuuna123-dot/AnimeTV/actions/workflows/build.yml)

## Fitur Utama

| Fitur | Keterangan |
|-------|-----------|
| 🎌 Tema Anime | UI pink/cyan/purple + gambar anime |
| 📺 DASH + DRM | ClearKey & Widevine support |
| 📋 Sidebar Kategori | Navigasi channel per kategori |
| 🔄 Ganti Channel | Tanpa keluar dari player (swipe/remote) |
| 🎬 Resolusi Nyata | 360p / 720p / 1080p / 4K / Auto |
| 📐 Ukuran Layar | Fit / Fixed Width / Fixed Height / Fill / Zoom |
| ⭐ Favorit | Long-press channel untuk tambah/hapus favorit |
| 🔍 Pencarian | Cari channel dari semua kategori |
| 🎴 Lottie Animation | Loading animation anime-style |
| 🔒 Lock Screen | Kunci kontrol player |
| 📱 PIP Mode | Picture-in-picture support |

## Sumber Channel

Playlist channel diambil dari:
```
https://raw.githubusercontent.com/aurorasekai15-hub/SymphogearTV-Native/main/channels.json
```

## Format channels.json

```json
{
  "channels": [
    {
      "id": 1,
      "name": "Nama Channel",
      "cat": "nasional",
      "url": "https://example.com/stream.mpd",
      "drm": false,
      "logo": "https://example.com/logo.png"
    },
    {
      "id": 2,
      "name": "Channel DRM ClearKey",
      "cat": "custom",
      "url": "https://example.com/protected.mpd",
      "drm": true,
      "drmType": "ClearKey",
      "licUrl": "keyid1:key1,keyid2:key2",
      "logo": ""
    },
    {
      "id": 3,
      "name": "Channel DRM Widevine",
      "cat": "custom",
      "url": "https://example.com/widevine.mpd",
      "drm": true,
      "drmType": "Widevine",
      "licUrl": "https://license-server.com/widevine",
      "logo": ""
    }
  ]
}
```

### Kategori yang Didukung
`nasional` · `berita` · `hiburan` · `olahraga` · `internasional` · `jepang` · `vision` · `indihome` · `custom`

## Build via GitHub Actions

1. Push project ini ke repo GitHub kamu
2. Buka tab **Actions**
3. Workflow **Build AnimeTV APK** otomatis jalan saat push ke `main`
4. Download APK dari **Artifacts** setelah build selesai

## Build Manual

```bash
chmod +x gradlew
./gradlew assembleRelease
```

Output APK: `app/build/outputs/apk/release/AnimeTV_v1.0.apk`

## Teknologi

- **ExoPlayer 2.18.2** — Video playback engine
- **Lottie 6.1.0** — Anime loading animations
- **SpinKit 1.4.0** — Loading spinner animations
- **Glide 4.15.1** — Channel logo loading
- **Shimmer 0.5.0** — Skeleton loading UI
- **OkHttp 4.10.0** — Network HTTP client
- **Gson 2.9.1** — JSON parsing

## Package Info

- **Package ID:** `com.animatv.player`
- **Min SDK:** 21 (Android 5.0)
- **Target SDK:** 33 (Android 13)
- **Gradle:** 7.5.1
- **Kotlin:** 1.7.20
