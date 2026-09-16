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
 * Urutan:
 * 1. Symphogear JSON
 * 2. JSON Playlist biasa
 * 3. M3U
 */
fun String?.toPlaylist(): Playlist? {

    // ==========================================
    // SYMPHOGEAR JSON
    // ==========================================

    try {

        if (
            com.animatv.player.extra
                .SymphogearJsonConverter
                .isSymphogearFormat(
                    this ?: ""
                )
        ) {

            val result =
                com.animatv.player.extra
                    .SymphogearJsonConverter
                    .convert(
                        this ?: ""
                    )

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


    // ==========================================
    // JSON PLAYLIST BIASA
    // ==========================================

    try {

        return Gson().fromJson(
            this,
            Playlist::class.java
        )

    } catch (e: JsonParseException) {

        e.printStackTrace()
    }


    // ==========================================
    // M3U PLAYLIST
    // ==========================================

    try {

        return M3uTool
            .parse(this)
            .toPlaylist()

    } catch (e: Exception) {

        e.printStackTrace()
    }


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
