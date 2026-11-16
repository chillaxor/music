package com.linghit.gdmusic

/**
 * 小说书源统一模型。
 *
 * so-novel 与 Legado（阅读 3.0）的规则字段不同，解析层把两者归一到这个模型，
 * 后面的搜索扇出、去重、目录/正文抓取、健康度都用同一套逻辑。
 */
data class NovelSource(
    val id: String,
    val name: String,
    val dialect: String,                 // legado | sonovel
    val baseUrl: String,
    val searchUrl: String,
    val searchMethod: String = "GET",
    val searchBody: String = "",
    val headers: Map<String, String> = emptyMap(),
    val bookList: String,
    val fieldName: String,
    val fieldAuthor: String = "",
    val fieldBookUrl: String,
    val fieldCover: String = "",
    val fieldIntro: String = "",
    val fieldLatest: String = "",
    val tocUrl: String = "",             // 可选：目录独立地址
    val tocList: String,
    val tocName: String = "text",
    val tocLink: String = "href",
    val contentRule: String,
    val nextContentRule: String = "",
    val paragraphRule: String = "",      // so-novel paragraphTag
    val filterText: String = "",         // so-novel filterTxt
    val weight: Int = 0,
    val respondMs: Long = 0L,
    val lastUpdate: String = ""
)

data class NovelBook(
    val sourceId: String,
    val sourceName: String,
    val name: String,
    val author: String,
    val url: String,
    val cover: String = "",
    val intro: String = "",
    val latest: String = "",
    val id: String = ""
)

data class NovelChapter(
    val index: Int,
    val name: String,
    val url: String
)

data class NovelSearchResult(
    val books: List<NovelBook>,
    val failedSources: Int,
    val totalSources: Int,
    val skippedSources: Int
)

/** 书架条目：书架、进度、收藏三件事都挂在这条记录上 */
data class NovelShelfItem(
    val id: String,
    val name: String,
    val author: String,
    val sourceId: String,
    val sourceName: String,
    val bookUrl: String,
    val cover: String = "",
    val chapterIndex: Int = 0,
    val paragraphIndex: Int = 0,
    val scrollY: Int = 0,
    val favorite: Boolean = false,
    val totalChapters: Int = 0,
    val lastRead: Long = 0L
)
