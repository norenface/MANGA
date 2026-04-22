package com.manga.translator.translation

import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object MyMemoryTranslator {

    private const val TAG = "MyMemory"
    private const val ENDPOINT = "https://api.mymemory.translated.net/get"
    // 複数テキストをまとめて送る区切り文字 (翻訳後も保持されやすい文字列)
    private const val SEP = " ⏎ "

    /** テキストリストを一括翻訳 (1回のAPI呼び出し) */
    fun translateBatch(texts: List<String>): List<String> {
        if (texts.isEmpty()) return emptyList()
        return try {
            val joined = texts.joinToString(SEP)
            val translated = callApi(joined)
            val parts = translated.split(SEP)
            // 件数が合わない場合は個別翻訳にフォールバック
            if (parts.size == texts.size) {
                parts.map { it.trim() }
            } else {
                texts.map { translateSingle(it) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Batch failed, fallback: ${e.message}")
            texts.map { translateSingle(it) }
        }
    }

    /** 単一テキスト翻訳 */
    fun translateSingle(text: String): String {
        return try { callApi(text) } catch (e: Exception) { text }
    }

    private fun callApi(text: String): String {
        val encoded = URLEncoder.encode(text, "UTF-8")
        val url = URL("$ENDPOINT?q=$encoded&langpair=ko|ja")
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        conn.setRequestProperty("User-Agent", "MangaTranslator/3.0")
        return try {
            val body = conn.inputStream.bufferedReader().readText()
            val json = JSONObject(body)
            json.getJSONObject("responseData").getString("translatedText").trim()
        } finally {
            conn.disconnect()
        }
    }
}
