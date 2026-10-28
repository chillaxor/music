package com.linghit.gdmusic

/**
 * 资料整理：把抓取到的正文/摘要拼成可喂模型的参考资料，并附「关键数字」便于查数据/价格。
 *
 * ⚠️ **预算必须算准（2026-10-28 踩坑）**：Qwen3-1.7B 的上下文窗口是 2048 token，
 * 且 `maxOutputToken = 1024`，留给输入的只有约 1024 token。中文按 1 字 ≈ 1 token 保守估，
 * 系统提示词本身约 200 字，那么参考资料最多只能给 **约 600 字**。
 * 之前这里给了 1600 字，**远超上限**——LiteRT-LM 不会报错，而是**静默截断输入**，
 * 结果就是「参考资料被丢掉、模型拿不到任何数据」，回答看着就是空的。
 *
 * 所以这里的预算按 2048 窗口反推：
 *   系统提示 200 + 参考资料 600 + 问题 30 + 回答 768 ≈ 1600 token，留足安全余量。
 */
object SnippetBuilder {
    /** 第一篇给的额度（信息量最大） */
    private const val FIRST_MAX = 300
    /** 其余各篇额度 */
    private const val OTHER_MAX = 180
    /** 参考资料总预算（字） */
    private const val TOTAL_MAX = 600
    /** 「关键数字」最多保留几个（数量本身也吃预算） */
    private const val NUM_MAX = 12

    fun build(articles: List<Article>): String {
        if (articles.isEmpty()) return ""

        val allText = articles.joinToString("\n") { it.text }
        val numbers = extractNumbers(allText).take(NUM_MAX)
        val numBlock = if (numbers.isEmpty()) "" else "【关键数字】\n" + numbers.joinToString("；")

        // 关键数字比正文更值钱（查价格/查数据全靠它），先给它留位置，再分正文
        var budget = TOTAL_MAX - numBlock.length - 30
        if (budget < FIRST_MAX) budget = FIRST_MAX

        val parts = mutableListOf<String>()
        var used = 0
        for ((i, a) in articles.withIndex()) {
            if (used >= budget) break
            val title = a.title.ifBlank { a.url }
            val room = budget - used - title.length - 6
            if (room <= 20) break
            val max = if (i == 0) FIRST_MAX else OTHER_MAX
            val slice = a.text.take(minOf(max, room))
            parts.add("[${i + 1}] $title\n$slice")
            used += slice.length + title.length + 6
        }

        val body = parts.joinToString("\n\n")
        return if (numBlock.isNotEmpty()) "$body\n\n$numBlock" else body
    }

    /** 抽取价格/百分比/日期/数量等，帮助「查数据 / 查价格」类问题 */
    fun extractNumbers(text: String): List<String> {
        val pats = listOf(
            // 全角￥（￥5299）与半角¥（¥5299）都要认
            Regex("""[¥￥]\s?\d[\d,]*\.?\d*"""),
            Regex("""人民币\s?\d[\d,]*\.?\d*"""),
            // 房价最常见形态：41006元/㎡、10727元/平
            Regex("""\d[\d,]*\.?\d*\s*元\s*(?:/|每)?\s*(?:㎡|平米|平|平方米)?"""),
            Regex("""\$\s?\d[\d,]*\.?\d*"""),
            Regex("""\d+(\.\d+)?%"""),
            Regex("""\d{4}\s*[-/年]\s*\d{1,2}\s*[-/月]\s*\d{1,2}\s*日?"""),
            Regex("""\d{4}\s*年"""),
            Regex("""\d+(\.\d+)?\s*(万|亿|倍|套|台|套房源)"""),
            Regex("""\d+\s*(?:室|厅|卫)""")
        )
        val out = linkedSetOf<String>()
        for (p in pats) for (m in p.findAll(text)) out.add(m.value.trim())
        return out.take(30).toList()
    }
}
