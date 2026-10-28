package com.linghit.gdmusic

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 端侧三段式检索结果：整理好的资料文本 + 来源 + 给用户看的提示。
 */
data class Retrieved(
    val query: String,
    val context: String,
    val sources: List<SourceItem>,
    val note: String
)

/**
 * 端侧三段式检索（搜索 → 抓取 → 整理），与「用哪个模型回答」完全解耦。
 *
 * 这一段是「升级到云端」复用的核心：本地 Qwen3 与云端大模型都先调用这里拿资料，
 * 差别只在最后谁把资料读成答案。
 *
 * @param emitStage 进度回调（搜索中 / 抓取中 / 整理中），由调用方转成 UI 事件
 */
object QnaRetrieval {
    suspend fun retrieve(
        question: String,
        emitStage: suspend (String) -> Unit
    ): Retrieved = withContext(Dispatchers.IO) {
        // 单链接：跳过搜索，直接抓正文（喂给模型读）
        if (QnaPrompts.isSingleUrl(question)) {
            emitStage("抓取链接中…")
            val art = WebFetcher.fetch(question.trim())
            if (art != null) {
                val ctxText = SnippetBuilder.build(listOf(art))
                return@withContext Retrieved(
                    query = art.title.ifBlank { question },
                    context = ctxText,
                    sources = listOf(SourceItem(art.title.ifBlank { question }, art.url)),
                    note = ""
                )
            }
            return@withContext Retrieved(
                query = question,
                context = "",
                sources = emptyList(),
                note = "这个链接抓不到内容（可能需登录、反爬或已失效）。可换链接，或直接把正文贴给我。"
            )
        }

        // 阶段一：搜索
        emitStage("搜索中：$question")
        val hits = SearchEngine.search(question)
        if (hits.isEmpty()) {
            return@withContext Retrieved(
                query = question,
                context = "",
                sources = emptyList(),
                note = "没搜到结果（搜索引擎可能触发验证）。你可以：① 直接粘贴一个链接让我读；② 把相关正文贴给我。"
            )
        }
        emitStage("找到 ${hits.size} 条结果，读取摘要与正文…")

        // 阶段二：抓取 top3（抓不到正文就用搜索摘要兜底，保证模型有料）
        val arts = WebFetcher.fetchTop(hits.take(3))
        if (arts.isEmpty()) {
            return@withContext Retrieved(
                query = question,
                context = "",
                sources = hits.map { SourceItem(it.title, it.url, it.snippet) },
                note = "搜到了但没能读到内容（反爬/需登录）。可用下方链接自行查看，或把正文贴给我。"
            )
        }

        // 阶段三：整理裁剪（按上下文窗口预算裁剪，附关键数字）
        emitStage("整理资料中…")
        val ctxText = SnippetBuilder.build(arts)
        val sources = arts.map { SourceItem(it.title.ifBlank { it.url }, it.url, "") }
        Retrieved(query = question, context = ctxText, sources = sources, note = "")
    }
}
