package com.adscreen.kiosk.exit

import android.app.Dialog
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.fragment.app.DialogFragment
import com.adscreen.kiosk.R
import com.adscreen.kiosk.databinding.DialogExitBinding
import com.adscreen.kiosk.util.CryptoUtil
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class ExitDialogFragment : DialogFragment() {

    private var _binding: DialogExitBinding? = null
    private val binding get() = _binding!!

    private lateinit var cryptoUtil: CryptoUtil
    private var onExitConfirmed: (() -> Unit)? = null
    private var dialogTitle: String? = null
    private var dialogHint: String? = null
    private var confirmButtonText: String? = null

    fun setOnExitConfirmed(listener: () -> Unit): ExitDialogFragment {
        onExitConfirmed = listener
        return this
    }

    fun setTitle(title: String): ExitDialogFragment {
        dialogTitle = title
        return this
    }

    fun setHint(hint: String): ExitDialogFragment {
        dialogHint = hint
        return this
    }

    fun setConfirmButtonText(text: String): ExitDialogFragment {
        confirmButtonText = text
        return this
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, com.google.android.material.R.style.Theme_Material3_Light_Dialog)
        cryptoUtil = CryptoUtil(requireContext())
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = DialogExitBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Apply custom text if set
        dialogTitle?.let { binding.tvDialogTitle.text = it }
        dialogHint?.let { binding.etExitPassword.hint = it }
        confirmButtonText?.let { binding.btnConfirmExit.text = it }

        binding.etExitPassword.requestFocus()

        // Clear error state on input
        binding.etExitPassword.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                binding.tvError.visibility = View.GONE
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        binding.btnCancel.setOnClickListener {
            dismiss()
        }

        binding.btnConfirmExit.setOnClickListener {
            validateAndExit()
        }
    }

    override fun onStart() {
        super.onStart()
        // Ensure the dialog is full-width and keyboard pops up
        dialog?.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        dialog?.window?.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
        )
    }

    private fun validateAndExit() {
        val input = binding.etExitPassword.text?.toString() ?: ""

        if (cryptoUtil.verifyPassword(input)) {
            val listener = onExitConfirmed
            dismiss()
            listener?.invoke()
        } else {
            binding.tvError.visibility = View.VISIBLE
            binding.etExitPassword.text?.clear()
            binding.etExitPassword.requestFocus()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "ExitDialogFragment"
        const val TAG_SETTINGS = "SettingsGateDialog"
    }
}
