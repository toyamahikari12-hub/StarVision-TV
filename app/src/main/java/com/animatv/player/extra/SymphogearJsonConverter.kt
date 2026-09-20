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

    // Mapping cat key -> nama tampil di sidebar
    private val CAT_NAMES = mapOf(
        "nasional"              to "NASIONAL",
        "movies & entertainment" to "MOVIES & ENTERTAINMENT",
        "daerah"                to "DAERAH",
        "kids"                  to "KIDS",
        "anime"                 to "ANIME",
        "jepang"                to "JEPANG",
        "sport"                 to "SPORT",
        // legacy keys tetap didukung
        "berita"                to "BERITA",
        "hiburan"               to "HIBURAN",
        "olahraga"              to "OLAHRAGA",
        "internasional"         to "INTERNASIONAL",
        "vision"                to "VISION+",
        "indihome"              to "INDIHOME",
        "custom"                to "CUSTOM"
    )

    // Urutan sidebar sesuai permintaan
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

    fun convert(jsonString: String): Playlist? {
        return try {
            val root = JsonParser.parseString(jsonString).asJsonObject
            if (!root.has("channels")) return null

            val channelsArray: JsonArray = root.getAsJsonArray("channels")
            val playlist = Playlist()
            val categoryMap = LinkedHashMap<String, ArrayList<Channel>>()
            val drmMap = LinkedHashMap<String, String>()

            // Reset menu manager sebelum parsing
            com.animatv.player.extra.MenuManager.clearJsonMenus()

            // Parse array "menus" jika ada
            val menuLabelMap = mutableMapOf<String, String>()
            if (root.has("menus")) {
                try {
                    root.getAsJsonArray("menus").forEach { menuEl ->
                        val menuObj = menuEl.asJsonObject
                        val menuId = menuObj.get("id")?.asString ?: return@forEach
                        val menuLabel = menuObj.get("label")?.asString ?: menuId.uppercase()
                        menuLabelMap[menuId.lowercase()] = menuLabel
                    }
                } catch (e: Exception) { }
            }

            for (element in channelsArray) {
                val obj = element.asJsonObject
                val name    = obj.get("name")?.asString    ?: continue
                val url     = obj.get("url")?.asString     ?: continue
                val cat     = obj.get("cat")?.asString     ?: "nasional"
                val menuId  = obj.get("menu")?.asString
                val hasDrm  = obj.get("drm")?.asBoolean   ?: false
                val drmType = obj.get("drmType")?.asString ?: "ClearKey"
                val licUrl  = obj.get("licUrl")?.asString
                val ua      = obj.get("ua")?.asString
                val ref     = obj.get("ref")?.asString     // referer header (opsional)
                val logo    = obj.get("logo")?.asString

                val channel = Channel()
                channel.name = name

                // Bangun stream URL dengan header tambahan
                // Format: url|user-agent=UA|referer=REF
                val streamUrl = StringBuilder(url)
                val headers = mutableListOf<String>()
                if (!ua.isNullOrBlank())  headers.add("user-agent=$ua")
                if (!ref.isNullOrBlank()) headers.add("referer=$ref")
                if (headers.isNotEmpty()) streamUrl.append("|${headers.joinToString("|")}")
                channel.streamUrl = streamUrl.toString()

                channel.logo = logo

                if (hasDrm && !licUrl.isNullOrBlank()) {
                    val isWidevine = drmType.equals("Widevine", ignoreCase = true)
                    val drmName = if (isWidevine) "widevine_${licUrl.hashCode()}"
                                  else             "clearkey_${licUrl.hashCode()}"
                    channel.drmName = drmName
                    if (!drmMap.containsKey(drmName)) drmMap[drmName] = licUrl
                }

                // Normalisasi key kategori ke lowercase
                val catKey = cat.lowercase().trim()
                if (!categoryMap.containsKey(catKey)) categoryMap[catKey] = ArrayList()
                categoryMap[catKey]?.add(channel)

                // Register ke MenuManager jika ada field menu
                if (!menuId.isNullOrBlank()) {
                    val menuLabel = menuLabelMap[menuId.lowercase()] ?: menuId.uppercase()
                    com.animatv.player.extra.MenuManager.registerCategoryMenu(cat, menuId, menuLabel)
                }
            }

            val categories = ArrayList<Category>()

            // Tambah kategori sesuai urutan yang ditentukan
            for (key in ORDERED_CATS) {
                if (categoryMap.containsKey(key)) {
                    val category = Category()
                    category.name = CAT_NAMES[key] ?: key.replaceFirstChar { it.uppercase() }
                    category.channels = categoryMap[key]
                    categories.add(category)
                }
            }
            // Kategori yang tidak ada di ORDERED_CATS, tambah di akhir
            for ((key, channels) in categoryMap) {
                if (!ORDERED_CATS.contains(key)) {
                    val category = Category()
                    category.name = CAT_NAMES[key] ?: key.replaceFirstChar { it.uppercase() }
                    category.channels = channels
                    categories.add(category)
                }
            }

            playlist.categories = categories

            val drmLicenses = ArrayList<DrmLicense>()
            for ((name, lic) in drmMap) {
                val drm = DrmLicense()
                drm.name = name
                drm.url = lic
                drmLicenses.add(drm)
            }
            playlist.drmLicenses = drmLicenses

            Log.d(TAG, "Converted ${channelsArray.size()} channels, ${categories.size} cats, ${drmLicenses.size} DRM")
            playlist

        } catch (e: Exception) {
            Log.e(TAG, "Failed to convert Symphogear JSON: ${e.message}")
            null
        }
    }

    fun isSymphogearFormat(jsonString: String): Boolean {
        return try {
            val root = JsonParser.parseString(jsonString).asJsonObject
            root.has("channels") && !root.has("categories")
        } catch (e: Exception) {
            false
        }
    }
}
