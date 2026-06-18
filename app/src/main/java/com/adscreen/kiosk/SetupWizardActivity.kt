package com.adscreen.kiosk

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.adscreen.kiosk.databinding.ActivitySetupWizardBinding
import com.adscreen.kiosk.util.Constants
import com.adscreen.kiosk.util.CryptoUtil

class SetupWizardActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySetupWizardBinding
    private lateinit var cryptoUtil: CryptoUtil

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySetupWizardBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cryptoUtil = CryptoUtil(this)

        // If not first run, skip to main
        if (!cryptoUtil.isFirstRun) {
            launchMainActivity()
            return
        }

        setupViews()
    }

    private fun setupViews() {
        // Limit URL to one line
        binding.etUrl.setSingleLine(true)

        binding.btnConfirm.setOnClickListener {
            validateAndProceed()
        }
    }

    private fun validateAndProceed() {
        val password = binding.etPassword.text?.toString() ?: ""
        val passwordConfirm = binding.etPasswordConfirm.text?.toString() ?: ""
        val url = binding.etUrl.text?.toString() ?: ""

        // Validate password
        if (password.length != Constants.PASSWORD_LENGTH || !password.all { it.isDigit() }) {
            binding.tilPassword.error = getString(R.string.setup_password_too_short)
            return
        }
        binding.tilPassword.error = null

        if (password != passwordConfirm) {
            binding.tilPasswordConfirm.error = getString(R.string.setup_password_mismatch)
            return
        }
        binding.tilPasswordConfirm.error = null

        // Validate responsibility checkbox
        if (!binding.cbResponsibility.isChecked) {
            Toast.makeText(this, "请确认已牢记密码", Toast.LENGTH_SHORT).show()
            return
        }

        // Process URL
        val finalUrl = if (url.isBlank()) {
            Constants.DEFAULT_URL
        } else {
            normalizeUrl(url)
        }

        // Save orientation
        val orientation = when (binding.orientationGroup.checkedButtonId) {
            binding.radioPortrait.id -> "portrait"
            binding.radioSensor.id -> "sensor"
            else -> "landscape"
        }
        getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(Constants.KEY_ORIENTATION, orientation).apply()

        // Save securely
        cryptoUtil.apply {
            savePassword(password)
            saveUrl(finalUrl)
            isFirstRun = false
        }

        launchMainActivity()
    }

    private fun normalizeUrl(url: String): String {
        var normalized = url.trim()
        if (!normalized.startsWith("http://") && !normalized.startsWith("https://")) {
            normalized = "https://$normalized"
        }
        return normalized
    }

    private fun launchMainActivity() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        startActivity(intent)
        finish()
    }
}
