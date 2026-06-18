package com.adscreen.kiosk

import android.app.ActivityManager
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.adscreen.kiosk.databinding.ActivityMainBinding
import com.adscreen.kiosk.exit.ExitDialogFragment
import com.adscreen.kiosk.manager.KioskManager
import com.adscreen.kiosk.manager.RootManager
import com.adscreen.kiosk.util.Constants
import com.adscreen.kiosk.util.CryptoUtil
import com.adscreen.kiosk.util.NetworkUtil
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var cryptoUtil: CryptoUtil
    private lateinit var kioskManager: KioskManager
    private lateinit var rootManager: RootManager
    private lateinit var networkUtil: NetworkUtil

    private var networkCallback: NetworkUtil.NetworkCallbackAdapter? = null
    private var currentUrl: String = ""
    private var isExiting = false
    private var isLocking = false

    companion object {
        private const val TAG = "MainActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cryptoUtil = CryptoUtil(this)
        kioskManager = KioskManager(this)
        rootManager = RootManager(this)
        networkUtil = NetworkUtil(this)

        // Apply saved orientation (may trigger recreation — that's OK, the new
        // instance will handle it properly since all fields are initialized).
        applyOrientation()

        kioskManager.applyImmersiveFlags()
        currentUrl = cryptoUtil.getDecryptedUrl()

        setupWebView()
        setupSettingsButton()
        binding.btnRetry.setOnClickListener { reloadWebView() }
        registerNetworkMonitor()

        // Root enforcement (lifecycle-aware, auto-cancelled on destroy).
        lifecycleScope.launch {
            if (!rootManager.performElevatedSetup()) {
                showRootRequired()
                return@launch
            }

            // Start lock‑task with touch guard.
            lockWithOverlay()
            startLockTask()

            // Fallback: dismiss the pin‑confirmation dialog in case the
            // whitelist didn't take (some custom ROMs ignore dpm).
            delay(300)
            val w = resources.displayMetrics.widthPixels
            val h = resources.displayMetrics.heightPixels
            rootManager.dismissPinningDialog(w, h)

            unlockWithOverlay()
        }

        // Periodic enforcement: every 2 s re-apply immersive flags,
        // re-enter lock task if it was exited, and bring the app to
        // front if a third-party trick backgrounded it.
        lifecycleScope.launch {
            while (isActive) {
                kioskManager.applyImmersiveFlags()

                // Re-enter lock task if something kicked us out
                val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
                if (am.lockTaskModeState == ActivityManager.LOCK_TASK_MODE_NONE && !isLocking) {
                    startLockTask()
                }

                if (!isExiting && !lifecycle.currentState.isAtLeast(
                        Lifecycle.State.RESUMED
                    )
                ) {
                    rootManager.bringToFront()
                }

                delay(2000)
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            kioskManager.applyImmersiveFlags()
        } else if (!isExiting) {
            // Root enforcement: immediately bring app back to front
            lifecycleScope.launch {
                rootManager.bringToFront()
            }
        }
    }

    override fun onBackPressed() {
        // Block back button entirely — kiosk mode requirement
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // Block physical keys that could exit kiosk mode
        when (keyCode) {
            KeyEvent.KEYCODE_HOME,
            KeyEvent.KEYCODE_APP_SWITCH,
            KeyEvent.KEYCODE_MENU -> {
                return true // Consumed, no effect
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onUserLeaveHint() {
        // Root enforcement: prevent app from being sent to background
        super.onUserLeaveHint()
        if (!isExiting) {
            lifecycleScope.launch {
                rootManager.bringToFront()
            }
            kioskManager.applyImmersiveFlags()
        }
    }

    override fun onStop() {
        super.onStop()
        if (!isExiting) {
            // Root enforcement: bring app back if it somehow got backgrounded
            lifecycleScope.launch {
                rootManager.bringToFront()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        applyOrientation()
        kioskManager.applyImmersiveFlags()

        // Re-enter lock task after returning from settings etc.
        val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        if (am.lockTaskModeState == ActivityManager.LOCK_TASK_MODE_NONE) {
            lifecycleScope.launch {
                lockWithOverlay()
                startLockTask()
                delay(300)
                val w = resources.displayMetrics.widthPixels
                val h = resources.displayMetrics.heightPixels
                rootManager.dismissPinningDialog(w, h)
                unlockWithOverlay()
            }
        }

        // Reload WebView if URL was changed in settings
        val savedUrl = cryptoUtil.getDecryptedUrl()
        if (savedUrl != currentUrl) {
            currentUrl = savedUrl
            binding.webview.loadUrl(currentUrl)
        }
    }

    private fun applyOrientation() {
        val orientation = getSharedPreferences(Constants.PREFS_NAME, MODE_PRIVATE)
            .getString(Constants.KEY_ORIENTATION, "landscape") ?: "landscape"
        val target = when (orientation) {
            "portrait" -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            "sensor" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR
            else -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        if (requestedOrientation != target) {
            requestedOrientation = target
        }
    }

    override fun onDestroy() {
        networkCallback?.let { networkUtil.unregisterNetworkCallback(it) }
        super.onDestroy()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val webView = binding.webview

        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false

            // Mixed content
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

            // Performance
            setRenderPriority(android.webkit.WebSettings.RenderPriority.HIGH)
            cacheMode = android.webkit.WebSettings.LOAD_DEFAULT

            // Viewport
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = false
            displayZoomControls = false

            // Autoplay
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                mediaPlaybackRequiresUserGesture = false
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                binding.progressBar.visibility = View.VISIBLE
                Log.d(TAG, "Page loading: $url")
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                binding.progressBar.visibility = View.GONE
                binding.networkErrorOverlay.visibility = View.GONE
                Log.d(TAG, "Page loaded: $url")
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                // Only show error overlay for main frame errors
                if (request?.isForMainFrame == true) {
                    binding.progressBar.visibility = View.GONE
                    binding.networkErrorOverlay.visibility = View.VISIBLE
                    Log.e(TAG, "WebView error: ${error?.description}")
                }
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                // Allow all navigation within the WebView
                return false
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                binding.progressBar.progress = newProgress
                if (newProgress == 100) {
                    binding.progressBar.visibility = View.GONE
                }
            }
        }

        // Load the target URL
        webView.loadUrl(currentUrl)
    }

    private fun reloadWebView() {
        binding.networkErrorOverlay.visibility = View.GONE
        binding.webview.loadUrl(currentUrl)
    }

    private fun setupSettingsButton() {
        binding.btnSettings.setOnClickListener {
            val dialog = ExitDialogFragment()
                .setTitle("管理员验证")
                .setHint("请输入管理员密码")
                .setConfirmButtonText("确认")
                .setOnExitConfirmed {
                    val intent = Intent(this, SettingsActivity::class.java)
                    startActivity(intent)
                }
            dialog.show(supportFragmentManager, ExitDialogFragment.TAG_SETTINGS)
        }
    }

    private fun registerNetworkMonitor() {
        networkCallback = NetworkUtil.NetworkCallbackAdapter(
            onAvailable = {
                runOnUiThread {
                    if (binding.networkErrorOverlay.visibility == View.VISIBLE) {
                        reloadWebView()
                    }
                }
            }
        )
        networkUtil.registerNetworkCallback(networkCallback!!)
    }

    private fun showRootRequired() {
        AlertDialog.Builder(this)
            .setTitle("需要 Root 权限")
            .setMessage("本应用需要 Root 权限来启用全屏锁定模式。\n\n请确保设备已获取 Root 权限，并允许本应用的 Root 请求。")
            .setCancelable(false)
            .setPositiveButton("退出") { _, _ -> finishAffinity() }
            .show()
    }

    private var overlayView: View? = null

    @SuppressLint("ClickableViewAccessibility")
    private fun lockWithOverlay() {
        isLocking = true

        // In-app overlay
        binding.touchOverlay.visibility = View.VISIBLE
        binding.touchOverlay.bringToFront()
        binding.webview.setOnTouchListener { _, _ -> true }

        // System overlay: only cover bottom ~25% where "不用了" sits,
        // so auto-tap at centre (50%,50%‑60%) reaches confirm button.
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val display = wm.defaultDisplay
        val metrics = android.util.DisplayMetrics()
        display.getRealMetrics(metrics)
        val height = (metrics.heightPixels * 0.35).toInt()
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            height,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    or WindowManager.LayoutParams.FLAG_FULLSCREEN,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.BOTTOM
        val overlay = View(this)
        overlay.setBackgroundColor(0x00000000)
        overlay.setOnTouchListener { _, _ -> true }
        overlayView = overlay
        try {
            wm.addView(overlay, params)
        } catch (e: Exception) {
            Log.w(TAG, "System overlay not available", e)
        }
    }

    private fun unlockWithOverlay() {
        isLocking = false
        binding.touchOverlay.visibility = View.GONE
        binding.webview.setOnTouchListener(null)

        overlayView?.let {
            try {
                (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it)
            } catch (_: Exception) {}
            overlayView = null
        }
    }

    private fun performSafeExit() {
        isExiting = true

        // 1. Exit lock task mode
        try {
            stopLockTask()
        } catch (_: Exception) {}

        // 2. Restore system UI via root
        lifecycleScope.launch {
            rootManager.performElevatedCleanup()
        }

        // 3. Clear secure flag so screenshots work after exit
        kioskManager.disableSecureFlag()

        // 4. Finish with affinity
        finishAffinity()
    }
}
