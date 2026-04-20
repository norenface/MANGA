package com.manga.translator.api

object NaverComicsApi {
    private const val TITLE_ID = "131385"
    private const val BASE_URL = "https://m.comic.naver.com"

    // Google Translateプロキシ経由でKorean→Japaneseに翻訳
    private const val TRANSLATE_BASE = "https://m-comic-naver-com.translate.goog"
    private const val TRANSLATE_PARAMS = "&_x_tr_sl=ko&_x_tr_tl=ja&_x_tr_hl=ja&_x_tr_pto=wapp"

    fun getTranslatedListUrl(week: String = "thu"): String {
        return "$TRANSLATE_BASE/webtoon/list?titleId=$TITLE_ID&week=$week$TRANSLATE_PARAMS"
    }

    fun getTranslatedEpisodeUrl(episodeNo: Int): String {
        return "$TRANSLATE_BASE/webtoon/detail?titleId=$TITLE_ID&no=$episodeNo$TRANSLATE_PARAMS"
    }

    fun getOriginalListUrl(): String {
        return "$BASE_URL/webtoon/list?titleId=$TITLE_ID&week=thu"
    }

    fun getOriginalEpisodeUrl(episodeNo: Int): String {
        return "$BASE_URL/webtoon/detail?titleId=$TITLE_ID&no=$episodeNo"
    }

    fun toTranslatedUrl(originalUrl: String): String {
        if (originalUrl.contains("translate.goog")) return originalUrl
        return originalUrl
            .replace("https://m.comic.naver.com", TRANSLATE_BASE)
            .replace("https://comic.naver.com", TRANSLATE_BASE) + TRANSLATE_PARAMS
    }
}
