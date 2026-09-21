package com.animatv.player.model

class Category {
    var name: String? = null
    var channels: ArrayList<Channel>? = null

    // Ikon singkat sidebar (opsional, dari "category_icons" di channels.json)
    var icon: String? = null
}
