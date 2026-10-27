package com.linghit.gdmusic

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader

/** 播客单集 */
data class Episode(
    val guid: String,
    val title: String,
    val url: String,
    val durationMs: Long,
    val pubDate: String,
    val desc: String
)

/** 播客元数据（来自 RSS <channel>） */
data class PodcastMeta(
    val title: String,
    val author: String,
    val coverUrl: String,
    val desc: String,
    val episodes: List<Episode>
)

/**
 * RSS 2.0 解析器（零依赖，用系统 XmlPullParser）。
 * 兼容 iTunes 扩展：itunes:author / itunes:image@href / itunes:duration / content:encoded。
 * 纯函数：传入 RSS 文本，返回 PodcastMeta。网络请求由调用方（PodcastActivity）用 OkHttp 完成。
 */
object RssParser {

    fun parse(xml: String): PodcastMeta {
        val p = XmlPullParserFactory.newInstance().also { it.isNamespaceAware = true }.newPullParser()
        p.setInput(StringReader(xml))
        var ev = p.eventType
        var chTitle = ""; var chAuthor = ""; var chCover = ""; var chDesc = ""
        var inChannel = false; var inImage = false
        val eps = mutableListOf<Episode>()
        var inItem = false
        var iTitle = ""; var iUrl = ""; var iDur = ""; var iPub = ""; var iDesc = ""; var iGuid = ""
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG) {
                val n = p.name?.lowercase() ?: ""
                when {
                    n == "channel" -> inChannel = true
                    n == "item" || n.endsWith(":item") -> {
                        inItem = true
                        iTitle = ""; iUrl = ""; iDur = ""; iPub = ""; iDesc = ""; iGuid = ""
                    }
                    n.endsWith("image") -> {
                        val href = attr(p, "href")
                        if (href != null) { if (!inItem) chCover = href } else if (inChannel && !inItem) inImage = true
                    }
                    n == "url" && inImage -> { chCover = (p.nextText() ?: "").trim(); inImage = false }
                    inItem -> when (n) {
                        "title" -> iTitle = (p.nextText() ?: "").trim()
                        "enclosure" -> iUrl = attr(p, "url") ?: ""
                        "guid" -> iGuid = (p.nextText() ?: "").trim()
                        "pubdate" -> iPub = (p.nextText() ?: "").trim()
                        "description" -> iDesc = (p.nextText() ?: "").trim()
                        "summary" -> if (iDesc.isBlank()) iDesc = (p.nextText() ?: "").trim()
                        "duration" -> iDur = (p.nextText() ?: "").trim()
                        "encoded" -> if (iDesc.isBlank()) iDesc = (p.nextText() ?: "").trim()
                    }
                    inChannel -> when (n) {
                        "title" -> chTitle = (p.nextText() ?: "").trim()
                        "author" -> chAuthor = (p.nextText() ?: "").trim()
                        "description" -> chDesc = (p.nextText() ?: "").trim()
                        "summary" -> if (chDesc.isBlank()) chDesc = (p.nextText() ?: "").trim()
                    }
                }
            } else if (ev == XmlPullParser.END_TAG) {
                val n = p.name?.lowercase() ?: ""
                if ((n == "item" || n.endsWith(":item")) && inItem) {
                    inItem = false
                    if (iUrl.isNotBlank()) eps.add(
                        Episode(
                            guid = iGuid.ifBlank { iUrl },
                            title = iTitle.ifBlank { "未命名单集" },
                            url = iUrl,
                            durationMs = parseDur(iDur),
                            pubDate = iPub,
                            desc = iDesc
                        )
                    )
                }
                if (n == "channel") inChannel = false
            }
            ev = p.next()
        }
        return PodcastMeta(chTitle.ifBlank { "未知播客" }, chAuthor, chCover, chDesc, eps)
    }

    /** 取某命名空间/local 名属性（忽略前缀） */
    private fun attr(p: XmlPullParser, local: String): String? {
        val c = runCatching { p.attributeCount }.getOrNull() ?: 0
        for (i in 0 until c) {
            if (p.getAttributeName(i).equals(local, true)) return p.getAttributeValue(i)
        }
        return null
    }

    /** itunes:duration 兼容 HH:MM:SS / MM:SS / 纯秒数 */
    private fun parseDur(s: String): Long {
        if (s.isBlank()) return 0L
        s.toLongOrNull()?.let { return it * 1000 }
        return runCatching {
            val ps = s.split(":").map { it.toLongOrNull() ?: 0L }
            when (ps.size) {
                3 -> (ps[0] * 3600 + ps[1] * 60 + ps[2]) * 1000
                2 -> (ps[0] * 60 + ps[1]) * 1000
                else -> 0L
            }
        }.getOrDefault(0L)
    }
}
