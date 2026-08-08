package com.babycall.call

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import org.webrtc.EglBase
import org.webrtc.SurfaceViewRenderer

/**
 * Always-on-top video bubble shown near the bottom-right corner of the
 * baby's screen whenever at least one viewer is connected -- drawn over
 * whatever else is on screen (the waiting screen, or a registered
 * third-party app) so it never interrupts what the baby is doing on its
 * own. Tapping it toggles between that small corner bubble and filling the
 * whole screen with the same video (a bigger, "real" video call view) --
 * the baby can freely go back and forth between watching the family member
 * up close and returning to whatever's underneath (the game keeps running
 * the whole time either way; only this overlay's own size changes, nothing
 * about the game's activity/task is touched). Requires the caregiver to
 * have granted "display over other apps" (see ScreenShareSetupActivity); if
 * not granted, calls still connect normally, there's just no visible
 * bubble (and thus no way to expand it).
 */
class CallBubbleOverlay(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var renderer: SurfaceViewRenderer? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var expanded = false

    /** Adds the bubble to the screen (if not already shown) and returns the
     *  renderer to bind a video track to; returns null if the overlay
     *  permission hasn't been granted. */
    fun show(eglBaseContext: EglBase.Context): SurfaceViewRenderer? {
        renderer?.let { return it }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) return null

        val view = SurfaceViewRenderer(context)
        view.init(eglBaseContext, null)
        view.setZOrderOnTop(true)
        view.setOnClickListener { toggleExpanded() }

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        // Deliberately touchable (unlike a typical always-ignore overlay) so the
        // tap-to-expand gesture works; only this small corner rect intercepts
        // touches from whatever's underneath, and only while a call is active.
        val params = WindowManager.LayoutParams(
            dpToPx(BUBBLE_WIDTH_DP),
            dpToPx(BUBBLE_HEIGHT_DP),
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            x = dpToPx(MARGIN_DP)
            y = dpToPx(MARGIN_DP)
        }

        return try {
            windowManager.addView(view, params)
            renderer = view
            layoutParams = params
            expanded = false
            view
        } catch (_: Exception) {
            null
        }
    }

    /** Switches the same overlay window between the small corner bubble and
     *  filling the screen, in place -- never touches the underlying
     *  activity/task, so whatever the baby was doing (a registered app, or
     *  the waiting screen) picks back up exactly where it was once shrunk
     *  back down. */
    private fun toggleExpanded() {
        val view = renderer ?: return
        val params = layoutParams ?: return
        expanded = !expanded
        if (expanded) {
            params.width = WindowManager.LayoutParams.MATCH_PARENT
            params.height = WindowManager.LayoutParams.MATCH_PARENT
            params.gravity = Gravity.TOP or Gravity.START
            params.x = 0
            params.y = 0
        } else {
            params.width = dpToPx(BUBBLE_WIDTH_DP)
            params.height = dpToPx(BUBBLE_HEIGHT_DP)
            params.gravity = Gravity.BOTTOM or Gravity.END
            params.x = dpToPx(MARGIN_DP)
            params.y = dpToPx(MARGIN_DP)
        }
        try {
            windowManager.updateViewLayout(view, params)
        } catch (_: Exception) {
        }
    }

    fun hide() {
        val view = renderer ?: return
        renderer = null
        layoutParams = null
        expanded = false
        try {
            windowManager.removeView(view)
        } catch (_: Exception) {
        }
        view.release()
    }

    private fun dpToPx(dp: Int): Int = (dp * context.resources.displayMetrics.density).toInt()

    companion object {
        private const val BUBBLE_WIDTH_DP = 120
        private const val BUBBLE_HEIGHT_DP = 160
        private const val MARGIN_DP = 16
    }
}
