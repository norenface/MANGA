package com.babycall.security

import android.content.Context
import androidx.appcompat.app.AlertDialog
import com.babycall.R
import com.babycall.databinding.DialogPinBinding

/**
 * Modal PIN prompt. [onSubmit] returns true if the PIN was correct, in which
 * case the dialog closes; otherwise an inline error is shown and the dialog
 * stays open so the caller (a parent or babysitter) can retry.
 */
object PinDialog {

    fun show(context: Context, titleRes: Int, onSubmit: (String) -> Boolean) {
        val binding = DialogPinBinding.inflate(android.view.LayoutInflater.from(context))

        val dialog = AlertDialog.Builder(context)
            .setTitle(titleRes)
            .setView(binding.root)
            .setPositiveButton(R.string.button_confirm, null)
            .setNegativeButton(R.string.button_cancel, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val pin = binding.etPinInput.text?.toString().orEmpty()
                if (onSubmit(pin)) {
                    dialog.dismiss()
                } else {
                    binding.tvPinError.visibility = android.view.View.VISIBLE
                    binding.etPinInput.text?.clear()
                }
            }
        }

        dialog.show()
    }
}
