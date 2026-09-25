package com.animatv.player.extra

import android.util.Log
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.animatv.player.model.Category
import com.animatv.player.model.Channel
import com.animatv.player.model.DrmLicense
import com.animatv.player.model.Playlist

object SymphogearJsonConverter {

    private const val TAG = "SymphogearConverter"


    // ================================================================
    // NULL-SAFE JSON READERS
    // "origin": null di JSON menghasilkan JsonNull, dan JsonNull.asString
    // melempar exception -> channel dilewati diam-diam. Helper ini
    // mengembalikan null untuk field yang tidak ada / null.
    // ================================================================

    private fun JsonObject.str(key: String): String? {
        val el = this.get(key)
        return if (el == null || el.isJsonNull) null else el.asString
    }

    private fun JsonObject.bool(key: String): Boolean? {
        val el = this.get(key)
        return if (el == null || el.isJsonNull) null else el.asBoolean
    }

    // ================================================================
    // CATEGORY NAMES
    // ================================================================

    // ================================================================
    // CATEGORY NAME & ORDER
    // ================================================================
    //
    // Sengaja TIDAK ada lagi daftar urutan/nama kategori yang di-hardcode
    // di sini. Urutan kategori sepenuhnya mengikuti urutan kemunculan
    // pertama field "cat" di channels.json -- supaya nambah, atur urutan,
    // dan hapus kategori bisa dilakukan cukup dengan edit channels.json,
    // tanpa perlu build ulang aplikasi.
    //
    // "jepang"/"Jepang"/"JEPANG" dkk yang beda huruf besar-kecil tetap
    // digabung jadi satu kategori (case-insensitive), dan nama yang
    // dipakai adalah penulisan PERTAMA yang ditemukan di file.

    // ================================================================
    // CONVERTER
    // ================================================================

    fun convert(jsonString: String): Playlist? {

        return try {

            val root =
                JsonParser
                    .parseString(jsonString)
                    .asJsonObject

            // Format Symphogear harus mempunyai "channels"
            if (!root.has("channels")) {
                Log.e(
                    TAG,
                    "JSON tidak mempunyai field 'channels'"
                )
                return null
            }

            val channelsArray: JsonArray =
                root.getAsJsonArray("channels")

            val playlist = Playlist()

            val categoryMap =
                LinkedHashMap<String, ArrayList<Channel>>()

            // Simpan penulisan asli (huruf besar/kecil) pertama kali
            // sebuah kategori muncul di channels.json, supaya tampilannya
            // tidak dipaksa jadi "Judul huruf pertama saja".
            val categoryDisplayNames =
                LinkedHashMap<String, String>()

            val drmMap =
                LinkedHashMap<String, String>()

            // ========================================================
            // RESET MENU
            // ========================================================

            com.animatv.player.extra.MenuManager
                .clearJsonMenus()

            // ========================================================
            // PARSE MENUS
            // ========================================================

            val menuLabelMap =
                mutableMapOf<String, String>()

            if (root.has("menus")) {

                try {

                    root.getAsJsonArray("menus")
                        .forEach { menuEl ->

                            val menuObj =
                                menuEl.asJsonObject

                            val menuId =
                                menuObj.str("id")
                                    ?: return@forEach

                            val menuLabel =
                                menuObj.str("label")
                                    ?: menuId.uppercase()

                            menuLabelMap[
                                menuId.lowercase()
                            ] = menuLabel
                        }

                } catch (e: Exception) {

                    Log.w(
                        TAG,
                        "Gagal membaca menus: ${e.message}"
                    )
                }
            }

            // ========================================================
            // PARSE CHANNELS
            // ========================================================

            for (element in channelsArray) {

                try {

                    val obj =
                        element.asJsonObject

                    // ------------------------------------------------
                    // BASIC DATA
                    // ------------------------------------------------

                    val name =
                        obj.str("name")
                            ?: continue

                    val url =
                        obj.str("url")
                            ?: continue

                    val cat =
                        obj.str("cat")
                            ?: "nasional"

                    val menuId =
                        obj.str("menu")

                    // ------------------------------------------------
                    // DRM DATA
                    // ------------------------------------------------

                    val hasDrm =
                        obj.bool("drm")
                            ?: false

                    val drmType =
                        obj.str("drmType")
                            ?: "ClearKey"

                    val licUrl =
                        obj.str("licUrl")

                    val licenseKey =
                        obj.str("licenseKey")

                    // ------------------------------------------------
                    // USER AGENT
                    // ------------------------------------------------

                    // Buang awalan salah ketik seperti "http-user-agent="
                    val ua =
                        obj.str("ua")
                            ?.replace(
                                Regex("^\\s*(http-)?user-agent=", RegexOption.IGNORE_CASE),
                                ""
                            )
                            ?.trim()

                    // ------------------------------------------------
                    // REFERER
                    //
                    // Support:
                    // referrer
                    // referer
                    // ref
                    // ------------------------------------------------

                    val ref =
                        when {

                            obj.has("referrer") &&
                                    !obj.get("referrer").isJsonNull ->

                                obj.get("referrer")
                                    .asString

                            obj.has("referer") &&
                                    !obj.get("referer").isJsonNull ->

                                obj.get("referer")
                                    .asString

                            obj.has("ref") &&
                                    !obj.get("ref").isJsonNull ->

                                obj.get("ref")
                                    .asString

                            else -> null
                        }

                    // ------------------------------------------------
                    // ORIGIN
                    // ------------------------------------------------

                    val origin =
                        obj.str("origin")

                    // ------------------------------------------------
                    // BUFFER (opsional, preset per channel)
                    // ------------------------------------------------

                    val bufferMode =
                        obj.str("buffer")

                    // ------------------------------------------------
                    // LOGO
                    // ------------------------------------------------

                    val logo =
                        obj.str("logo")

                    // =================================================
                    // CREATE CHANNEL
                    // =================================================

                    val channel =
                        Channel()

                    channel.name =
                        name

                    channel.logo =
                        logo

                    // =================================================
                    // SIMPAN FIELD TAMBAHAN
                    // =================================================

                    channel.userAgent =
                        ua

                    channel.referrer =
                        ref

                    channel.licenseKey =
                        licenseKey

                    channel.origin =
                        origin

                    channel.bufferMode =
                        bufferMode

                    // =================================================
                    // BUILD STREAM URL
                    //
                    // PlayerActivity membaca:
                    //
                    // URL
                    // |user-agent=...
                    // |referer=...
                    // =================================================

                    val streamUrl =
                        StringBuilder(url)

                    val headers =
                        mutableListOf<String>()

                    if (!ua.isNullOrBlank()) {

                        headers.add(
                            "user-agent=$ua"
                        )
                    }

                    if (!ref.isNullOrBlank()) {

                        headers.add(
                            "referer=$ref"
                        )
                    }

                    if (headers.isNotEmpty()) {

                        streamUrl.append(
                            "|${headers.joinToString("|")}"
                        )
                    }

                    channel.streamUrl =
                        streamUrl.toString()

                    // =================================================
                    // DRM
                    //
                    // Untuk konten ClearKey yang memang berizin,
                    // licenseKey dapat digunakan sebagai sumber
                    // kredensial DRM.
                    //
                    // Widevine tetap menggunakan licUrl.
                    // =================================================

                    val isWidevine =
                        drmType.equals(
                            "Widevine",
                            ignoreCase = true
                        )

                    val drmValue =
                        if (isWidevine) {

                            // Widevine:
                            // gunakan license server URL
                            licUrl

                        } else {

                            // ClearKey:
                            // gunakan licenseKey jika tersedia,
                            // fallback ke licUrl.
                            when {

                                !licenseKey.isNullOrBlank() ->
                                    licenseKey

                                !licUrl.isNullOrBlank() ->
                                    licUrl

                                else ->
                                    null
                            }
                        }

                    if (hasDrm &&
                        !drmValue.isNullOrBlank()
                    ) {

                        val drmName =
                            if (isWidevine) {

                                "widevine_${drmValue.hashCode()}"

                            } else {

                                "clearkey_${drmValue.hashCode()}"
                            }

                        channel.drmName =
                            drmName

                        if (!drmMap.containsKey(drmName)) {

                            drmMap[drmName] =
                                drmValue
                        }

                        Log.d(
                            TAG,
                            "DRM channel: $name | type=$drmType"
                        )

                    } else if (hasDrm) {

                        Log.w(
                            TAG,
                            "DRM channel '$name' tidak mempunyai licenseKey/licUrl"
                        )
                    }

                    // =================================================
                    // CATEGORY
                    // =================================================

                    val catKey =
                        cat
                            .lowercase()
                            .trim()

                    if (!categoryMap.containsKey(catKey)) {

                        categoryMap[catKey] =
                            ArrayList()

                        categoryDisplayNames[catKey] =
                            cat.trim()
                    }

                    categoryMap[catKey]
                        ?.add(channel)

                    // =================================================
                    // MENU
                    // =================================================

                    if (!menuId.isNullOrBlank()) {

                        val menuLabel =
                            menuLabelMap[
                                menuId.lowercase()
                            ] ?: menuId.uppercase()

                        com.animatv.player.extra.MenuManager
                            .registerCategoryMenu(
                                cat,
                                menuId,
                                menuLabel
                            )
                    }

                } catch (e: Exception) {

                    Log.e(
                        TAG,
                        "Gagal parsing satu channel: ${e.message}"
                    )
                }
            }

            // =========================================================
            // CREATE CATEGORIES
            // =========================================================
            // categoryMap adalah LinkedHashMap, jadi urutan iterasinya
            // sudah otomatis mengikuti urutan kemunculan pertama tiap
            // kategori di channels.json. Tidak ada penyusunan ulang di
            // sini -- itu ditangani belakangan oleh CategoryOrderManager
            // (yang defaultnya juga membiarkan urutan ini apa adanya).

            val categories =
                ArrayList<Category>()

            for ((key, channels) in categoryMap) {

                val category =
                    Category()

                category.name =
                    categoryDisplayNames[key] ?: key

                category.channels =
                    channels

                categories.add(
                    category
                )
            }

            playlist.categories =
                categories

            // =========================================================
            // CREATE DRM LICENSES
            // =========================================================

            val drmLicenses =
                ArrayList<DrmLicense>()

            for ((name, lic) in drmMap) {

                val drm =
                    DrmLicense()

                drm.name =
                    name

                drm.url =
                    lic

                drmLicenses.add(
                    drm
                )
            }

            playlist.drmLicenses =
                drmLicenses

            // =========================================================
            // DEBUG LOG
            // =========================================================

            Log.d(
                TAG,
                "Converted " +
                        "${channelsArray.size()} channels, " +
                        "${categories.size} cats, " +
                        "${drmLicenses.size} DRM"
            )

            playlist

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Failed to convert Symphogear JSON: ${e.message}",
                e
            )

            null
        }
    }

    // ================================================================
    // FORMAT CHECK
    // ================================================================

    fun isSymphogearFormat(
        jsonString: String
    ): Boolean {

        return try {

            val root =
                JsonParser
                    .parseString(jsonString)
                    .asJsonObject

            root.has("channels") &&
                    !root.has("categories")

        } catch (e: Exception) {

            false
        }
    }
}
