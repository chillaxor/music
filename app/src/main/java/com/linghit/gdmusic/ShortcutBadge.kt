package com.linghit.gdmusic

/**
 * 首页快捷位徽章规则（A+C 方案）：
 *  - page 类（App 内功能）：用自带 emoji，如 ✏️ 记一笔、🎬 影视仓
 *  - link 类（自定义/📌 钉来的链接）：先查站点字典——域名或名称命中 → 语义 emoji；
 *    都没命中 → 「首字 + 哈希配色」字母徽章（域名首字母优先，取不到用名称首字）。
 *
 * 目的：一排链接不再共用 🔗 分不清；同首字母（淘宝/贴吧/头条）也有域名/字典分层兜底。
 */
object ShortcutBadge {

    /** 徽章配色：白字压在饱和色上。按 item.id() 哈希取色，同色相邻概率低 */
    val PALETTE = intArrayOf(
        0xFFE5534B.toInt(), // 红
        0xFFE8862E.toInt(), // 橙
        0xFFC9A227.toInt(), // 金
        0xFF3FA45B.toInt(), // 绿
        0xFF12A594.toInt(), // 青
        0xFF4B8BE5.toInt(), // 蓝
        0xFF7B61E5.toInt(), // 紫
        0xFFD96BA8.toInt()  // 粉
    )

    /** 域名关键词 → emoji（url 小写子串匹配） */
    private val SITE_EMOJI = listOf(
        "bilibili" to "📺", "xhscdn" to "📕", "xiaohongshu" to "📕", "xhs." to "📕",
        "zhihu" to "🧠", "taobao" to "🛒", "tmall" to "🛒", "jd.com" to "🛍",
        "baidu" to "🐻", "weibo" to "🌊", "douyin" to "🎵", "kuaishou" to "⚡",
        "github" to "🐙", "gitee" to "🦊", "youtube" to "▶️", "youtu.be" to "▶️",
        "twitter" to "🐦", "x.com" to "🐦", "qq.com" to "🐧", "163.com" to "📮",
        "126.com" to "📮", "sina" to "📰", "toutiao" to "📰", "dingtalk" to "📌",
        "feishu" to "🕊", "larksuite" to "🕊", "notion" to "📓", "wikipedia" to "📚",
        "google" to "🔍", "meituan" to "🍜", "ele.me" to "🍔", "csdn" to "💻",
        "juejin" to "⛏", "weixin" to "💬", "aliyun" to "☁", "tiktok" to "🎧",
        "mi.com" to "📱", "huawei" to "📶", "apple" to "🍎", "amazon" to "📦",
        "bilibili" to "📺"
    ).let { list ->
        // 去重（bilibili 写了两遍，编译期不影响，这里防御性收敛）
        list.distinctBy { it.first }
    }

    /** 名称关键词 → emoji（label 小写子串匹配，管深链/短链没有可认域名的情况） */
    private val LABEL_EMOJI = listOf(
        "哔哩" to "📺", "b站" to "📺", "小破站" to "📺",
        "小红" to "📕", "红薯" to "📕",
        "知乎" to "🧠", "淘宝" to "🛒", "天猫" to "🛒", "京东" to "🛍",
        "百度" to "🐻", "微博" to "🌊", "抖音" to "🎵", "快手" to "⚡",
        "头条" to "📰", "钉钉" to "📌", "飞书" to "🕊", "微信" to "💬",
        "腾讯" to "🐧", "网易" to "📮", "美团" to "🍜", "饿了" to "🍔",
        "github" to "🐙", "油管" to "▶️", "推特" to "🐦", "谷歌" to "🔍",
        "维基" to "📚", "阿里" to "☁", "网盘" to "☁",
        "邮箱" to "📮", "邮件" to "📮", "地图" to "🗺", "翻译" to "🔤",
        "豆瓣" to "📗", "快递" to "📦", "外卖" to "🍔", "记账" to "📒"
    )

    /**
     * @param text 徽章内容（emoji 或单个字符）
     * @param emoji true=渲染 emoji（底色用主题色软底）；false=字母徽章（底色用 color）
     */
    class Badge(val text: String, val emoji: Boolean, val color: Int)

    fun of(item: HomeShortcutStore.Item): Badge {
        if (item.type != "link") return Badge(item.emoji.ifBlank { "🔖" }, true, 0)
        val url = item.url.lowercase()
        val label = item.label.lowercase()
        for ((k, e) in SITE_EMOJI) if (url.contains(k)) return Badge(e, true, 0)
        for ((k, e) in LABEL_EMOJI) if (label.contains(k)) return Badge(e, true, 0)
        // 字母徽章：域名第一个字母/数字（www. 剥掉），取不到用名称首字
        val host = url.substringAfter("://").substringBefore("/").removePrefix("www.")
        val letter = host.firstOrNull { it.isLetterOrDigit() }?.uppercase()?.toString()
            ?: item.label.firstOrNull()?.toString()
            ?: "?"
        val h = item.id().hashCode()
        val color = PALETTE[((h % PALETTE.size) + PALETTE.size) % PALETTE.size]
        return Badge(letter, false, color)
    }
}
