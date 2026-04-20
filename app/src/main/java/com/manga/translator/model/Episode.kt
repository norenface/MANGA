package com.manga.translator.model

data class Episode(
    val no: Int,
    val title: String,
    val thumbnail: String,
    val date: String,
    val rating: String
)

data class MangaInfo(
    val titleId: String = "131385",
    val title: String = "女神降臨",
    val author: String = "",
    val description: String = "",
    val thumbnail: String = "",
    val episodes: List<Episode> = emptyList()
)
