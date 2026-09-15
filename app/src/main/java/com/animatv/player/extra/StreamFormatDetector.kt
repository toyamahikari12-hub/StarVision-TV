package com.animatv.player.extra

import android.net.Uri
import com.google.android.exoplayer2.util.MimeTypes
import java.util.Locale

/**
 * Deteksi otomatis "sistem" streaming sebuah channel (HLS / DASH / SmoothStreaming /
 * RTSP / Progressive) berdasarkan URL-nya.
 *
 * KENAPA INI DIPERLUKAN
 * ExoPlayer sendiri sebenarnya SUDAH bisa memutar semua sistem streaming di atas asal
 * modul librarynya ada (exoplayer-hls, exoplayer-dash, exoplayer-smoothstreaming,
 * exoplayer-rtsp) - jadi tidak perlu bikin "pemutar baru" tiap ada channel dengan sistem
 * baru. Yang sering meleset justru TEBAKAN format dari URL-nya, terutama untuk panel
 * IPTV yang tidak memakai ekstensi baku (mis. URL Xtream Codes tanpa akhiran .m3u8,
 * atau parameter query seperti ?type=m3u8).
 *
 * Kalau tebakan pertama ternyata salah (ExoPlayer gagal parse manifest/container-nya),
 * [FORMAT_FALLBACK_LADDER] dipakai oleh PlayerActivity untuk mencoba format lain secara
 * berurutan sampai salah satu berhasil - jadi channel dengan sistem streaming yang belum
 * pernah ditemui pun tetap punya kesempatan diputar tanpa perlu update kode.
 */
object StreamFormatDetector {

    /**
     * Urutan MIME type yang dicoba berurutan kalau deteksi awal meleset (dipicu saat
     * ExoPlayer melempar error "manifest/container tidak dikenali").
     * `null` di posisi terakhir = mode progressive: ExoPlayer memakai ekstraktor bawaan
     * yang mengenali isi file (MP4/TS/MKV/FLV/ADTS/dll) langsung dari byte stream-nya,
     * bukan dari ekstensi URL.
     */
    val FORMAT_FALLBACK_LADDER: List<String?> = listOf(
        MimeTypes.APPLICATION_M3U8,
        MimeTypes.APPLICATION_MPD,
        MimeTypes.APPLICATION_SS,
        null
    )

    /**
     * Tebak MIME type dari URL sebuah channel.
     * Return `null` artinya "biarkan ExoPlayer pakai ekstraktor progresif bawaan",
     * yang juga otomatis mengenali banyak format dari isi filenya sendiri.
     */
    fun detect(url: String): String? {
        // RTSP dikenali dari skema URL - ditangani otomatis oleh ExoPlayer
        // (RtspMediaSource) selama modul exoplayer-rtsp ada di classpath.
        if (url.startsWith("rtsp://", ignoreCase = true)) return MimeTypes.APPLICATION_RTSP

        val uri = try { Uri.parse(url) } catch (e: Exception) { null }
        val path = (uri?.path ?: url).lowercase(Locale.getDefault())
        val query = (uri?.query ?: "").lowercase(Locale.getDefault())
        val combined = "$path?$query"

        return when {
            // DASH
            path.endsWith(".mpd") ||
            combined.contains(".mpd?") ||
            combined.contains("format=mpd") ||
            combined.contains("/dash/") -> MimeTypes.APPLICATION_MPD

            // HLS - termasuk pola umum panel IPTV (Xtream Codes dkk) yang mengirim
            // parameter "type=m3u8" / "type=m3u_plus" alih-alih ekstensi di path
            path.endsWith(".m3u8") ||
            path.endsWith(".m3u") ||
            combined.contains(".m3u8") ||
            combined.contains("type=m3u8") ||
            combined.contains("type=m3u_plus") ||
            combined.contains("output=hls") ||
            combined.contains("format=hls") ||
            combined.contains("/hls/") ||
            combined.contains("chunklist") -> MimeTypes.APPLICATION_M3U8

            // Microsoft SmoothStreaming
            path.endsWith(".ism") ||
            path.contains(".ism/manifest") ||
            path.endsWith(".isml") ||
            combined.contains("smoothstreaming") -> MimeTypes.APPLICATION_SS

            // Tidak ada penanda jelas → biarkan ExoPlayer menebak sendiri dari isi file
            else -> null
        }
    }

    /** Nama yang gampang dibaca untuk keperluan log/debug. */
    fun label(mimeType: String?): String = when (mimeType) {
        MimeTypes.APPLICATION_M3U8 -> "HLS"
        MimeTypes.APPLICATION_MPD -> "DASH"
        MimeTypes.APPLICATION_SS -> "SmoothStreaming"
        MimeTypes.APPLICATION_RTSP -> "RTSP"
        null -> "Progressive/Auto"
        else -> mimeType
    }
}
