package com.manga.translator.ocr

import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.manga.translator.model.TextOverlay
import kotlinx.coroutines.tasks.await

class OcrTranslationEngine {

    private val tag = "OcrTranslationEngine"
    private val recognizer: TextRecognizer = TextRecognition.getClient(
        KoreanTextRecognizerOptions.Builder().build()
    )
    private var translator: Translator? = null
    var isReady = false
        private set

    suspend fun initialize(): Boolean {
        return try {
            val options = TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.KOREAN)
                .setTargetLanguage(TranslateLanguage.JAPANESE)
                .build()
            val t = Translation.getClient(options)
            translator = t

            val conditions = DownloadConditions.Builder().build()
            t.downloadModelIfNeeded(conditions).await()
            isReady = true
            Log.i(tag, "Translation model ready")
            true
        } catch (e: Exception) {
            Log.e(tag, "Failed to initialize: ${e.message}")
            false
        }
    }

    suspend fun processImage(bitmap: Bitmap): List<TextOverlay> {
        if (!isReady) return emptyList()
        return try {
            val inputImage = InputImage.fromBitmap(bitmap, 0)
            val visionText = recognizer.process(inputImage).await()

            visionText.textBlocks.mapNotNull { block ->
                val box = block.boundingBox ?: return@mapNotNull null
                val text = block.text.trim()
                if (text.isBlank()) return@mapNotNull null

                val translated = try {
                    translator?.translate(text)?.await() ?: text
                } catch (e: Exception) {
                    text
                }

                TextOverlay(
                    boundingBox = box,
                    originalText = text,
                    translatedText = translated
                )
            }
        } catch (e: Exception) {
            Log.e(tag, "OCR failed: ${e.message}")
            emptyList()
        }
    }

    fun release() {
        recognizer.close()
        translator?.close()
        isReady = false
    }
}
