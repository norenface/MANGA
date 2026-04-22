package com.manga.translator

object NaverComicsApi {
    private const val TITLE_ID = "131385"
    private const val TR_BASE = "https://m-comic-naver-com.translate.goog"
    private const val TR_PARAMS = "&_x_tr_sl=ko&_x_tr_tl=ja&_x_tr_hl=ja&_x_tr_pto=wapp"

    fun translatedListUrl() =
        "$TR_BASE/webtoon/list?titleId=$TITLE_ID&week=thu$TR_PARAMS"

    fun translatedEpisodeUrl(no: Int) =
        "$TR_BASE/webtoon/detail?titleId=$TITLE_ID&no=$no$TR_PARAMS"

    // エピソード画像の直接取得用 (翻訳プロキシなし)
    fun directEpisodeUrl(no: Int) =
        "https://m.comic.naver.com/webtoon/detail?titleId=$TITLE_ID&no=$no"

    fun toTranslated(url: String): String {
        if (url.contains("translate.goog")) return url
        return url
            .replace("https://m.comic.naver.com", TR_BASE)
            .replace("https://comic.naver.com", TR_BASE) + TR_PARAMS
    }
}
