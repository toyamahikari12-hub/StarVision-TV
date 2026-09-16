package com.animatv.player.extra

import android.util.Log
import com.google.gson.JsonArray
import com.google.gson.JsonParser
import com.animatv.player.model.Category
import com.animatv.player.model.Channel
import com.animatv.player.model.DrmLicense
import com.animatv.player.model.Playlist

object SymphogearJsonConverter {

    private const val TAG = "SymphogearConverter"

    // ================================================================
    // CATEGORY NAMES
    // ================================================================

    private val CAT_NAMES = mapOf(
        "nasional" to "NASIONAL",
        "movies & entertainment" to "MOVIES & ENTERTAINMENT",
        "daerah" to "DAERAH",
        "kids" to "KIDS",
        "anime" to "ANIME",
        "jepang" to "JEPANG",
        "sport" to "SPORT",

        // legacy
        "berita" to "BERITA",
        "hiburan" to "HIBURAN",
        "olahraga" to "OLAHRAGA",
        "internasional" to "INTERNASIONAL",
        "vision" to "VISION+",
        "indihome" to "INDIHOME",
        "custom" to "CUSTOM"
    )

    // ================================================================
    // CATEGORY ORDER
    // ================================================================

    private val ORDERED_CATS = listOf(
        "nasional",
        "movies & entertainment",
        "daerah",
        "kids",
        "anime",
        "jepang",
        "sport",

        // legacy
        "berita",
        "hiburan",
        "olahraga",
        "internasional",
        "vision",
        "indihome",
        "custom"
    )

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
                                menuObj
                                    .get("id")
                                    ?.asString
                                    ?: return@forEach

                            val menuLabel =
                                menuObj
                                    .get("label")
                                    ?.asString
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
                        obj.get("name")
                            ?.asString
                            ?: continue

                    val url =
                        obj.get("url")
                            ?.asString
                            ?: continue

                    val cat =
                        obj.get("cat")
                            ?.asString
                            ?: "nasional"

                    val menuId =
                        obj.get("menu")
                            ?.asString

                    // ------------------------------------------------
                    // DRM DATA
                    // ------------------------------------------------

                    val hasDrm =
                        obj.get("drm")
                            ?.asBoolean
                            ?: false

                    val drmType =
                        obj.get("drmType")
                            ?.asString
                            ?: "ClearKey"

                    val licUrl =
                        obj.get("licUrl")
                            ?.asString

                    val licenseKey =
                        obj.get("licenseKey")
                            ?.asString

                    // ------------------------------------------------
                    // USER AGENT
                    // ------------------------------------------------

                    val ua =
                        obj.get("ua")
                            ?.asString

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
                        obj.get("origin")
                            ?.asString

                    // ------------------------------------------------
                    // LOGO
                    // ------------------------------------------------

                    val logo =
                        obj.get("logo")
                            ?.asString

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

            val categories =
                ArrayList<Category>()

            // Kategori berdasarkan urutan yang ditentukan
            for (key in ORDERED_CATS) {

                if (categoryMap.containsKey(key)) {

                    val category =
                        Category()

                    category.name =
                        CAT_NAMES[key]
                            ?: key.replaceFirstChar {
                                it.uppercase()
                            }

                    category.channels =
                        categoryMap[key]

                    categories.add(
                        category
                    )
                }
            }

            // Kategori lain
            for ((key, channels) in categoryMap) {

                if (!ORDERED_CATS.contains(key)) {

                    val category =
                        Category()

                    category.name =
                        CAT_NAMES[key]
                            ?: key.replaceFirstChar {
                                it.uppercase()
                            }

                    category.channels =
                        channels

                    categories.add(
                        category
                    )
                }
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
