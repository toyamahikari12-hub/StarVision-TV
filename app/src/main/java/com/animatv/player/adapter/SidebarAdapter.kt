package com.animatv.player.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.animatv.player.R
import com.animatv.player.model.Category

class SidebarAdapter(
    private var categories: ArrayList<Category>?,
    private val onItemClick: (Category, Int) -> Unit
) : RecyclerView.Adapter<SidebarAdapter.ViewHolder>() {

    private var selectedPosition = 0

    private val catIcons = mapOf(
        "nasional"      to "TV",
        "berita"        to "NWS",
        "hiburan"       to "ENT",
        "olahraga"      to "SPT",
        "sport"         to "SPT",
        "internasional" to "INT",
        "jepang"        to "JPN",
        "vision+"       to "VIS",
        "vision"        to "VIS",
        "indihome"      to "IND",
        "custom"        to "CST",
        "kids"          to "KID",
        "anime"         to "ANM",
        "daerah"        to "DAR",
        "live event"    to "LVE",
        "movies"        to "MOV",
        "favorit"       to "\u2605",
        "favorite"      to "\u2605"
    )

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val accent: View = view.findViewById(R.id.sidebar_accent)
        val icon: TextView = view.findViewById(R.id.sidebar_icon)
        val name: TextView = view.findViewById(R.id.sidebar_name)
        val count: TextView = view.findViewById(R.id.sidebar_count)
        val root: View = view.findViewById(R.id.sidebar_item_root)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_sidebar, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val cat = categories?.get(position) ?: return
        val catName = cat.name ?: ""
        val key = catName.lowercase().trim()

        val icon = cat.icon?.takeIf { it.isNotBlank() }
            ?: catIcons.entries.firstOrNull { key.contains(it.key) }?.value
            ?: catName.trim().take(3).uppercase().ifBlank { "CH" }
        holder.icon.text = icon
        holder.name.text = catName

        val count = cat.channels?.size ?: 0
        holder.count.text = if (count > 99) "99+" else count.toString()

        val isSelected = position == selectedPosition
        holder.accent.visibility = if (isSelected) View.VISIBLE else View.INVISIBLE
        if (isSelected) {
            holder.root.setBackgroundResource(R.drawable.sidebar_item_selected_bg)
        } else {
            // Pakai selector agar state_focused dari remote tetap jalan
            holder.root.setBackgroundResource(R.drawable.sidebar_item_bg)
        }
        holder.name.setTextColor(
            if (isSelected) ContextCompat.getColor(holder.root.context, R.color.color_primary)
            else 0xE0F0E6FF.toInt()
        )

        holder.root.setOnClickListener {
            val prev = selectedPosition
            selectedPosition = holder.adapterPosition
            notifyItemChanged(prev)
            notifyItemChanged(selectedPosition)
            onItemClick(cat, selectedPosition)
        }

        // TV Remote: highlight saat item mendapat fokus dari DPAD
        holder.root.setOnFocusChangeListener { v, hasFocus ->
            if (hasFocus) {
                v.setBackgroundResource(R.drawable.sidebar_item_focused_bg)
                holder.name.setTextColor(0xFFFFD700.toInt())
            } else {
                if (position == selectedPosition) {
                    v.setBackgroundResource(R.drawable.sidebar_item_selected_bg)
                    holder.name.setTextColor(
                        androidx.core.content.ContextCompat.getColor(v.context, R.color.color_primary)
                    )
                } else {
                    v.setBackgroundResource(R.drawable.sidebar_item_bg)
                    holder.name.setTextColor(0xE0F0E6FF.toInt())
                }
            }
        }

        // TV Remote: ENTER/DPAD_CENTER triggers click
        holder.root.setOnKeyListener { v, keyCode, event ->
            if (event.action == android.view.KeyEvent.ACTION_DOWN &&
                (keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER ||
                 keyCode == android.view.KeyEvent.KEYCODE_ENTER ||
                 keyCode == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER)) {
                v.performClick()
                true
            } else false
        }
    }

    override fun getItemCount() = categories?.size ?: 0

    fun selectCategory(position: Int) {
        val prev = selectedPosition
        selectedPosition = position
        notifyItemChanged(prev)
        if (position >= 0) notifyItemChanged(selectedPosition)
        else notifyDataSetChanged() // deselect all
    }

    fun updateCategories(newCategories: ArrayList<Category>) {
        categories = newCategories
        selectedPosition = 0
        notifyDataSetChanged()
    }

    fun getCategories(): ArrayList<Category> = categories ?: ArrayList()
}
