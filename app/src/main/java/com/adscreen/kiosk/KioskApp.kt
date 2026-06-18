package com.adscreen.kiosk

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.util.Log
import com.topjohnwu.superuser.Shell
import java.io.File
import java.io.FileWriter

class KioskApp : Application() {

    companion object {
        const val NOTIFICATION_CHANNEL_ID = "kiosk_keepalive"
        private const val TAG = "KioskApp"
    }

    override fun onCreate() {
        // Install crash handler before super.onCreate so it catches ALL crashes
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val file = File(filesDir, "crash_log.txt")
                FileWriter(file).use { w ->
                    w.write("Time: ${System.currentTimeMillis()}\n")
                    w.write("Thread: ${thread.name}\n")
                    w.write("Message: ${throwable.message}\n")
                    w.write("Stack:\n")
                    throwable.stackTrace.forEach { w.write("\t${it.toString()}\n") }
                    throwable.cause?.let { cause ->
                        w.write("Caused by: ${cause.message}\n")
                        cause.stackTrace.forEach { w.write("\t${it.toString()}\n") }
                    }
                }
                Log.e(TAG, "Crash logged to ${file.absolutePath}", throwable)

                // Restore launchers before dying — use libsu like the rest of the app
                val launchers = com.adscreen.kiosk.util.Constants.LAUNCHER_PACKAGES.split(":")
                for (pkg in launchers) {
                    try {
                        Shell.cmd("pm enable $pkg").exec()
                    } catch (_: Exception) {}
                }
            } catch (_: Exception) {
                // last resort
            }
            // Let default handler finish the job
            throwable.printStackTrace()
            android.os.Process.killProcess(android.os.Process.myPid())
            System.exit(1)
        }

        super.onCreate()

        Shell.setDefaultBuilder(
            Shell.Builder.create()
                .setFlags(Shell.FLAG_REDIRECT_STDERR)
                .setTimeout(10)
        )

        createKeepAliveChannel()
    }

    private fun createKeepAliveChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Kiosk Service",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "Keeps the kiosk app alive in the background"
                enableVibration(false)
                setSound(null, null)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }
}
