package com.animatv.player.model

import com.google.gson.annotations.SerializedName

class Playlist {
    var categories: ArrayList<Category> = ArrayList()

    @SerializedName(value = "drm_licenses", alternate = ["drmLicenses", "drm_license"])
    var drmLicenses: ArrayList<DrmLicense> = ArrayList()

    companion object {
        var cached = Playlist()
        var favorites = Favorites()
    }
}
