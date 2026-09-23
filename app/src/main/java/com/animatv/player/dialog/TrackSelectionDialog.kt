/*
 * Copyright (C) 2019 The Android Open Source Project
 * Licensed under the Apache License, Version 2.0
 *
 * Ditulis ulang untuk Media3 (androidx.media3) API.
 * Media3 memakai model Tracks/TrackSelectionOverride berbasis TrackGroup,
 * bukan lagi MappingTrackSelector.MappedTrackInfo seperti ExoPlayer2 lama.
 */
package com.animatv.player.dialog

import android.app.Dialog
import android.content.DialogInterface
import android.content.res.Resources
import android.os.Bundle
import android.util.SparseArray
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.appcompat.app.AppCompatDialog
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.FragmentPagerAdapter
import androidx.media3.common.C
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.TrackSelectionView
import androidx.media3.ui.TrackSelectionView.TrackSelectionListener
import androidx.viewpager.widget.ViewPager
import com.google.android.material.tabs.TabLayout
import com.animatv.player.R

class TrackSelectionDialog : DialogFragment() {

    private val tabFragments: SparseArray<TrackSelectionViewFragment> = SparseArray()
    private val tabTrackTypes: ArrayList<Int> = ArrayList()
    private var titleId = 0
    private lateinit var onClickListener: DialogInterface.OnClickListener
    private lateinit var onDismissListener: DialogInterface.OnDismissListener

    private fun init(
        titleId: Int,
        tracks: Tracks,
        trackSelector: DefaultTrackSelector,
        onClickListener: DialogInterface.OnClickListener,
        onDismissListener: DialogInterface.OnDismissListener
    ) {
        this.titleId = titleId
        this.onClickListener = onClickListener
        this.onDismissListener = onDismissListener

        val parameters = trackSelector.parameters

        // Kelompokkan Tracks.Group berdasarkan tipe (video/audio/teks) -
        // pengganti loop per-renderer di MappedTrackInfo versi lama.
        val groupsByType = tracks.groups
            .filter {
                it.type == C.TRACK_TYPE_VIDEO ||
                    it.type == C.TRACK_TYPE_AUDIO ||
                    it.type == C.TRACK_TYPE_TEXT
            }
            .groupBy { it.type }

        var tabIndex = 0
        for ((trackType, groups) in groupsByType) {
            if (groups.isEmpty()) continue

            val isDisabled = parameters.disabledTrackTypes.contains(trackType)
            val overridesForType = TrackSelectionView.filterOverrides(
                parameters.overrides, groups, /* allowMultipleOverrides = */ false
            )

            val tabFragment = TrackSelectionViewFragment()
            tabFragment.initFragment(groups, isDisabled, overridesForType)
            tabFragments.put(tabIndex, tabFragment)
            tabTrackTypes.add(trackType)
            tabIndex++
        }
    }

    fun getIsDisabled(index: Int): Boolean {
        return tabFragments[index]?.isDisabled ?: false
    }

    fun getOverrides(index: Int): Map<TrackGroup, TrackSelectionOverride> {
        return tabFragments[index]?.overrides ?: emptyMap()
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return AppCompatDialog(requireContext(), R.style.TrackSelectionDialogThemeOverlay).apply {
            setTitle(titleId)
        }
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        onDismissListener.onDismiss(dialog)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val dialogView = inflater.inflate(R.layout.track_selection_dialog, container, false)

        val viewPager = dialogView.findViewById<ViewPager>(R.id.track_selection_dialog_view_pager)
        viewPager.adapter = FragmentAdapter(childFragmentManager)

        val tabLayout = dialogView.findViewById<TabLayout>(R.id.track_selection_dialog_tab_layout)
        tabLayout.setupWithViewPager(viewPager)
        tabLayout.visibility = if (tabFragments.size() > 1) View.VISIBLE else View.GONE

        dialogView.findViewById<Button>(R.id.track_selection_dialog_cancel_button)
            .setOnClickListener { dismiss() }

        dialogView.findViewById<Button>(R.id.track_selection_dialog_ok_button)
            .setOnClickListener {
                onClickListener.onClick(dialog, DialogInterface.BUTTON_POSITIVE)
                dismiss()
            }

        return dialogView
    }

    private inner class FragmentAdapter(fragmentManager: FragmentManager) :
        FragmentPagerAdapter(fragmentManager, BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT) {
        override fun getItem(position: Int): Fragment = tabFragments.valueAt(position)
        override fun getCount(): Int = tabFragments.size()
        override fun getPageTitle(position: Int): CharSequence =
            getTrackTypeString(resources, tabTrackTypes[position])
    }

    class TrackSelectionViewFragment : Fragment(), TrackSelectionListener {

        private var trackGroups: List<Tracks.Group> = emptyList()

        var isDisabled = false
        var overrides: Map<TrackGroup, TrackSelectionOverride> = emptyMap()

        fun initFragment(
            trackGroups: List<Tracks.Group>,
            initialIsDisabled: Boolean,
            initialOverrides: Map<TrackGroup, TrackSelectionOverride>
        ) {
            this.trackGroups = trackGroups
            this.isDisabled = initialIsDisabled
            this.overrides = initialOverrides
        }

        override fun onCreateView(
            inflater: LayoutInflater,
            container: ViewGroup?,
            savedInstanceState: Bundle?
        ): View? {
            val rootView = inflater.inflate(
                androidx.media3.ui.R.layout.exo_track_selection_dialog,
                container, false
            )
            val trackSelectionView: TrackSelectionView =
                rootView.findViewById(androidx.media3.ui.R.id.exo_track_selection_view)

            trackSelectionView.setShowDisableOption(true)
            trackSelectionView.setAllowMultipleOverrides(false)
            trackSelectionView.setAllowAdaptiveSelections(true)

            trackSelectionView.init(
                trackGroups,
                isDisabled,
                overrides,
                null,
                this
            )
            return rootView
        }

        override fun onTrackSelectionChanged(
            isDisabled: Boolean,
            overrides: MutableMap<TrackGroup, TrackSelectionOverride>
        ) {
            this.isDisabled = isDisabled
            this.overrides = overrides
        }

        init {
            @Suppress("DEPRECATION")
            retainInstance = true
        }
    }

    companion object {

        fun willHaveContent(tracks: Tracks?): Boolean {
            if (tracks == null) return false
            return tracks.groups.any {
                it.type == C.TRACK_TYPE_VIDEO ||
                    it.type == C.TRACK_TYPE_AUDIO ||
                    it.type == C.TRACK_TYPE_TEXT
            }
        }

        fun createForTrackSelector(
            tracks: Tracks,
            trackSelector: DefaultTrackSelector?,
            onDismissListener: DialogInterface.OnDismissListener
        ): TrackSelectionDialog {
            requireNotNull(trackSelector)
            val dialog = TrackSelectionDialog()
            dialog.init(
                titleId = R.string.track_selection_title,
                tracks = tracks,
                trackSelector = trackSelector,
                onClickListener = { _, _ ->
                    val builder = trackSelector.parameters.buildUpon()
                    for (i in dialog.tabTrackTypes.indices) {
                        val trackType = dialog.tabTrackTypes[i]
                        builder.setTrackTypeDisabled(trackType, dialog.getIsDisabled(i))
                        builder.clearOverridesOfType(trackType)
                        dialog.getOverrides(i).values.forEach { override ->
                            builder.addOverride(override)
                        }
                    }
                    trackSelector.setParameters(builder)
                },
                onDismissListener = onDismissListener
            )
            return dialog
        }

        private fun getTrackTypeString(resources: Resources, trackType: Int): String {
            return when (trackType) {
                C.TRACK_TYPE_VIDEO -> resources.getString(
                    androidx.media3.ui.R.string.exo_track_selection_title_video
                )
                C.TRACK_TYPE_AUDIO -> resources.getString(
                    androidx.media3.ui.R.string.exo_track_selection_title_audio
                )
                C.TRACK_TYPE_TEXT -> resources.getString(
                    androidx.media3.ui.R.string.exo_track_selection_title_text
                )
                else -> throw IllegalArgumentException("Unknown track type: $trackType")
            }
        }
    }

    init {
        @Suppress("DEPRECATION")
        retainInstance = true
    }
}
