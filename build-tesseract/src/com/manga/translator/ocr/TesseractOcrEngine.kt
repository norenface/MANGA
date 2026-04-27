package com.manga.translator.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import com.googlecode.tesseract.android.TessBaseAPI
import com.manga.translator.model.TextOverlay
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

class TesseractOcrEngine(private val context: Context) {

    private val tag = "TesseractOCR"
    private var tessApi: TessBaseAPI? = null
    var isReady = false
        private set

    fun initialize(): Boolean {
        return try {
            val dataDir = context.filesDir.absolutePath
            copyTessdata(dataDir)
            val api = TessBaseAPI()
            val ok = api.init(dataDir, "kor")
            if (ok) {
                api.pageSegMode = TessBaseAPI.PageSegMode.PSM_AUTO
                tessApi = api
                isReady = true
                Log.i(tag, "Tesseract initialized OK")
            } else {
                Log.e(tag, "Tesseract init returned false")
            }
            ok
        } catch (t: Throwable) {
            // UnsatisfiedLinkError (native lib 読み込み失敗) も含め捕捉
            Log.e(tag, "Init failed: ${t.message}")
            false
        }
    }

    private fun copyTessdata(dataDir: String) {
        val tessDir = File("$dataDir/tessdata")
        tessDir.mkdirs()
        val dest = File("$tessDir/kor.traineddata")
        if (dest.exists() && dest.length() > 1_000_000L) return

        var src: InputStream? = null
        var out: OutputStream? = null
        try {
            src = context.assets.open("tessdata/kor.traineddata")
            out = FileOutputStream(dest)
            val buf = ByteArray(8192)
            var n: Int
            while (true) {
                n = src.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
            }
            Log.i(tag, "kor.traineddata copied: ${dest.length()} bytes")
        } finally {
            src?.close()
            out?.close()
        }
    }

    @Synchronized
    fun processImage(bitmap: Bitmap): List<TextOverlay> {
        val api = tessApi ?: return ArrayList()
        // Tesseract に渡す Bitmap を最大 1200px に制限してネイティブ OOM を防ぐ
        val ocr = scaleBitmapForOcr(bitmap)
        val scaleX = bitmap.width.toFloat() / ocr.width
        val scaleY = bitmap.height.toFloat() / ocr.height
        return try {
            api.setImage(ocr)
            api.getUTF8Text()

            val results = ArrayList<TextOverlay>()
            val ri = api.resultIterator
            ri.begin()
            val level = TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE
            do {
                val text = ri.getUTF8Text(level) ?: continue
                val trimmed = text.trim()
                if (trimmed.isEmpty()) continue
                val box: Rect = ri.getBoundingRect(level) ?: continue
                if (box.width() < 8 || box.height() < 8) continue
                // OCR 座標を元の Bitmap サイズに戻す
                val scaled = Rect(
                    (box.left   * scaleX).toInt(),
                    (box.top    * scaleY).toInt(),
                    (box.right  * scaleX).toInt(),
                    (box.bottom * scaleY).toInt()
                )
                results.add(TextOverlay(boundingBox = scaled, originalText = trimmed))
            } while (ri.next(level))
            ri.delete()
            results
        } catch (t: Throwable) {
            Log.e(tag, "OCR error: ${t.message}")
            ArrayList()
        } finally {
            if (ocr !== bitmap) ocr.recycle()
        }
    }

    private fun scaleBitmapForOcr(src: Bitmap): Bitmap {
        // ウェブトゥーンは横幅が狭く縦が長い。幅基準でのみスケールし、
        // 縦方向はそのまま保持することで文字の判読性を確保する。
        val maxW = 1200
        if (src.width <= maxW) return src
        val scale = maxW.toFloat() / src.width
        val nw = maxW
        val nh = (src.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, nw, nh, false)
    }

    fun release() {
        tessApi?.end()
        tessApi = null
        isReady = false
    }
}
