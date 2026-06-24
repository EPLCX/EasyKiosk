package com.adscreen.kiosk.manager

import android.app.Activity
import android.app.ActivityManager
import android.os.Build
import android.util.Log
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager

/**
 * Manages kiosk-mode system UI state: immersive mode, task locking,
 * and screen pinning.
 */
class KioskManager(private val activity: Activity) {

    companion object {
        private const val TAG = "KioskManager"
    }

    /**
     * Apply sticky immersive fullscreen flags.
     * Uses WindowInsetsController on API 30+ for reliable fullscreen.
     * Falls back to legacy SYSTEM_UI_FLAG approach on older devices.
     * Must be called in onWindowFocusChanged to re-apply on focus changes.
     */
    fun applyImmersiveFlags() {
        // FLAG_SECURE is intentionally NOT applied here — on some Android 11
        // devices it prevents WebView's SurfaceView from rendering (black
        // screen). It is applied separately via enableSecureFlag() after the
        // first page finishes loading.

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Modern API (Android 11+) — properly hides status + nav bars
            activity.window.setDecorFitsSystemWindows(false)
            activity.window.insetsController?.let { controller ->
                controller.hide(
                    WindowInsets.Type.statusBars() or
                    WindowInsets.Type.navigationBars() or
                    WindowInsets.Type.captionBar()
                )
                controller.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            // Legacy API (Android 7–10)
            val decorView = activity.window.decorView
            decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                )
        }

        // Keep screen on
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Clear task description from overview
        clearTaskDescription()
    }

    /**
     * Clear the task description so the recent tasks screen
     * shows minimal information.
     */
    private fun clearTaskDescription() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            activity.setTaskDescription(
                ActivityManager.TaskDescription(null, null, 0)
            )
        } else {
            @Suppress("DEPRECATION")
            activity.setTaskDescription(
                ActivityManager.TaskDescription(null, null)
            )
        }
    }

    /**
     * Set up the window to prevent screenshots/recording.
     */
    fun enableSecureFlag() {
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    fun disableSecureFlag() {
        activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
}
