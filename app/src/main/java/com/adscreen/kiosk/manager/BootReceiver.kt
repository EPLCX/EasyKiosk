package com.adscreen.kiosk.manager

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.adscreen.kiosk.MainActivity
import com.adscreen.kiosk.SetupWizardActivity
import com.adscreen.kiosk.util.Constants
import com.topjohnwu.superuser.Shell

class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_LOCKED_BOOT_COMPLETED -> {
                Log.i(TAG, "Boot completed, re-enabling launchers")

                // Run root commands on background thread to avoid ANR
                Thread {
                    enableLaunchers()
                    Handler(Looper.getMainLooper()).postDelayed({
                        startKiosk(context)
                    }, 1500)
                }.start()
            }
            Intent.ACTION_SHUTDOWN -> {
                Log.i(TAG, "Device shutting down, re-enabling launchers")
                enableLaunchers()
            }
        }
    }

    private fun startKiosk(context: Context) {
        // Check if setup has been completed; if not, start the setup wizard
        val prefs = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        val isFirstRun = prefs.getBoolean(Constants.KEY_IS_FIRST_RUN, true)

        val targetClass = if (isFirstRun) SetupWizardActivity::class.java else MainActivity::class.java
        val launchIntent = Intent(context, targetClass).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            )
        }
        context.startActivity(launchIntent)
        Log.i(TAG, "Starting: ${targetClass.simpleName}")
    }

    private fun enableLaunchers() {
        val launchers = Constants.LAUNCHER_PACKAGES.split(":")
        for (pkg in launchers) {
            val result = Shell.cmd(Constants.ROOT_CMD_ENABLE_LAUNCHER.format(pkg)).exec()
            if (result.isSuccess) {
                Log.i(TAG, "Enabled launcher: $pkg")
            }
        }
    }
}
