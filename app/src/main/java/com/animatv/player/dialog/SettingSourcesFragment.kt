package com.animatv.player.dialog

import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.DividerItemDecoration
import com.developer.filepicker.model.DialogConfigs
import com.developer.filepicker.model.DialogProperties
import com.animatv.player.R
import com.animatv.player.adapter.SourcesAdapter
import com.animatv.player.databinding.SettingSourcesFragmentBinding
import com.animatv.player.extension.isLinkUrl
import com.animatv.player.extra.SourceChecker
import com.animatv.player.model.Source
import java.io.File
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import com.animatv.player.extra.AdminManager

class SettingSourcesFragment: Fragment() {
    companion object {
        var sources: ArrayList<Source>? = null
    }

    @Suppress("DEPRECATION")
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {

        // Tab Playlist dikunci kode admin. Sumber sudah tertanam permanen
        // di kode (lihat Preferences.kt), jadi pengguna biasa tidak perlu
        // membuka tab ini — hanya admin yang boleh melihat/mengubahnya.
        val root = FrameLayout(requireContext())

        if (AdminManager.isAdminUnlocked) {
            root.addView(buildSourcesView(inflater, container))
        } else {
            root.addView(buildLockedView(inflater, root))
        }

        return root
    }

    /** Tampilan kunci: minta kode admin sebelum menampilkan daftar source. */
    private fun buildLockedView(inflater: LayoutInflater, root: FrameLayout): View {
        val context = requireContext()

        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(80, 80, 80, 80)
        }

        val label = android.widget.TextView(context).apply {
            text = "Playlist terkunci.\nMasukkan kode admin untuk mengelola sumber playlist."
            setTextColor(0xFFCCCCCC.toInt())
            gravity = Gravity.CENTER
            textSize = 15f
            setPadding(0, 0, 0, 40)
        }

        val input = EditText(context).apply {
            hint = "Kode admin"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }

        val button = Button(context).apply {
            text = "Buka"
            setOnClickListener {
                if (AdminManager.tryUnlockAdmin(input.text.toString().trim())) {
                    root.removeAllViews()
                    root.addView(buildSourcesView(inflater, root))
                } else {
                    Toast.makeText(context, "Kode salah!", Toast.LENGTH_SHORT).show()
                }
            }
        }

        layout.addView(label)
        layout.addView(input)
        layout.addView(button)

        return layout
    }

    /** Tampilan asli daftar & pengaturan source (kode lama, tidak diubah). */
    private fun buildSourcesView(inflater: LayoutInflater, container: ViewGroup?): View {
        val binding = SettingSourcesFragmentBinding.inflate(inflater, container, false)

        val adapter = SourcesAdapter(sources)
        binding.sourcesAdapter = adapter
        binding.rvSources.addItemDecoration(DividerItemDecoration(context, DividerItemDecoration.VERTICAL))

        val properties = DialogProperties().apply {
            extensions = arrayOf("json","m3u")
            selection_mode = DialogConfigs.MULTI_MODE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                root = File(Environment.getExternalStorageDirectory().path)
                error_dir = File(Environment.getExternalStorageDirectory().path)
                offset  = File(Environment.getExternalStorageDirectory().path)
            } else {
                root = File("/")
                offset  = File("/mnt/sdcard:/storage")
            }
        }

        val filePicker = FilePickerDialog(requireContext()).apply {
            setTitle(getString(R.string.title_select_file_json))
            setProperties(properties)
            setDialogSelectionListener {
                for (path in it){
                    adapter.addItem(Source().apply {
                        this.path = path
                        active = true
                    })
                }
                sources = adapter.getItems()
            }
        }

        binding.btnPick.setOnClickListener {
            filePicker.show()
        }

        val clipboard = context?.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        var clipText = clipboard.primaryClip?.getItemAt(0)?.text.toString()
        binding.inputSource.apply {
            setText(if (clipText.isLinkUrl()) clipText.trim() else "")
            setOnEditorActionListener { _, i, k ->
                if (i == EditorInfo.IME_ACTION_DONE || k.keyCode == KeyEvent.KEYCODE_ENTER) {
                    binding.btnAdd.performClick(); true
                }
                else false
            }
        }

        binding.btnAdd.setOnClickListener {
            val inputSource = binding.inputSource
            var input = inputSource.text.toString()
            if (input.isBlank()) {
                clipText = clipboard.primaryClip?.getItemAt(0)?.text.toString()
                if (clipText.isLinkUrl()) input = clipText
                else return@setOnClickListener
            }
            else if (!input.isLinkUrl()) return@setOnClickListener

            it.isEnabled = false
            inputSource.isEnabled = false
            inputSource.setText(R.string.checking_url)

            val source = Source().apply {
                path = input
                active = true
            }

            SourceChecker().set(source, object: SourceChecker.Result{
                override fun onCheckResult(result: Boolean) {
                    it.isEnabled = true
                    inputSource.text?.clear()
                    inputSource.isEnabled =true
                    if (result) {
                        adapter.addItem(source)
                        sources = adapter.getItems()
                    }
                    else {
                        inputSource.setText(input)
                        Toast.makeText(context, R.string.link_error, Toast.LENGTH_SHORT).show()
                    }
                }
            }).run()
        }

        return binding.root
    }
}
