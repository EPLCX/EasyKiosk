package com.adscreen.kiosk.manager

import android.content.Context
import android.os.Build
import android.util.Log
import com.adscreen.kiosk.util.Constants
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Manages root-level operations using libsu.
 * All root operations are performed asynchronously on IO dispatcher.
 */
class RootManager(private val context: Context) {

    companion object {
        private const val TAG = "RootManager"
    }

    /**
     * Check if device has root access.
     */
    suspend fun hasRoot(): Boolean = withContext(Dispatchers.IO) {
        try {
            Shell.cmd("which su").exec().isSuccess
        } catch (e: Exception) {
            Log.e(TAG, "Root check failed", e)
            false
        }
    }

    /**
     * Request root shell (triggers Superuser prompt if not already granted).
     */
    suspend fun requestRootShell(): Boolean = withContext(Dispatchers.IO) {
        try {
            val shell = Shell.getShell()
            shell.isRoot
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get root shell", e)
            false
        }
    }

    /**
     * Execute a root shell command and return the result.
     */
    private suspend fun execRoot(cmd: String): Shell.Result = withContext(Dispatchers.IO) {
        Shell.cmd(cmd).exec()
    }

    /**
     * Apply global immersive mode via system settings.
     * Skipped on Android 11+ (API 30+) because policy_control breaks screen
     * rendering on modern Android — the Activity-level WindowInsetsController
     * approach in KioskManager is sufficient.
     */
    suspend fun forceImmersiveMode(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Log.i(TAG, "Skipping policy_control immersive on API ${Build.VERSION.SDK_INT}")
            return true
        }
        val result = execRoot(Constants.ROOT_CMD_HIDE_NAV_BAR)
        if (!result.isSuccess) {
            Log.e(TAG, "Failed to set global immersive: ${result.err}")
        }
        return result.isSuccess
    }

    /**
     * Harden system UI to prevent status bar pull-down.
     * On Android 11+ policy_control is skipped to avoid black screen.
     */
    suspend fun hardenSystemUi(): Boolean {
        val cmds = mutableListOf<String>()
        // policy_control breaks rendering on API 30+
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            cmds.add(Constants.ROOT_CMD_HIDE_STATUS_BAR)
        }
        cmds.add(Constants.ROOT_CMD_DISABLE_HEADS_UP)
        var allOk = true
        for (cmd in cmds) {
            val result = execRoot(cmd)
            if (!result.isSuccess) allOk = false
        }
        return allOk
    }

    /**
     * Grant SYSTEM_ALERT_WINDOW to ourselves via root.
     */
    suspend fun grantOverlayPermission(): Boolean {
        val cmd = Constants.ROOT_CMD_GRANT_OVERLAY.format(context.packageName)
        val result = execRoot(cmd)
        if (!result.isSuccess) {
            Log.w(TAG, "Failed to grant overlay permission: ${result.err}")
        }
        return result.isSuccess
    }

    /**
     * Restore navigation bar to default behavior.
     * Tries both the empty-string assignment and the delete command
     * for maximum ROM compatibility.
     */
    suspend fun restoreNavigationBar(): Boolean {
        var ok = execRoot(Constants.ROOT_CMD_RESTORE_NAV_BAR).isSuccess
        if (!ok) Log.w(TAG, "nav bar: put \"\" failed, trying delete…")
        ok = execRoot(Constants.ROOT_CMD_DELETE_POLICY_CONTROL).isSuccess || ok
        if (!ok) Log.e(TAG, "Failed to restore nav bar (both methods)")
        return ok
    }

    /**
     * Raise our process priority to the maximum (-20).
     */
    suspend fun raiseProcessPriority(): Boolean {
        val pid = android.os.Process.myPid()
        val result = execRoot(Constants.ROOT_CMD_RENICE.format(pid))
        if (!result.isSuccess) {
            Log.e(TAG, "Failed to renice: ${result.err}")
        }
        return result.isSuccess
    }

    /**
     * Disable known launcher packages to prevent them from appearing.
     */
    suspend fun disableLaunchers(): Boolean {
        val launchers = Constants.LAUNCHER_PACKAGES.split(":")
        var allSuccess = true
        for (pkg in launchers) {
            val cmd = Constants.ROOT_CMD_DISABLE_LAUNCHER.format(pkg)
            val result = execRoot(cmd)
            if (result.isSuccess) {
                Log.i(TAG, "Disabled launcher: $pkg")
            } else {
                allSuccess = false
                Log.w(TAG, "Failed to disable launcher $pkg: ${result.err}")
            }
        }
        return allSuccess
    }

    /**
     * Re-enable launcher packages (on exit).
     */
    suspend fun enableLaunchers(): Boolean {
        val launchers = Constants.LAUNCHER_PACKAGES.split(":")
        var allSuccess = true
        for (pkg in launchers) {
            val cmd = Constants.ROOT_CMD_ENABLE_LAUNCHER.format(pkg)
            val result = execRoot(cmd)
            if (result.isSuccess) {
                Log.i(TAG, "Enabled launcher: $pkg")
            } else {
                allSuccess = false
                Log.w(TAG, "Failed to enable launcher $pkg: ${result.err}")
            }
        }
        return allSuccess
    }

    /**
     * Register ourselves as an active device admin via root (no user prompt).
     */
    suspend fun setActiveAdmin(): Boolean {
        val result = execRoot(Constants.ROOT_CMD_SET_ACTIVE_ADMIN)
        if (!result.isSuccess) {
            Log.w(TAG, "set-active-admin failed: ${result.err}")
        }
        return result.isSuccess
    }

    /**
     * Enable screen pinning in system settings and whitelist our app
     * so startLockTask() can be used without a device admin prompt.
     *
     * Uses two strategies:
     *  1. Device Owner (DPM API) — no root needed, preferred when available.
     *  2. Root shell fallback — via su and dpm/pm commands.
     */
    suspend fun enableScreenPinning(): Boolean {
        val securityManager = SecurityManager(context)

        if (securityManager.isDeviceOwner()) {
            Log.i(TAG, "Device Owner detected — using DPM API for lock-task")
            val pkgOk = securityManager.setLockTaskPackages(context.packageName)
            if (pkgOk) {
                Log.i(TAG, "Lock-task packages whitelisted via DPM")
                return true
            }
            Log.w(TAG, "DPM path failed (packages=$pkgOk), falling back to root")
        } else {
            Log.i(TAG, "Not device owner — using root for lock-task setup")
        }

        // Root fallback path
        execRoot(Constants.ROOT_CMD_ENABLE_PINNING)
        setActiveAdmin()
        val result = execRoot(Constants.ROOT_CMD_LOCK_TASK_PACKAGES.format(context.packageName))
        if (!result.isSuccess) {
            Log.w(TAG, "Root fallback: set-lock-task-packages failed: ${result.err}")
        }
        return result.isSuccess
    }

    /**
     * Dismiss the screen-pinning confirmation dialog by tapping where
     * the "OK" / "开始" / "GOT IT" button is expected.
     *
     * Tries a grid of positions across the dialog area instead of a single
     * fixed coordinate, because button placement varies by ROM and screen
     * resolution. Falls back to KEYCODE_ENTER if taps don't work.
     */
    suspend fun dismissPinningDialog(width: Int, height: Int) {
        // Build a list of candidate tap positions covering the area
        // where the confirm button typically appears across various ROMs
        val taps = mutableListOf<Pair<Int, Int>>()

        // Center row (most ROMs: confirm button at ~45-50% from top)
        for (xFraction in listOf(0.40, 0.50, 0.60)) {
            taps.add((width * xFraction).toInt() to (height * 0.48).toInt())
        }

        // Middle-lower row (some ROMs: button slightly lower)
        for (xFraction in listOf(0.40, 0.50, 0.60)) {
            taps.add((width * xFraction).toInt() to (height * 0.55).toInt())
        }

        // Lower row (fallback: some ROMs put it near the bottom)
        for (xFraction in listOf(0.45, 0.50, 0.55)) {
            taps.add((width * xFraction).toInt() to (height * 0.62).toInt())
        }

        // Try all tap positions with short delays
        for ((x, y) in taps) {
            execRoot(Constants.ROOT_CMD_DISMISS_PINNING_DIALOG.format(x, y))
            kotlinx.coroutines.delay(150)
        }

        // Last resort: send enter key event (works on some AOSP-based ROMs)
        execRoot("input keyevent KEYCODE_ENTER")
        kotlinx.coroutines.delay(100)
        execRoot("input keyevent KEYCODE_DPAD_CENTER")
    }

    /**
     * Check root availability and request root shell.
     */
    suspend fun ensureRoot(): Boolean {
        if (!hasRoot()) {
            Log.w(TAG, "No root access available")
            return false
        }
        return requestRootShell()
    }

    /**
     * Execute all elevated setup operations.
     *
     * Uses two privilege paths:
     *  - **Device Owner** (DPM API): enables lock-task without root.
     *  - **Root shell** (su): for policy_control (global immersive), renice,
     *    and launcher freeze.
     *
     * When root is unavailable but device-owner is set, lock-task still works
     * but global system UI hiding (policy_control) is skipped — the app still
     * enforces immersive via Activity-level WindowInsetsController flags.
     */
    suspend fun performElevatedSetup(): Boolean {
        val securityManager = SecurityManager(context)
        val isDevOwner = securityManager.isDeviceOwner()
        val hasRoot = ensureRoot()

        if (!hasRoot && !isDevOwner) {
            Log.e(TAG, "Neither root nor device-owner available — kiosk cannot enforce")
            return false
        }

        Log.i(TAG, "Starting elevated setup (root=$hasRoot, deviceOwner=$isDevOwner)…")

        // Always restore launchers first (clean up from previous crash) — needs root
        if (hasRoot) enableLaunchers()

        // CRITICAL: Clean up any stale policy_control that may have been left by a
        // previous run (or a previous app version). This setting persists across
        // reboots and on Android 11+ it causes a black screen / SystemUI crash.
        if (hasRoot && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            restoreNavigationBar()
        }

        // Fullscreen mode — policy_control requires root
        if (hasRoot) {
            grantOverlayPermission()
            forceImmersiveMode()
            hardenSystemUi()
            raiseProcessPriority()
        } else {
            Log.i(TAG, "No root, skipping global immersive — using Activity-level only")
        }

        // Screen pinning / lock-task — prefers DPM, falls back to root
        enableScreenPinning()

        // Freeze launcher (default enabled in settings) — needs root
        if (hasRoot) {
            val prefs = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
            val freezeLauncher = prefs.getBoolean(Constants.KEY_FREEZE_LAUNCHER, true)
            if (freezeLauncher) {
                Log.i(TAG, "Freezing launchers")
                disableLaunchers()
            } else {
                Log.i(TAG, "Skipping launcher freeze")
            }
        }

        Log.i(TAG, "Elevated setup complete")
        return true
    }

    /**
     * Bring the kiosk app to the foreground immediately via root.
     */
    suspend fun bringToFront(): Boolean = withContext(Dispatchers.IO) {
        val cmd = "am start -n ${context.packageName}/.MainActivity --activityflags 0x10200000"
        val result = Shell.cmd(cmd).exec()
        if (!result.isSuccess) {
            Log.e(TAG, "Failed to bring app to front: ${result.err}")
        }
        result.isSuccess
    }

    /**
     * Cleanup after session ends.
     */
    suspend fun performElevatedCleanup() {
        if (!hasRoot()) return

        Log.i(TAG, "Starting elevated cleanup…")

        // Restore nav bar — retry once on failure
        if (!restoreNavigationBar()) {
            Log.w(TAG, "nav bar restore failed on first attempt, retrying…")
            kotlinx.coroutines.delay(500)
            restoreNavigationBar()
        }

        if (!enableLaunchers()) {
            Log.w(TAG, "some launchers could not be re-enabled")
        }

        // Re-enable SystemUI in case it was disabled by external tools
        execRoot(Constants.ROOT_CMD_ENABLE_SYSTEMUI)
            .let { if (!it.isSuccess) Log.w(TAG, "enable SystemUI: ${it.err}") }

        val startResult = execRoot(Constants.ROOT_CMD_START_LAUNCHER)
        if (!startResult.isSuccess) {
            Log.e(TAG, "failed to launch home: ${startResult.err}")
        }

        Log.i(TAG, "Elevated cleanup complete")
    }
}
