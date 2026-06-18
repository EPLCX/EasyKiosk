package com.adscreen.kiosk.manager

import android.content.Context
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
     */
    suspend fun forceImmersiveMode(): Boolean {
        val result = execRoot(Constants.ROOT_CMD_HIDE_NAV_BAR)
        if (!result.isSuccess) {
            Log.e(TAG, "Failed to set global immersive: ${result.err}")
        }
        return result.isSuccess
    }

    /**
     * Harden system UI to prevent status bar pull-down.
     */
    suspend fun hardenSystemUi(): Boolean {
        val cmds = listOf(
            Constants.ROOT_CMD_HIDE_STATUS_BAR,
            Constants.ROOT_CMD_CLEAR_QS_TILES,
            Constants.ROOT_CMD_DISABLE_HEADS_UP
        )
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
     */
    suspend fun restoreNavigationBar(): Boolean {
        val result = execRoot(Constants.ROOT_CMD_RESTORE_NAV_BAR)
        if (!result.isSuccess) {
            Log.e(TAG, "Failed to restore nav bar: ${result.err}")
        }
        return result.isSuccess
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
                Log.d(TAG, "Could not disable $pkg (may not exist): ${result.err}")
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
            if (!result.isSuccess) {
                allSuccess = false
                Log.d(TAG, "Could not enable $pkg: ${result.err}")
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
     */
    suspend fun enableScreenPinning(): Boolean {
        execRoot(Constants.ROOT_CMD_ENABLE_PINNING)

        // Force activate device admin, then whitelist ourselves
        setActiveAdmin()
        val result = execRoot(Constants.ROOT_CMD_LOCK_TASK_PACKAGES.format(context.packageName))
        if (!result.isSuccess) {
            Log.w(TAG, "set-lock-task-packages failed: ${result.err}")
        }
        return result.isSuccess
    }

    /**
     * Dismiss the screen-pinning confirmation dialog by tapping where
     * the "OK" / "开始" / "GOT IT" button appears.
     */
    suspend fun dismissPinningDialog(width: Int, height: Int) {
        // Tap slightly below centre where the confirm button usually sits
        val cmdCentre = Constants.ROOT_CMD_DISMISS_PINNING_DIALOG.format(width / 2, height / 2)
        val cmdLow = Constants.ROOT_CMD_DISMISS_PINNING_DIALOG.format(width / 2, (height * 0.6).toInt())
        execRoot(cmdCentre)
        kotlinx.coroutines.delay(200)
        execRoot(cmdLow)
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
     */
    suspend fun performElevatedSetup(): Boolean {
        if (!ensureRoot()) {
            Log.e(TAG, "Root required but not available — kiosk cannot enforce")
            return false
        }

        Log.i(TAG, "Starting elevated setup, root shell acquired…")

        // Always restore launchers first (clean up from previous crash)
        enableLaunchers()

        // Fullscreen mode
        forceImmersiveMode()
        hardenSystemUi()
        raiseProcessPriority()

        // Enable screen pinning (for startLockTask)
        enableScreenPinning()

        // Freeze launcher (default enabled in settings)
        val prefs = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        val freezeLauncher = prefs.getBoolean(Constants.KEY_FREEZE_LAUNCHER, true)
        if (freezeLauncher) {
            Log.i(TAG, "Freezing launchers")
            disableLaunchers()
        } else {
            Log.i(TAG, "Skipping launcher freeze")
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

        restoreNavigationBar()
        enableLaunchers()
        execRoot(Constants.ROOT_CMD_START_LAUNCHER)

        Log.i(TAG, "Elevated cleanup complete")
    }
}
