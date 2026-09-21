package com.animatv.player.extension

import android.os.Build
import android.view.View
import android.view.Window
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import androidx.core.view.WindowCompat

@Suppress("DEPRECATION")
fun Window.setFullScreenFlags() {
    // Isi area poni/notch/kamera depan supaya tidak ada bar hitam di sisi layar
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        val params = this.attributes
        params.layoutInDisplayCutoutMode =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else {
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        this.attributes = params
    }

    // Konten app ikut mentok ke tepi layar (tidak didorong menjauhi poni/notch)
    WindowCompat.setDecorFitsSystemWindows(this, false)

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val controller = this.insetsController ?: return
        controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
        controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    } else {
        this.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
    }
}
