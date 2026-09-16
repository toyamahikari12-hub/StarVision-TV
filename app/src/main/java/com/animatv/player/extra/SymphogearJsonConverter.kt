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

        "berita",
        "hiburan",
        "olahraga",
        "internasional",
        "vision",
        "indihome",
        "custom"
    )

    // ================================================================
    // CONVERT
    // ================================================================

    fun convert(jsonString: String): Playlist? {

        return try {

            if (jsonString.isBlank()) {
                Log.e(TAG, "JSON kosong")
                return null
            }

            // ========================================================
            // PENTING:
            // Gunakan JsonParser() agar kompatibel dengan Gson lama.
            // Jangan gunakan JsonParser.parseString().
            // ========================================================

            val parser = JsonParser()

            val rootElement =
                parser.parse(jsonString)

            if (!rootElement.isJsonObject) {
                Log.e(TAG, "Root JSON bukan object")
                return null
            }

            val root =
                rootElement.asJsonObject

            // ========================================================
            // CHANNELS
            // ========================================================

            if (!root.has("channels") ||
                root.get("channels").isJsonNull ||
                !root.get("channels").isJsonArray
            ) {
                Log.e(
                    TAG,
                    "Field 'channels' tidak ditemukan atau bukan array"
                )
                return null
            }

            val channelsArray: JsonArray =
                root.getAsJsonArray("channels")

            if (channelsArray.size() == 0) {
                Log.e(TAG, "Array channels kosong")
                return null
            }

            val playlist =
                Playlist()

            val categoryMap =
                LinkedHashMap<String, ArrayList<Channel>>()

            val drmMap =
                LinkedHashMap<String, String>()

            // ========================================================
            // RESET MENU
            // ========================================================

            try {
                com.animatv.player.extra.MenuManager
                    .clearJsonMenus()
            } catch (e: Exception) {
                Log.w(
                    TAG,
                    "MenuManager reset gagal: ${e.message}"
                )
            }

            // ========================================================
            // MENUS
            // ========================================================

            val menuLabelMap =
                mutableMapOf<String, String>()

            if (root.has("menus") &&
                !root.get("menus").isJsonNull &&
                root.get("menus").isJsonArray
            ) {

                try {

                    root.getAsJsonArray("menus")
                        .forEach { menuElement ->

                            if (!menuElement.isJsonObject) {
                                return@forEach
                            }

                            val menuObj =
                                menuElement.asJsonObject

                            val menuId =
                                menuObj
                                    .get("id")
                                    ?.takeIf { !it.isJsonNull }
                                    ?.asString
                                    ?: return@forEach

                            val menuLabel =
                                menuObj
                                    .get("label")
                                    ?.takeIf { !it.isJsonNull }
                                    ?.asString
                                    ?: menuId.toUpperCase()

                            menuLabelMap[
                                menuId.toLowerCase()
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
            // CHANNELS
            // ========================================================

            for (element in channelsArray) {

                try {

                    if (!element.isJsonObject) {
                        continue
                    }

                    val obj =
                        element.asJsonObject

                    // ------------------------------------------------
                    // BASIC
                    // ------------------------------------------------

                    val name =
                        obj.get("name")
                            ?.takeIf { !it.isJsonNull }
                            ?.asString
                            ?.trim()

                    val url =
                        obj.get("url")
                            ?.takeIf { !it.isJsonNull }
                            ?.asString
                            ?.trim()

                    if (name.isNullOrBlank() ||
                        url.isNullOrBlank()
                    ) {
                        Log.w(
                            TAG,
                            "Channel dilewati karena name/url kosong"
                        )
                        continue
                    }

                    val cat =
                        obj.get("cat")
                            ?.takeIf { !it.isJsonNull }
                            ?.asString
                            ?.trim()
                            ?: "nasional"

                    val menuId =
                        obj.get("menu")
                            ?.takeIf { !it.isJsonNull }
                            ?.asString
                            ?.trim()

                    // ------------------------------------------------
                    // DRM METADATA
                    // ------------------------------------------------

                    val hasDrm =
                        obj.get("drm")
                            ?.takeIf { !it.isJsonNull }
                            ?.asBoolean
                            ?: false

                    val drmType =
                        obj.get("drmType")
                            ?.takeIf { !it.isJsonNull }
                            ?.asString
                            ?.trim()
                            ?: "ClearKey"

                    val licUrl =
                        obj.get("licUrl")
                            ?.takeIf { !it.isJsonNull }
                            ?.asString
                            ?.trim()

                    val licenseKey =
                        obj.get("licenseKey")
                            ?.takeIf { !it.isJsonNull }
                            ?.asString
                            ?.trim()

                    // ------------------------------------------------
                    // USER AGENT
                    // ------------------------------------------------

                    val ua =
                        obj.get("ua")
                            ?.takeIf { !it.isJsonNull }
                            ?.asString
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
                                    .trim()

                            obj.has("referer") &&
                                    !obj.get("referer").isJsonNull ->

                                obj.get("referer")
                                    .asString
                                    .trim()

                            obj.has("ref") &&
                                    !obj.get("ref").isJsonNull ->

                                obj.get("ref")
                                    .asString
                                    .trim()

                            else -> null
                        }

                    // ------------------------------------------------
                    // ORIGIN
                    // ------------------------------------------------

                    val origin =
                        obj.get("origin")
                            ?.takeIf { !it.isJsonNull }
                            ?.asString
                            ?.trim()

                    // ------------------------------------------------
                    // LOGO
                    // ------------------------------------------------

                    val logo =
                        obj.get("logo")
                            ?.takeIf { !it.isJsonNull }
                            ?.asString
                            ?.trim()

                    // =================================================
                    // CHANNEL OBJECT
                    // =================================================

                    val channel =
                        Channel()

                    channel.name =
                        name

                    channel.logo =
                        logo

                    // Simpan metadata asli
                    channel.userAgent =
                        ua

                    channel.referrer =
                        ref

                    channel.licenseKey =
                        licenseKey

                    channel.origin =
                        origin

                    // =================================================
                    // STREAM URL
                    // =================================================

                    val streamBuilder =
                        StringBuilder(url)

                    val headers =
                        ArrayList<String>()

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

                        streamBuilder.append(
                            "|"
                        )

                        streamBuilder.append(
                            headers.joinToString("|")
                        )
                    }

                    channel.streamUrl =
                        streamBuilder.toString()

                    // =================================================
                    // DRM METADATA
                    // =================================================
                    //
                    // Jangan mengubah nilai license menjadi URL.
                    // licenseKey tetap disimpan di Channel.
                    //
                    // Untuk license server resmi, licUrl digunakan
                    // sebagai URL license.
                    // =================================================

                    val isWidevine =
                        drmType.equals(
                            "Widevine",
                            ignoreCase = true
                        )

                    if (hasDrm) {

                        if (isWidevine) {

                            if (!licUrl.isNullOrBlank()) {

                                val drmName =
                                    "widevine_${licUrl.hashCode()}"

                                channel.drmName =
                                    drmName

                                if (!drmMap.containsKey(drmName)) {

                                    drmMap[drmName] =
                                        licUrl
                                }

                                Log.d(
                                    TAG,
                                    "Widevine metadata loaded: $name"
                                )

                            } else {

                                Log.w(
                                    TAG,
                                    "Widevine '$name' tidak mempunyai licUrl"
                                )
                            }

                        } else {

                            // ClearKey:
                            // tandai channel menggunakan ClearKey
                            // apabila metadata license tersedia.
                            //
                            // Nilai licenseKey tetap berada di
                            // channel. Tidak diubah menjadi URL.

                            if (!licenseKey.isNullOrBlank()) {

                                val drmName =
                                    "clearkey_${licenseKey.hashCode()}"

                                channel.drmName =
                                    drmName

                                if (!drmMap.containsKey(drmName)) {

                                    drmMap[drmName] =
                                        licenseKey
                                }

                                Log.d(
                                    TAG,
                                    "ClearKey metadata loaded: $name"
                                )

                            } else if (!licUrl.isNullOrBlank()) {

                                val drmName =
                                    "clearkey_${licUrl.hashCode()}"

                                channel.drmName =
                                    drmName

                                if (!drmMap.containsKey(drmName)) {

                                    drmMap[drmName] =
                                        licUrl
                                }

                                Log.d(
                                    TAG,
                                    "ClearKey license URL loaded: $name"
                                )

                            } else {

                                Log.w(
                                    TAG,
                                    "ClearKey '$name' tidak mempunyai license metadata"
                                )
                            }
                        }
                    }

                    // =================================================
                    // CATEGORY
                    // =================================================

                    val catKey =
                        cat
                            .toLowerCase()
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
                                menuId.toLowerCase()
                            ] ?: menuId.toUpperCase()

                        try {

                            com.animatv.player.extra.MenuManager
                                .registerCategoryMenu(
                                    cat,
                                    menuId,
                                    menuLabel
                                )

                        } catch (e: Exception) {

                            Log.w(
                                TAG,
                                "Menu register gagal: ${e.message}"
                            )
                        }
                    }

                } catch (e: Exception) {

                    Log.e(
                        TAG,
                        "Gagal parsing channel: ${e.message}",
                        e
                    )
                }
            }

            // ========================================================
            // CATEGORY
            // ========================================================

            val categories =
                ArrayList<Category>()

            // Urutan kategori utama
            for (key in ORDERED_CATS) {

                val channels =
                    categoryMap[key]

                if (channels != null &&
                    channels.isNotEmpty()
                ) {

                    val category =
                        Category()

                    category.name =
                        CAT_NAMES[key]
                            ?: key.replaceFirstChar {
                                it.toUpperCase()
                            }

                    category.channels =
                        channels

                    categories.add(
                        category
                    )
                }
            }

            // Kategori lain
            for ((key, channels) in categoryMap) {

                if (!ORDERED_CATS.contains(key) &&
                    channels.isNotEmpty()
                ) {

                    val category =
                        Category()

                    category.name =
                        CAT_NAMES[key]
                            ?: key.replaceFirstChar {
                                it.toUpperCase()
                            }

                    category.channels =
                        channels

                    categories.add(
                        category
                    )
                }
            }

            // ========================================================
            // SIMPAN PLAYLIST
            // ========================================================

            playlist.categories =
                categories

            // ========================================================
            // DRM LICENSE LIST
            // ========================================================

            val drmLicenses =
                ArrayList<DrmLicense>()

            for ((name, value) in drmMap) {

                val drm =
                    DrmLicense()

                drm.name =
                    name

                drm.url =
                    value

                drmLicenses.add(
                    drm
                )
            }

            playlist.drmLicenses =
                drmLicenses

            // ========================================================
            // RESULT LOG
            // ========================================================

            Log.d(
                TAG,
                "SUCCESS: " +
                        "${channelsArray.size()} channels, " +
                        "${categories.size} categories, " +
                        "${drmLicenses.size} DRM entries"
            )

            if (categories.isEmpty()) {

                Log.e(
                    TAG,
                    "Converter selesai tetapi tidak ada category"
                )

                return null
            }

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

            if (jsonString.isBlank()) {
                return false
            }

            // Gunakan API Gson lama agar kompatibel
            // dengan dependency project.
            val root =
                JsonParser()
                    .parse(jsonString)

            if (!root.isJsonObject) {
                return false
            }

            val obj =
                root.asJsonObject

            obj.has("channels") &&
                    obj.get("channels").isJsonArray

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Format check gagal: ${e.message}"
            )

            false
        }
    }
}
