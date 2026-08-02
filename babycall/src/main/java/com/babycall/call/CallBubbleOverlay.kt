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
 * Small always-on-top video bubble shown near the bottom-right corner of
 * the baby's screen whenever at least one viewer is connected -- drawn over
 * whatever else is on screen (the waiting screen, or a registered
 * third-party app) without ever stealing focus or touch input, so it never
 * interrupts what the baby is doing. Requires the caregiver to have granted
 * "display over other apps" (see ScreenShareSetupActivity); if not granted,
 * calls still connect normally, there's just no visible bubble.
 */
class CallBubbleOverlay(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var renderer: SurfaceViewRenderer? = null
    private var attached = false

    /** Adds the bubble to the screen (if not already shown) and returns the
     *  renderer to bind a video track to; returns null if the overlay
     *  permission hasn't been granted. */
    fun show(eglBaseContext: EglBase.Context): SurfaceViewRenderer? {
        renderer?.let { return it }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) return null

        val view = SurfaceViewRenderer(context)
        view.init(eglBaseContext, null)
        view.setZOrderOnTop(true)

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            dpToPx(BUBBLE_WIDTH_DP),
            dpToPx(BUBBLE_HEIGHT_DP),
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
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
            attached = true
            view
        } catch (_: Exception) {
            null
        }
    }

    fun hide() {
        val view = renderer ?: return
        renderer = null
        if (attached) {
            attached = false
            try {
                windowManager.removeView(view)
            } catch (_: Exception) {
            }
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
