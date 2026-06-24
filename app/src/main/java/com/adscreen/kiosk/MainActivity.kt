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
    private var secureFlagApplied = false

    companion object {
        private const val TAG = "MainActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Reject callers that are not our own app, system, shell, or root.
        // This prevents third-party apps on the same device from launching
        // MainActivity to interfere with kiosk mode (e.g. break lock-task).
        // Note: getLaunchedFromUid() is wrapped in try-catch because some
        // custom Android 11 ROMs (Chinese vendor builds) removed this method
        // from the Activity class, causing a crash on startup.
        val callerUid = android.os.Process.myUid()
        val callingUid = try { getLaunchedFromUid() } catch (_: Error) { null }
        if (callingUid != null && callingUid != callerUid
            && callingUid != android.os.Process.ROOT_UID
            && callingUid != android.os.Process.SYSTEM_UID
            && callingUid != android.os.Process.SHELL_UID) {
            Log.w(TAG, "Rejected launch from untrusted UID: $callingUid")
            finish()
            return
        }

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
        Log.d(TAG, "Target URL: $currentUrl")

        setupWebView()
        setupSettingsButton()
        binding.btnRetry.setOnClickListener { reloadWebView() }
        registerNetworkMonitor()

        // Root enforcement (lifecycle-aware, auto-cancelled on destroy).
        lifecycleScope.launch {
            // Obtain root shell immediately at startup, before any setup
            rootManager.ensureRoot()

            if (!rootManager.performElevatedSetup()) {
                showRootRequired()
                return@launch
            }

            // Start lock‑task with touch guard.
            lockWithOverlay()
            try {
                startLockTask()

                // Fallback: dismiss the pin‑confirmation dialog in case the
                // whitelist didn't take (some custom ROMs ignore dpm).
                delay(300)
                // Blank the WebView so no input tap can hit page content
                binding.webview.stopLoading()
                binding.webview.loadUrl("about:blank")
                delay(500)
                val w = resources.displayMetrics.widthPixels
                val h = resources.displayMetrics.heightPixels
                rootManager.dismissPinningDialog(w, h)
                binding.webview.loadUrl(currentUrl)
            } finally {
                unlockWithOverlay()
            }
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
                try {
                    startLockTask()
                    delay(300)
                    binding.webview.stopLoading()
                    binding.webview.loadUrl("about:blank")
                    delay(500)
                    val w = resources.displayMetrics.widthPixels
                    val h = resources.displayMetrics.heightPixels
                    rootManager.dismissPinningDialog(w, h)
                    binding.webview.loadUrl(currentUrl)
                } finally {
                    unlockWithOverlay()
                }
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

            // Block mixed content: HTTPS pages must not load HTTP subresources
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW

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
                // Apply FLAG_SECURE after the first successful page load.
                // On some Android 11 devices, setting it before WebView
                // initialization causes a black rendered surface.
                if (!secureFlagApplied) {
                    secureFlagApplied = true
                    kioskManager.enableSecureFlag()
                }
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

            override fun onReceivedSslError(
                view: WebView?,
                handler: android.webkit.SslErrorHandler?,
                error: android.net.http.SslError?
            ) {
                Log.e(TAG, "SSL error: ${error?.toString()}")
                binding.progressBar.visibility = View.GONE
                binding.networkErrorOverlay.visibility = View.VISIBLE
                // Do NOT call handler.proceed() — block insecure pages
                handler?.cancel()
            }

            override fun onReceivedHttpError(
                view: WebView?,
                request: WebResourceRequest?,
                errorResponse: android.webkit.WebResourceResponse?
            ) {
                if (request?.isForMainFrame == true) {
                    Log.e(TAG, "HTTP error: ${errorResponse?.statusCode}")
                    binding.progressBar.visibility = View.GONE
                    binding.networkErrorOverlay.visibility = View.VISIBLE
                }
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val url = request?.url ?: return true
                val scheme = url.scheme
                // Block any non-http(s) navigation silently
                if (scheme != "http" && scheme != "https") {
                    Log.w(TAG, "Blocked non-http navigation: $url")
                    return true
                }
                return false
            }

            @Suppress("DEPRECATION")
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                url: String?
            ): Boolean {
                if (url == null) return true
                val scheme = android.net.Uri.parse(url).scheme
                if (scheme != "http" && scheme != "https") {
                    Log.w(TAG, "Blocked non-http navigation: $url")
                    return true
                }
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
            .setTitle("权限不足")
            .setMessage("本应用需要 Root 权限或 Device Owner 权限来启用全屏锁定模式。\n\n请确保设备已 Root 并允许授权，或通过 ADB 设置为 Device Owner：\n\ndpm set-device-owner com.adscreen.kiosk/.manager.DeviceAdminReceiverImpl")
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

        // Full-screen system overlay above all app content.
        // Without FLAG_NOT_TOUCH_MODAL the overlay captures every user
        // touch, preventing the lock-task confirmation's "取消" button
        // from being tapped — only our root-injected input tap reaches
        // the dialog to confirm.
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    or WindowManager.LayoutParams.FLAG_FULLSCREEN,
            PixelFormat.TRANSLUCENT
        )
        val overlay = View(this)
        overlay.setBackgroundColor(0x00000000)
        overlay.setOnTouchListener { _, _ -> true }
        overlayView = overlay
        try {
            wm.addView(overlay, params)
        } catch (e: Exception) {
            Log.w(TAG, "Full-screen overlay not available", e)
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

}
