package com.adscreen.kiosk

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.view.MotionEvent
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.adscreen.kiosk.databinding.ActivitySettingsBinding
import com.adscreen.kiosk.exit.ExitDialogFragment
import com.adscreen.kiosk.manager.RootManager
import com.adscreen.kiosk.util.Constants
import com.adscreen.kiosk.util.CryptoUtil
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var cryptoUtil: CryptoUtil

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cryptoUtil = CryptoUtil(this)

        loadSettings()
        setupListeners()
        setupKeyboardDismiss()
    }

    private fun setupListeners() {
        binding.btnBack.setOnClickListener { finish() }
        binding.btnSave.setOnClickListener { saveSettings() }
        binding.btnExitKiosk.setOnClickListener { showExitDialog() }
        binding.btnUninstall.setOnClickListener { showUninstallDialog() }
    }

    private fun loadSettings() {
        binding.etUrl.setText(cryptoUtil.getDecryptedUrl())

        val orientation = getPrefs().getString(Constants.KEY_ORIENTATION, "landscape") ?: "landscape"
        val checkedId = when (orientation) {
            "portrait" -> binding.radioPortrait.id
            "sensor" -> binding.radioSensor.id
            else -> binding.radioLandscape.id
        }
        binding.orientationGroup.check(checkedId)

        val freezeLauncher = getPrefs().getBoolean(Constants.KEY_FREEZE_LAUNCHER, true)
        binding.switchFreezeLauncher.isChecked = freezeLauncher
    }

    private fun saveSettings() {
        val currentPw = binding.etCurrentPassword.text?.toString() ?: ""
        val newPw = binding.etNewPassword.text?.toString() ?: ""
        val newPwConfirm = binding.etNewPasswordConfirm.text?.toString() ?: ""
        val url = binding.etUrl.text?.toString() ?: ""

        // Password change (only if new password fields are filled)
        if (newPw.isNotEmpty() || newPwConfirm.isNotEmpty()) {
            if (currentPw.isEmpty()) {
                Toast.makeText(this, "请输入当前密码", Toast.LENGTH_SHORT).show()
                return
            }
            if (!cryptoUtil.verifyPassword(currentPw)) {
                binding.tilCurrentPassword.error = "当前密码错误"
                return
            }
            binding.tilCurrentPassword.error = null

            if (newPw.length != Constants.PASSWORD_LENGTH || !newPw.all { it.isDigit() }) {
                binding.tilNewPassword.error = "密码必须为6位数字"
                return
            }
            binding.tilNewPassword.error = null

            if (newPw != newPwConfirm) {
                binding.tilNewPasswordConfirm.error = "两次密码输入不一致"
                return
            }
            binding.tilNewPasswordConfirm.error = null

            cryptoUtil.savePassword(newPw)
            Toast.makeText(this, "密码已更新", Toast.LENGTH_SHORT).show()
        }

        // Save URL
        val finalUrl = if (url.isBlank()) {
            Constants.DEFAULT_URL
        } else {
            var normalized = url.trim()
            if (!normalized.startsWith("http://") && !normalized.startsWith("https://")) {
                normalized = "https://$normalized"
            }
            normalized
        }
        cryptoUtil.saveUrl(finalUrl)

        // Save orientation
        val orientation = when (binding.orientationGroup.checkedButtonId) {
            binding.radioPortrait.id -> "portrait"
            binding.radioSensor.id -> "sensor"
            else -> "landscape"
        }
        getPrefs().edit().putString(Constants.KEY_ORIENTATION, orientation).apply()

        // Save freeze launcher
        getPrefs().edit()
            .putBoolean(Constants.KEY_FREEZE_LAUNCHER, binding.switchFreezeLauncher.isChecked)
            .apply()

        setResult(RESULT_OK)
        Toast.makeText(this, "设置已保存", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun showExitDialog() {
        val dialog = ExitDialogFragment()
            .setTitle("退出Kiosk模式")
            .setHint("请输入管理员密码")
            .setConfirmButtonText("退出")
            .setOnExitConfirmed {
                performSettingsExit()
            }
        dialog.show(supportFragmentManager, "ExitFromSettings")
    }

    private fun performSettingsExit() {
        lifecycleScope.launch {
            // 1. Exit lock task mode
            try { stopLockTask() } catch (_: Exception) {}

            // 2. Restore system UI via root
            val rootManager = RootManager(this@SettingsActivity)
            rootManager.performElevatedCleanup()

            // 3. Clear secure flag
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)

            // 4. Finish all activities in the task
            finishAffinity()
        }
    }

    private fun showUninstallDialog() {
        val dialog = ExitDialogFragment()
            .setTitle("卸载 Kiosk")
            .setHint("请输入管理员密码确认")
            .setConfirmButtonText("卸载")
            .setOnExitConfirmed {
                performUninstall()
            }
        dialog.show(supportFragmentManager, "UninstallKiosk")
    }

    private fun performUninstall() {
        lifecycleScope.launch {
            // 1. Exit lock task
            try { stopLockTask() } catch (_: Exception) {}

            // 2. 清除 device owner 和 device admin（不需要 root）
            val dpm = getSystemService(DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
            try { dpm.clearDeviceOwnerApp(packageName) } catch (_: Exception) {}
            try {
                val comp = android.content.ComponentName(
                    this@SettingsActivity,
                    com.adscreen.kiosk.manager.DeviceAdminReceiverImpl::class.java
                )
                dpm.removeActiveAdmin(comp)
            } catch (_: Exception) {}

            // 3. Clear secure flag
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)

            // 4. Open system uninstall page
            val intent = Intent(Intent.ACTION_DELETE).apply {
                data = Uri.parse("package:$packageName")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)

            // 5. Finish settings
            finishAffinity()
        }
    }

    private fun setupKeyboardDismiss() {
        binding.root.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                imm.hideSoftInputFromWindow(currentFocus?.windowToken, 0)
                currentFocus?.clearFocus()
            }
            false
        }
    }

    private fun getPrefs(): SharedPreferences {
        return getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
    }
}
