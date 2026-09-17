package com.animatv.player.extension

import com.google.gson.Gson
import com.google.gson.JsonParseException
import com.animatv.player.extra.M3uTool
import com.animatv.player.model.*

fun List<M3U>?.toPlaylist(): Playlist? {

    if (this == null) return null

    val playlist = Playlist()

    val linkedMap =
        LinkedHashMap<String, ArrayList<Channel>>()

    val hashSet =
        HashSet<DrmLicense>()

    val drms =
        ArrayList<DrmLicense>()

    val cats =
        ArrayList<Category>()

    for (item in this) {

        val urls =
            item.streamUrl ?: continue

        for (i in urls.indices) {

            // ==========================================
            // DRM LICENSE
            // ==========================================

            if (!item.licenseKey.isNullOrEmpty()) {

                val drm =
                    DrmLicense()

                drm.name =
                    item.licenseName

                drm.url =
                    item.licenseKey

                if (
                    hashSet.none {
                        d ->
                        d.name == item.licenseName
                    }
                ) {
                    hashSet.add(drm)
                }
            }

            // ==========================================
            // CATEGORY
            // ==========================================

            val map =
                linkedMap.getOrPut(
                    item.groupName.toString()
                ) {
                    ArrayList()
                }

            // ==========================================
            // CHANNEL
            // ==========================================

            val ch =
                Channel()

            ch.name =
                if (i > 0) {
                    item.channelName + " #$i"
                } else {
                    item.channelName
                }

            // URL STREAM
            ch.streamUrl =
                urls[i]

            // ==========================================
            // HTTP HEADERS
            // ==========================================

            ch.userAgent =
                item.userAgent

            ch.referrer =
                item.referrer

            ch.origin =
                item.origin

            // ==========================================
            // DRM
            // ==========================================

            ch.drmName =
                item.licenseName

            ch.licenseKey =
                item.licenseKey

            map.add(ch)
        }
    }

    // ==========================================
    // CATEGORY
    // ==========================================

    for (entry in linkedMap) {

        val category =
            Category()

        category.name =
            entry.key

        category.channels =
            entry.value

        cats.add(category)
    }

    playlist.categories =
        cats

    // ==========================================
    // DRM LIST
    // ==========================================

    drms.addAll(hashSet)

    playlist.drmLicenses =
        drms

    return playlist
}


// ==================================================
// SORT CATEGORY
// ==================================================

fun Playlist?.sortCategories() {

    this?.categories?.sortBy {
        category ->
        category.name?.lowercase()
    }
}


// ==================================================
// SORT CHANNEL
// ==================================================

fun Playlist?.sortChannels() {

    if (this == null) return

    for (catId in this.categories.indices) {

        this.categories[catId]
            .channels
            ?.sortBy {
                channel ->
                channel.name?.lowercase()
            }
    }
}


// ==================================================
// REMOVE EMPTY STREAM URL
// ==================================================

fun Playlist?.trimChannelWithEmptyStreamUrl() {

    if (this == null) return

    for (catId in this.categories.indices) {

        this.categories[catId]
            .channels
            ?.removeAll {
                channel ->
                channel.streamUrl.isNullOrBlank()
            }
    }
}


// ==================================================
// MERGE PLAYLIST
// ==================================================

fun Playlist?.mergeWith(
    playlist: Playlist?
) {

    if (playlist == null) return

    // ==========================================
    // MERGE CATEGORY
    // ==========================================

    for (incomingCat in playlist.categories) {

        val existing =
            this?.categories?.firstOrNull {

                it.name
                    ?.trim()
                    ?.lowercase() ==

                    incomingCat.name
                        ?.trim()
                        ?.lowercase()
            }

        if (existing != null) {

            existing.channels?.addAll(
                incomingCat.channels
                    ?: ArrayList()
            )

        } else {

            this?.categories?.add(
                incomingCat
            )
        }
    }

    // ==========================================
    // MERGE DRM
    // ==========================================

    for (incomingDrm in playlist.drmLicenses) {

        if (
            this?.drmLicenses?.none {
                it.name == incomingDrm.name
            } == true
        ) {

            this.drmLicenses.add(
                incomingDrm
            )
        }
    }
}


// ==================================================
// INSERT FAVORITE
// ==================================================

fun Playlist?.insertFavorite(
    channels: ArrayList<Channel>
) {

    if (this == null) return

    if (this.categories[0].isFavorite()) {

        this.categories[0].channels =
            channels

    } else {

        this.categories.addFavorite(
            channels
        )
    }
}


// ==================================================
// REMOVE FAVORITE
// ==================================================

fun Playlist?.removeFavorite() {

    if (this == null) return

    if (this.categories[0].isFavorite()) {

        this.categories.removeAt(0)
    }
}


/**
 * Mengubah String menjadi Playlist.
 *
 * Format yang didukung:
 *
 * 1. Symphogear JSON
 * 2. channels.json format array
 * 3. JSON Playlist biasa
 * 4. M3U
 *
 * Contoh channels.json:
 *
 * [
 *   {
 *     "id": 1071,
 *     "name": "ANTV",
 *     "cat": "NASIONAL",
 *     "color": "#e91e8c",
 *     "q": "HD",
 *     "drm": false,
 *     "type": "hls",
 *     "logo": "...",
 *     "url": "...",
 *     "desc": "ANTV",
 *     "tvgId": "ANTV.indihome",
 *     "ua": "Mozilla/5.0 ...",
 *     "referrer": "https://...",
 *     "origin": null,
 *     "drmType": null,
 *     "licenseKey": null
 *   }
 * ]
 */
fun String?.toPlaylist(): Playlist? {

    if (this.isNullOrBlank()) {
        return null
    }

    val jsonText = this.trim()
    val gson = Gson()

    // =========================================================
    // 1. SYMPHOGEAR JSON
    // =========================================================

    try {

        if (
            com.animatv.player.extra.SymphogearJsonConverter
                .isSymphogearFormat(jsonText)
        ) {

            val result =
                com.animatv.player.extra.SymphogearJsonConverter
                    .convert(jsonText)

            if (
                result != null &&
                !result.isCategoriesEmpty()
            ) {
                return result
            }
        }

    } catch (e: Exception) {

        e.printStackTrace()
    }


    // =========================================================
    // 2. channels.json FORMAT ARRAY
    // =========================================================
    //
    // Format:
    //
    // [
    //   {
    //     "name": "ANIMAX",
    //     "cat": "VISION+",
    //     "url": "...",
    //     "ua": "...",
    //     "referrer": "...",
    //     "drmType": "clearkey",
    //     "licenseKey": "..."
    //   }
    // ]
    //
    // =========================================================

    try {

        val root =
            com.google.gson.JsonParser()
                .parse(jsonText)

        if (root.isJsonArray) {

            val playlist = Playlist()

            /*
             * LinkedHashMap digunakan supaya urutan kategori
             * mengikuti urutan channels.json.
             */
            val categoryMap =
                LinkedHashMap<String, ArrayList<Channel>>()

            /*
             * DRM dikumpulkan berdasarkan nama DRM.
             */
            val drmMap =
                LinkedHashMap<String, DrmLicense>()

            for (element in root.asJsonArray) {

                if (!element.isJsonObject) {
                    continue
                }

                val obj = element.asJsonObject

                // -------------------------------------------------
                // Nama channel
                // -------------------------------------------------

                val channelName =
                    if (
                        obj.has("name") &&
                        !obj.get("name").isJsonNull
                    ) {
                        obj.get("name").asString.trim()
                    } else {
                        ""
                    }

                if (channelName.isEmpty()) {
                    continue
                }


                // -------------------------------------------------
                // Kategori
                // -------------------------------------------------

                val categoryName =
                    if (
                        obj.has("cat") &&
                        !obj.get("cat").isJsonNull
                    ) {
                        obj.get("cat").asString.trim()
                    } else {
                        "LAINNYA"
                    }


                // -------------------------------------------------
                // Buat Channel
                // -------------------------------------------------

                val channel =
                    gson.fromJson(
                        obj,
                        Channel::class.java
                    )

                channel.name = channelName


                // -------------------------------------------------
                // URL
                // -------------------------------------------------

                var streamUrl =
                    when {

                        obj.has("url") &&
                        !obj.get("url").isJsonNull -> {
                            obj.get("url").asString.trim()
                        }

                        obj.has("stream_url") &&
                        !obj.get("stream_url").isJsonNull -> {
                            obj.get("stream_url").asString.trim()
                        }

                        else -> {
                            ""
                        }
                    }


                // -------------------------------------------------
                // User-Agent
                // -------------------------------------------------

                val userAgent =
                    if (
                        obj.has("ua") &&
                        !obj.get("ua").isJsonNull
                    ) {
                        obj.get("ua").asString.trim()
                    } else {
                        ""
                    }


                // -------------------------------------------------
                // Referer
                // -------------------------------------------------

                val referrer =
                    when {

                        obj.has("referrer") &&
                        !obj.get("referrer").isJsonNull -> {
                            obj.get("referrer").asString.trim()
                        }

                        obj.has("referer") &&
                        !obj.get("referer").isJsonNull -> {
                            obj.get("referer").asString.trim()
                        }

                        else -> {
                            ""
                        }
                    }


                // -------------------------------------------------
                // Origin
                // -------------------------------------------------

                val origin =
                    if (
                        obj.has("origin") &&
                        !obj.get("origin").isJsonNull
                    ) {
                        obj.get("origin").asString.trim()
                    } else {
                        ""
                    }


                // Simpan metadata ke Channel
                channel.userAgent =
                    userAgent.ifEmpty { null }

                channel.referrer =
                    referrer.ifEmpty { null }

                channel.origin =
                    origin.ifEmpty { null }


                // -------------------------------------------------
                // DRM
                // -------------------------------------------------

                val drmType =
                    if (
                        obj.has("drmType") &&
                        !obj.get("drmType").isJsonNull
                    ) {
                        obj.get("drmType").asString.trim()
                    } else {
                        ""
                    }


                val licenseKey =
                    if (
                        obj.has("licenseKey") &&
                        !obj.get("licenseKey").isJsonNull
                    ) {
                        obj.get("licenseKey").asString.trim()
                    } else {
                        ""
                    }


                if (
                    drmType.isNotEmpty()
                ) {

                    channel.drmName = drmType
                }


                channel.licenseKey =
                    licenseKey.ifEmpty { null }


                // -------------------------------------------------
                // Header untuk PlayerActivity lama
                // -------------------------------------------------
                //
                // PlayerActivity sekarang mengambil User-Agent
                // dan Referer dari streamUrl.
                //
                // Karena itu kita pertahankan format internal:
                //
                // URL|user-agent=...|referer=...
                //
                // Ini tidak mengubah URL asli di channels.json.
                // -------------------------------------------------

                if (streamUrl.isNotEmpty()) {

                    if (userAgent.isNotEmpty()) {

                        streamUrl +=
                            "|user-agent=" +
                            userAgent
                    }

                    if (referrer.isNotEmpty()) {

                        streamUrl +=
                            "|referer=" +
                            referrer
                    }

                    /*
                     * Origin tidak ditambahkan ke URL karena
                     * PlayerActivity belum mengambil Origin
                     * dari parameter URL.
                     *
                     * Nilainya tetap disimpan di Channel.origin.
                     */
                }

                channel.streamUrl =
                    streamUrl.ifEmpty { null }


                // -------------------------------------------------
                // Masukkan DRM ke Playlist
                // -------------------------------------------------

                if (
                    licenseKey.isNotEmpty() &&
                    drmType.isNotEmpty()
                ) {

                    if (
                        !drmMap.containsKey(drmType)
                    ) {

                        val drm =
                            DrmLicense()

                        drm.name = drmType
                        drm.url = licenseKey

                        drmMap[drmType] = drm
                    }
                }


                // -------------------------------------------------
                // Masukkan channel ke kategori
                // -------------------------------------------------

                val channelList =
                    categoryMap.getOrPut(
                        categoryName
                    ) {
                        ArrayList()
                    }

                channelList.add(channel)
            }


            // -----------------------------------------------------
            // Buat Category
            // -----------------------------------------------------

            val categories =
                ArrayList<Category>()

            for (
                entry in categoryMap
            ) {

                val category =
                    Category()

                category.name =
                    entry.key

                category.channels =
                    entry.value

                categories.add(category)
            }


            // -----------------------------------------------------
            // Masukkan hasil ke Playlist
            // -----------------------------------------------------

            playlist.categories =
                categories

            playlist.drmLicenses =
                ArrayList(drmMap.values)


            // -----------------------------------------------------
            // Pastikan memang ada channel
            // -----------------------------------------------------

            if (
                playlist.categories.isNotEmpty()
            ) {

                return playlist
            }
        }

    } catch (e: Exception) {

        e.printStackTrace()
    }


    // =========================================================
    // 3. JSON PLAYLIST BIASA
    // =========================================================

    try {

        val playlist =
            gson.fromJson(
                jsonText,
                Playlist::class.java
            )

        if (
            playlist != null &&
            !playlist.isCategoriesEmpty()
        ) {

            /*
             * JSON Playlist lama juga tetap didukung.
             *
             * PlayerActivity masih mengambil header dari
             * streamUrl, jadi metadata UA/Referer dipasang
             * ke URL internal jika belum ada.
             */

            playlist.categories?.forEach { category ->

                category.channels?.forEach { channel ->

                    var url =
                        channel.streamUrl?.trim()

                    if (
                        !url.isNullOrEmpty()
                    ) {

                        val hasUserAgent =
                            url.contains(
                                "|user-agent=",
                                ignoreCase = true
                            )

                        val hasReferer =
                            url.contains(
                                "|referer=",
                                ignoreCase = true
                            )


                        if (
                            !hasUserAgent &&
                            !channel.userAgent.isNullOrBlank()
                        ) {

                            url +=
                                "|user-agent=" +
                                channel.userAgent
                        }


                        if (
                            !hasReferer &&
                            !channel.referrer.isNullOrBlank()
                        ) {

                            url +=
                                "|referer=" +
                                channel.referrer
                        }


                        channel.streamUrl =
                            url
                    }
                }
            }

            return playlist
        }

    } catch (e: Exception) {

        e.printStackTrace()
    }


    // =========================================================
    // 4. M3U PLAYLIST
    // =========================================================

    try {

        return M3uTool
            .parse(jsonText)
            .toPlaylist()

    } catch (e: Exception) {

        e.printStackTrace()
    }


    // =========================================================
    // GAGAL
    // =========================================================

    return null
}


// ==================================================
// CHECK EMPTY CATEGORY
// ==================================================

fun Playlist?.isCategoriesEmpty(): Boolean {

    return this
        ?.categories
        ?.isEmpty() == true
}


// ==================================================
// SYMPHOGEAR PLAYLIST
// ==================================================

fun String?.toSymphogearPlaylist(): Playlist? {

    if (this.isNullOrBlank()) {

        return null
    }

    return com.animatv.player.extra
        .SymphogearJsonConverter
        .convert(this)
}
