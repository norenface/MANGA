package com.manga.translator.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatImageView
import com.manga.translator.model.TextOverlay

class TranslatedImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr) {

    private val overlays = mutableListOf<TextOverlay>()
    var showOverlays = true
        private set

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(230, 255, 252, 200)
        style = Paint.Style.FILL
    }

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 180, 80, 0)
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(15, 15, 15)
        style = Paint.Style.FILL
        textSize = 26f
    }

    private val matrixValues = FloatArray(9)

    fun setOverlays(overlays: List<TextOverlay>) {
        this.overlays.clear()
        this.overlays.addAll(overlays)
        invalidate()
    }

    fun toggleOverlays() {
        showOverlays = !showOverlays
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!showOverlays || overlays.isEmpty()) return

        val d = drawable ?: return
        if (d.intrinsicWidth <= 0 || d.intrinsicHeight <= 0) return

        imageMatrix.getValues(matrixValues)
        val scaleX = matrixValues[Matrix.MSCALE_X]
        val scaleY = matrixValues[Matrix.MSCALE_Y]
        val transX = matrixValues[Matrix.MTRANS_X]
        val transY = matrixValues[Matrix.MTRANS_Y]

        for (overlay in overlays) {
            val rect = RectF(
                overlay.boundingBox.left * scaleX + transX,
                overlay.boundingBox.top * scaleY + transY,
                overlay.boundingBox.right * scaleX + transX,
                overlay.boundingBox.bottom * scaleY + transY
            )
            if (rect.width() < 4f || rect.height() < 4f) continue

            canvas.drawRoundRect(rect, 5f, 5f, bgPaint)
            canvas.drawRoundRect(rect, 5f, 5f, borderPaint)
            drawWrappedText(canvas, overlay.translatedText, rect)
        }
    }

    private fun drawWrappedText(canvas: Canvas, text: String, rect: RectF) {
        val maxWidth = rect.width() - 4f
        val maxHeight = rect.height()
        if (maxWidth <= 0f || maxHeight <= 0f) return

        // Start with a font size proportional to box height, clamped to readable range
        textPaint.textSize = (maxHeight * 0.65f).coerceIn(9f, 38f)

        val lines = splitIntoLines(text, maxWidth)

        // If too many lines, shrink font
        val lineHeight = textPaint.fontSpacing
        if (lines.size * lineHeight > maxHeight && lines.size > 1) {
            val reduced = (maxHeight / lines.size * 0.85f).coerceIn(8f, 38f)
            textPaint.textSize = reduced
        }

        var y = rect.top + textPaint.textSize * 0.9f
        for (line in lines) {
            if (y > rect.bottom + textPaint.textSize) break
            canvas.drawText(line, rect.left + 2f, y, textPaint)
            y += textPaint.fontSpacing
        }
    }

    // Character-level wrapping suitable for Japanese/CJK text
    private fun splitIntoLines(text: String, maxWidth: Float): List<String> {
        val lines = mutableListOf<String>()
        val sb = StringBuilder()
        for (ch in text) {
            sb.append(ch)
            if (textPaint.measureText(sb.toString()) > maxWidth) {
                if (sb.length > 1) {
                    lines.add(sb.dropLast(1).toString())
                    sb.clear()
                    sb.append(ch)
                } else {
                    lines.add(sb.toString())
                    sb.clear()
                }
            }
        }
        if (sb.isNotEmpty()) lines.add(sb.toString())
        return lines.ifEmpty { listOf(text) }
    }
}
