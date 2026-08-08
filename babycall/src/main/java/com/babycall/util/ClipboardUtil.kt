package com.babycall.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import com.babycall.R

object ClipboardUtil {
    /** Copies the raw (unspaced) family code and shows a short confirmation toast. */
    fun copyFamilyCode(context: Context, code: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("family_code", code))
        Toast.makeText(context, R.string.toast_code_copied, Toast.LENGTH_SHORT).show()
    }
}
