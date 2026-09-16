package com.animatv.player.model

import com.google.gson.annotations.SerializedName

class Channel {

    var name: String? = null

    @SerializedName(value = "stream_url", alternate = ["url"])
    var streamUrl: String? = null

    // FIX: mapping utama ke "drmType" (JSON inline), fallback ke "drm_name"
    @SerializedName(value = "drmType", alternate = ["drm_name", "drmName"])
    var drmName: String? = null

    // FIX: field "type" dari JSON (dash/hls/ss/dll) sebelumnya tidak dipetakan
    @SerializedName("type")
    var streamType: String? = null

    var logo: String? = null

    @SerializedName("ua")
    var userAgent: String? = null

    @SerializedName("referrer")
    var referrer: String? = null

    // FIX: license inline (hex kid:key) dari JSON channel
    @SerializedName("licenseKey")
    var licenseKey: String? = null

    @SerializedName("origin")
    var origin: String? = null
}
