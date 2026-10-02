package com.animatv.player.extra

import android.content.Context
import android.content.SharedPreferences
import com.animatv.player.App
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Mengelola urutan dan visibilitas kategori di sidebar.
 * Urutan disimpan di SharedPreferences — bisa diubah dari Admin Panel.
 */
object CategoryOrderManager {

    private const val PREF_NAME = "animatv_category_order"
    private const val KEY_ORDER = "category_order"
    private const val KEY_HIDDEN = "category_hidden"

    private val prefs: SharedPreferences by lazy {
        App.context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Urutan kategori bawaan, ditanam permanen di kode (baked-in).
     * Berlaku otomatis di SEMUA install tanpa perlu diatur lewat Admin
     * Panel di tiap perangkat.
     *
     * Kategori yang disebut di sini akan tampil sesuai urutan list ini
     * (index 0 = paling atas). Kategori lain yang tidak disebutkan akan
     * ditaruh setelahnya, sesuai urutan aslinya dari playlist.
     *
     * Admin Panel tetap bisa mengubah urutan, tapi perubahan itu hanya
     * tersimpan lokal di perangkat itu saja (dipakai untuk uji coba).
     * Untuk mengubah urutan default di SEMUA perangkat, edit list ini
     * lalu build ulang aplikasinya.
     */
    // Kosong = tidak ada override apa pun di kode. Urutan kategori
    // sepenuhnya mengikuti urutan alami di channels.json (lihat
    // SymphogearJsonConverter). Isi list ini HANYA kalau kamu benar-benar
    // mau memaksa urutan tertentu langsung dari kode / build.
    private val DEFAULT_ORDER = emptyList<String>()

    /** Simpan urutan kategori (list nama kategori) — override lokal per perangkat */
    fun saveOrder(orderedNames: List<String>) {
        prefs.edit().putString(KEY_ORDER, Gson().toJson(orderedNames)).apply()
    }

    /**
     * PENTING -- ada DUA mekanisme "urutan kategori" yang beda level:
     *
     *   1. "category_order" (huruf kecil, pakai underscore) di dalam
     *      channels.json -- cuma ngatur urutan kategori DARI SATU SUMBER
     *      itu saja, sebelum digabung dengan sumber lain. Dipakai oleh
     *      SymphogearJsonConverter.
     *
     *   2. "categoryOrder" (huruf besar di tengah, TANPA underscore) di
     *      config/features.json -- inilah yang dibaca fungsi getOrder()
     *      di bawah ini. Ini ngatur urutan FINAL setelah SEMUA sumber
     *      playlist digabung (channels.json, link M3U Vision+, extraSources,
     *      dll). Kalau mau atur posisi kategori yang berasal dari sumber
     *      M3U (contoh: "V+ IONTV", "Dunia Wibu"), WAJIB pakai yang ini,
     *      bukan yang di channels.json -- karena kategori dari M3U tidak
     *      pernah melewati channels.json sama sekali.
     *
     * Kalau ragu kategori dari sumber mana, aman untuk selalu pakai
     * "categoryOrder" di features.json saja untuk SEMUA kategori, karena
     * cakupannya lebih luas (semua sumber, bukan cuma channels.json).
     *
     * Prioritas:
     *   1. Override lokal dari Admin Panel (khusus perangkat itu, buat uji coba)
     *   2. categoryOrder dari remote config (features.json) -- ini yang dipakai
     *      supaya bisa diubah kapan saja tanpa build ulang, berlaku di
     *      SEMUA perangkat begitu config-nya di-refresh
     *   3. DEFAULT_ORDER yang tertanam di kode (fallback kalau remote
     *      config belum sempat diambil / tidak diisi)
     */
    fun getOrder(): List<String> {
        val json = prefs.getString(KEY_ORDER, null)
        if (json != null) {
            try {
                val type = object : TypeToken<List<String>>() {}.type
                val saved: List<String> = Gson().fromJson(json, type)
                if (saved.isNotEmpty()) return saved
            } catch (e: Exception) {
                // abaikan, lanjut cek remote config
            }
        }

        val remoteOrder = AdminManager.getConfig().categoryOrder
        if (remoteOrder.isNotEmpty()) return remoteOrder

        return DEFAULT_ORDER
    }

    /** Simpan daftar kategori yang disembunyikan */
    fun saveHidden(hiddenNames: Set<String>) {
        prefs.edit().putString(KEY_HIDDEN, Gson().toJson(hiddenNames.toList())).apply()
    }

    /**
     * Ambil daftar kategori yang disembunyikan. Prioritas sama seperti
     * getOrder(): override lokal Admin Panel, lalu remote config, baru
     * dianggap tidak ada yang disembunyikan.
     */
    fun getHidden(): Set<String> {
        val json = prefs.getString(KEY_HIDDEN, null)
        if (json != null) {
            try {
                val type = object : TypeToken<List<String>>() {}.type
                val list: List<String> = Gson().fromJson(json, type)
                if (list.isNotEmpty()) return list.toSet()
            } catch (e: Exception) {
                // abaikan, lanjut cek remote config
            }
        }

        val remoteHidden = AdminManager.getConfig().categoryHidden
        if (remoteHidden.isNotEmpty()) return remoteHidden.toSet()

        return emptySet()
    }

    /**
     * Urutkan categories berdasarkan urutan yang tersimpan.
     * Kategori yang tidak ada di urutan tersimpan diletakkan di akhir.
     * Kategori yang disembunyikan tidak ditampilkan.
     */
    fun <T> applySavedOrder(
        categories: List<T>,
        getName: (T) -> String
    ): List<T> {
        val savedOrder = getOrder()
        val hidden = getHidden()

        // Filter kategori yang tidak disembunyikan
        val visible = categories.filter { cat ->
            !hidden.any { it.equals(getName(cat), ignoreCase = true) }
        }

        if (savedOrder.isEmpty()) return visible

        // Urutkan berdasarkan savedOrder
        val ordered = mutableListOf<T>()
        for (name in savedOrder) {
            val found = visible.firstOrNull {
                getName(it).equals(name, ignoreCase = true)
            }
            if (found != null) ordered.add(found)
        }
        // Tambah kategori yang belum ada di savedOrder di akhir
        for (cat in visible) {
            if (ordered.none { getName(it).equals(getName(cat), ignoreCase = true) }) {
                ordered.add(cat)
            }
        }
        return ordered
    }

    fun clearAll() {
        prefs.edit().clear().apply()
    }
}
