package com.manga.translator.model

import android.graphics.Rect

data class TextOverlay(
    val boundingBox: Rect,
    val originalText: String,
    var translatedText: String = ""
)
