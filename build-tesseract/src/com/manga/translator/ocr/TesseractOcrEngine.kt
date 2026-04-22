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
        } catch (e: Exception) {
            Log.e(tag, "Init failed: ${e.message}")
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
        return try {
            api.setImage(bitmap)
            api.getUTF8Text()   // triggers recognition

            val results = ArrayList<TextOverlay>()
            val ri = api.resultIterator
            ri.begin()
            val level = TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE
            do {
                val text = ri.getUTF8Text(level) ?: continue
                val trimmed = text.trim()
                if (trimmed.isEmpty()) continue
                val box: Rect = ri.getBoundingRect(level) ?: continue
                if (box.width() < 10 || box.height() < 10) continue
                results.add(TextOverlay(boundingBox = box, originalText = trimmed))
            } while (ri.next(level))
            ri.delete()
            results
        } catch (e: Exception) {
            Log.e(tag, "OCR error: ${e.message}")
            ArrayList()
        }
    }

    fun release() {
        tessApi?.end()
        tessApi = null
        isReady = false
    }
}
