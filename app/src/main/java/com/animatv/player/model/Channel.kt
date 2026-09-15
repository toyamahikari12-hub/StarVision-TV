package com.animatv.player.model

import com.google.gson.annotations.SerializedName

class Channel {

    var name: String? = null

    @SerializedName(value = "stream_url", alternate = ["url"])
    var streamUrl: String? = null

    @SerializedName("drm_name")
    var drmName: String? = null

    var logo: String? = null

    @SerializedName("ua")
    var userAgent: String? = null

    @SerializedName("referrer")
    var referrer: String? = null
}
