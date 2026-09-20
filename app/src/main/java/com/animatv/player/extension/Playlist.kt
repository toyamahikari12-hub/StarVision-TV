package com.animatv.player.extension

import com.google.gson.Gson
import com.google.gson.JsonParseException
import com.animatv.player.extra.M3uTool
import com.animatv.player.model.*

fun List<M3U>?.toPlaylist(): Playlist? {
    if (this == null) return null
    val playlist = Playlist()

    // FIXED: LinkedHashMap supaya urutan kategori tidak acak (HashMap = random order)
    val linkedMap = LinkedHashMap<String, ArrayList<Channel>>()
    val hashSet = HashSet<DrmLicense>()
    val drms = ArrayList<DrmLicense>()
    val cats = ArrayList<Category>()

    for (item in this) {
        val urls = item.streamUrl ?: continue
        for (i in urls.indices) {
            if (!item.licenseKey.isNullOrEmpty()) {
                val drm = DrmLicense()
                drm.name = item.licenseName
                drm.url = item.licenseKey
                if (hashSet.none { d -> d.name == item.licenseName })
                    hashSet.add(drm)
            }
            // getOrPut: kategori sama langsung masuk list yang sama
            val map = linkedMap.getOrPut(item.groupName.toString()) { ArrayList() }
            val ch = Channel()
            ch.name = if (i > 0) item.channelName + " #$i" else item.channelName
            ch.streamUrl = urls[i]
            ch.drmName = item.licenseName
            map.add(ch)
        }
    }

    for (entry in linkedMap) {
        val category = Category()
        category.name = entry.key
        category.channels = entry.value
        cats.add(category)
    }
    playlist.categories = cats
    drms.addAll(hashSet)
    playlist.drmLicenses = drms
    return playlist
}

fun Playlist?.sortCategories() {
    this?.categories?.sortBy { category -> category.name?.lowercase() }
}

fun Playlist?.sortChannels() {
    if (this == null) return
    for (catId in this.categories.indices) {
        this.categories[catId].channels?.sortBy { channel -> channel.name?.lowercase() }
    }
}

fun Playlist?.trimChannelWithEmptyStreamUrl() {
    if (this == null) return
    for (catId in this.categories.indices) {
        this.categories[catId].channels?.removeAll { channel -> channel.streamUrl.isNullOrBlank() }
    }
}

// FIXED: mergeWith — kategori nama sama DIGABUNG, bukan jadi duplikat
// Bug lama: "Nasional" dari 2 sumber = 2 kategori terpisah → catId index kacau → channel gagal diputar
fun Playlist?.mergeWith(playlist: Playlist?) {
    if (playlist == null) return
    for (incomingCat in playlist.categories) {
        val existing = this?.categories?.firstOrNull {
            it.name?.trim()?.lowercase() == incomingCat.name?.trim()?.lowercase()
        }
        if (existing != null) {
            existing.channels?.addAll(incomingCat.channels ?: ArrayList())
        } else {
            this?.categories?.add(incomingCat)
        }
    }
    for (incomingDrm in playlist.drmLicenses) {
        if (this?.drmLicenses?.none { it.name == incomingDrm.name } == true) {
            this.drmLicenses.add(incomingDrm)
        }
    }
}

fun Playlist?.insertFavorite(channels: ArrayList<Channel>) {
    if (this == null) return
    if (this.categories[0].isFavorite())
        this.categories[0].channels = channels
    else
        this.categories.addFavorite(channels)
}

fun Playlist?.removeFavorite() {
    if (this == null) return
    if (this.categories[0].isFavorite())
        this.categories.removeAt(0)
}

fun String?.toPlaylist(): Playlist? {
    try {
        if (com.animatv.player.extra.SymphogearJsonConverter.isSymphogearFormat(this ?: "")) {
            val result = com.animatv.player.extra.SymphogearJsonConverter.convert(this ?: "")
            if (result != null && !result.isCategoriesEmpty()) return result
        }
    } catch (e: Exception) { e.printStackTrace() }
    try { return Gson().fromJson(this, Playlist::class.java) }
    catch (e: JsonParseException) { e.printStackTrace() }
    try { return M3uTool.parse(this).toPlaylist() }
    catch (e: Exception) { e.printStackTrace() }
    return null
}

fun Playlist?.isCategoriesEmpty(): Boolean {
    return this?.categories?.isEmpty() == true
}

fun String?.toSymphogearPlaylist(): Playlist? {
    if (this.isNullOrBlank()) return null
    return com.animatv.player.extra.SymphogearJsonConverter.convert(this)
}
