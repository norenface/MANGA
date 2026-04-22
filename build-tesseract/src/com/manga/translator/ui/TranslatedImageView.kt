package com.manga.translator.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.widget.ImageView
import com.manga.translator.model.TextOverlay

class TranslatedImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : ImageView(context, attrs, defStyle) {

    private val overlays = mutableListOf<TextOverlay>()
    var showOverlays = true

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

    fun setOverlays(list: List<TextOverlay>) {
        overlays.clear()
        overlays.addAll(list)
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
        val sx = matrixValues[Matrix.MSCALE_X]
        val sy = matrixValues[Matrix.MSCALE_Y]
        val tx = matrixValues[Matrix.MTRANS_X]
        val ty = matrixValues[Matrix.MTRANS_Y]

        for (ov in overlays) {
            if (ov.translatedText.isBlank()) continue
            val r = RectF(
                ov.boundingBox.left   * sx + tx,
                ov.boundingBox.top    * sy + ty,
                ov.boundingBox.right  * sx + tx,
                ov.boundingBox.bottom * sy + ty
            )
            if (r.width() < 4f || r.height() < 4f) continue
            canvas.drawRoundRect(r, 5f, 5f, bgPaint)
            canvas.drawRoundRect(r, 5f, 5f, borderPaint)
            drawText(canvas, ov.translatedText, r)
        }
    }

    private fun drawText(canvas: Canvas, text: String, r: RectF) {
        val maxW = r.width() - 4f
        val maxH = r.height()
        if (maxW <= 0f || maxH <= 0f) return
        textPaint.textSize = (maxH * 0.65f).coerceIn(9f, 36f)
        val lines = wrapText(text, maxW)
        if (lines.size > 1 && lines.size * textPaint.fontSpacing > maxH) {
            textPaint.textSize = (maxH / lines.size * 0.85f).coerceIn(8f, 36f)
        }
        var y = r.top + textPaint.textSize * 0.9f
        for (line in lines) {
            if (y > r.bottom + textPaint.textSize) break
            canvas.drawText(line, r.left + 2f, y, textPaint)
            y += textPaint.fontSpacing
        }
    }

    private fun wrapText(text: String, maxW: Float): List<String> {
        val lines = mutableListOf<String>()
        val sb = StringBuilder()
        for (ch in text) {
            sb.append(ch)
            if (textPaint.measureText(sb.toString()) > maxW) {
                if (sb.length > 1) {
                    lines.add(sb.dropLast(1).toString())
                    sb.clear(); sb.append(ch)
                } else {
                    lines.add(sb.toString()); sb.clear()
                }
            }
        }
        if (sb.isNotEmpty()) lines.add(sb.toString())
        return lines.ifEmpty { listOf(text) }
    }
}
