package com.animatv.player.extra

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.animatv.player.App
import com.animatv.player.model.MenuConfig
import com.animatv.player.model.Playlist

/**
 * MenuManager — kelola mapping menu → sub-kategori
 *
 * Sumber data (prioritas):
 * 1. Override dari Admin Panel (tersimpan di SharedPreferences)
 * 2. Dari channels.json field "menu" (di-parse oleh SymphogearJsonConverter)
 * 3. Fallback: auto-group berdasarkan nama kategori
 */
object MenuManager {

    private const val PREF_NAME = "animatv_menus"
    private const val KEY_MENUS = "menu_configs"
    private const val KEY_CAT_MENU_MAP = "cat_menu_map" // mapping cat -> menuId

    private val prefs: SharedPreferences by lazy {
        App.context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    // Cache runtime — mapping dari channels.json
    private var jsonMenus: MutableList<MenuConfig> = mutableListOf()
    // Mapping kategori -> menuId dari channels.json
    private val catToMenuId: MutableMap<String, String> = mutableMapOf()

    /**
     * Dipanggil oleh SymphogearJsonConverter saat parsing channels.json
     * Menyimpan mapping kategori -> menuId
     */
    fun registerCategoryMenu(catName: String, menuId: String, menuLabel: String) {
        catToMenuId[catName.lowercase().trim()] = menuId.lowercase().trim()

        // Tambah/update menu config di jsonMenus
        val existing = jsonMenus.firstOrNull { it.id == menuId.lowercase() }
        if (existing != null) {
            if (!existing.subCategories.contains(catName)) {
                existing.subCategories.add(catName)
            }
        } else {
            jsonMenus.add(MenuConfig(
                id = menuId.lowercase(),
                label = menuLabel.uppercase(),
                subCategories = mutableListOf(catName)
            ))
        }
    }

    fun clearJsonMenus() {
        jsonMenus.clear()
        catToMenuId.clear()
    }

    /**
     * Ambil semua menu yang tersedia.
     * Prioritas: Admin override > JSON menus > auto-detect
     */
    fun getMenus(): List<MenuConfig> {
        // Coba load admin override
        val adminMenus = loadAdminMenus()
        if (adminMenus.isNotEmpty()) return adminMenus

        // Pakai dari channels.json
        if (jsonMenus.isNotEmpty()) return jsonMenus

        // Fallback: auto-detect dari nama kategori
        return autoDetectMenus()
    }

    /**
     * Dapatkan menu ID untuk sebuah kategori
     */
    fun getMenuIdForCategory(catName: String): String? {
        val key = catName.lowercase().trim()

        // Cek admin override mapping dulu
        val adminMap = loadAdminCatMap()
        if (adminMap.containsKey(key)) return adminMap[key]

        // Dari JSON
        return catToMenuId[key]
    }

    /**
     * Dapatkan sub-kategori untuk sebuah menu ID
     * dari Playlist.cached
     */
    fun getSubCategories(menuId: String): List<com.animatv.player.model.Category> {
        val menus = getMenus()
        val menu = menus.firstOrNull { it.id.equals(menuId, ignoreCase = true) }
            ?: return emptyList()

        val all = Playlist.cached.categories

        // PENTING: urutan hasil harus ikut urutan menu.subCategories
        // (persis seperti ditulis di "menus" -> channels.json), bukan
        // urutan alami Playlist.cached.categories. Sebelumnya di-filter
        // langsung dari `all`, jadi urutannya ikut daftar kategori global
        // dan kelihatan "acak" dibanding urutan subCategories yang ditulis.
        val result = mutableListOf<com.animatv.player.model.Category>()
        for (subCatName in menu.subCategories) {
            val found = all.firstOrNull { (it.name ?: "").equals(subCatName, ignoreCase = true) }
            if (found != null && result.none { it === found }) {
                result.add(found)
            }
        }
        return result
    }

    /**
     * Cek apakah kategori termasuk dalam menu mana pun (bukan live_tv default)
     */
    fun isInAnyMenu(catName: String): Boolean {
        val menuId = getMenuIdForCategory(catName)
        return menuId != null && menuId != "live_tv"
    }

    // ===== ADMIN OVERRIDE =====

    fun saveAdminMenus(menus: List<MenuConfig>) {
        prefs.edit().putString(KEY_MENUS, Gson().toJson(menus)).apply()
    }

    fun saveAdminCatMap(map: Map<String, String>) {
        prefs.edit().putString(KEY_CAT_MENU_MAP, Gson().toJson(map)).apply()
    }

    private fun loadAdminMenus(): List<MenuConfig> {
        val json = prefs.getString(KEY_MENUS, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<MenuConfig>>() {}.type
            Gson().fromJson(json, type) ?: emptyList()
        } catch (e: Exception) { emptyList() }
    }

    private fun loadAdminCatMap(): Map<String, String> {
        val json = prefs.getString(KEY_CAT_MENU_MAP, null) ?: return emptyMap()
        return try {
            val type = object : TypeToken<Map<String, String>>() {}.type
            Gson().fromJson(json, type) ?: emptyMap()
        } catch (e: Exception) { emptyMap() }
    }

    fun clearAdminOverride() {
        prefs.edit().clear().apply()
    }

    /**
     * Auto-detect menus berdasarkan nama kategori yang mirip
     * Contoh: semua kategori mengandung "SPORT" → masuk menu SPORT
     */
    private fun autoDetectMenus(): List<MenuConfig> {
        val cats = Playlist.cached.categories.map { it.name ?: "" }

        val keywords = mapOf(
            "sport"         to "SPORT",
            "live event"    to "LIVE EVENT",
            "daerah"        to "DAERAH",
            "jepang"        to "JEPANG",
            "kids"          to "KIDS",
            "anime"         to "ANIME",
            "movies"        to "MOVIES",
            "entertainment" to "HIBURAN"
        )

        val result = mutableListOf<MenuConfig>()
        for ((key, label) in keywords) {
            val matched = cats.filter { it.lowercase().contains(key) }
            if (matched.size > 1) { // hanya buat menu kalau ada lebih dari 1 sub-kategori
                result.add(MenuConfig(
                    id = key,
                    label = label,
                    subCategories = matched.toMutableList()
                ))
            }
        }
        return result
    }
}
