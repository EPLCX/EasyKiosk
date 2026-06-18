package com.adscreen.kiosk.manager

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log

/**
 * Manages DevicePolicyManager operations for screen pinning fallback
 * and lock-task mode whitelisting.
 */
class SecurityManager(private val context: Context) {

    companion object {
        private const val TAG = "SecurityManager"
    }

    private val devicePolicyManager =
        context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    private val adminComponent = ComponentName(context, DeviceAdminReceiverImpl::class.java)

    /**
     * Check if our app is already a device admin.
     */
    fun isDeviceAdmin(): Boolean {
        return devicePolicyManager.isAdminActive(adminComponent)
    }

    /**
     * Launch the device admin activation intent.
     */
    fun requestDeviceAdmin() {
        if (isDeviceAdmin()) return

        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
            putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "需要设备管理员权限以启用屏幕固定模式，防止应用被退出。"
            )
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    /**
     * Check if screen pinning is enabled in system settings.
     */
    fun isScreenPinningEnabled(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                val pinned =
                    Settings.Global.getInt(
                        context.contentResolver,
                        "lock_task_mode_enabled",
                        0
                    )
                return pinned == 1
            } catch (e: Exception) {
                Log.e(TAG, "Failed to check screen pinning setting", e)
            }
        }
        return false
    }

    /**
     * Open screen pinning settings for the user to enable it manually,
     * if the app cannot enable it programmatically.
     */
    fun openScreenPinningSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val intent = Intent(Settings.ACTION_SECURITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }

    /**
     * Check if the app is whitelisted for lock-task mode.
     */
    fun isLockTaskWhitelisted(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                return devicePolicyManager.isLockTaskPermitted(context.packageName)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to check lock task permitted", e)
            }
        }
        return false
    }

    /**
     * Remove device admin — called during exit cleanup.
     */
    fun removeDeviceAdmin() {
        if (isDeviceAdmin()) {
            try {
                devicePolicyManager.removeActiveAdmin(adminComponent)
                Log.i(TAG, "Device admin removed")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to remove device admin", e)
            }
        }
    }

    /**
     * Check if the app is Device Owner (the most privileged DPM role).
     * When device-owner is set, lock-task can be configured via the DPM
     * API directly without root shell access.
     */
    fun isDeviceOwner(): Boolean {
        return try {
            devicePolicyManager.isDeviceOwnerApp(context.packageName)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check device owner", e)
            false
        }
    }

    /**
     * Whitelist packages for lock-task via the DPM API (requires device-owner).
     */
    fun setLockTaskPackages(vararg packages: String): Boolean {
        return try {
            devicePolicyManager.setLockTaskPackages(adminComponent, packages)
            Log.i(TAG, "Lock task packages set via DPM: ${packages.joinToString()}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set lock task packages via DPM", e)
            false
        }
    }
}
