package com.linghit.gdmusic

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow

/**
 * 端侧三段式 Provider：检索在手机上跑，最后用离线 Qwen3 读材料。
 *
 * 模型权重冻结（知识截止约 2025-03），所以「查最新新闻/价格」必须靠三段式检索拿资料，
 * 模型只负责阅读材料、组织答案。这也是和云端 Provider 唯一不同的地方。
 *
 * ⚠️ 这里必须用 channelFlow 而不是 flow：检索（QnaRetrieval）在 Dispatchers.IO 上
 * 通过回调 emit 进度，云端 streamAnswer 也在 IO 上吐字。flow 构建器要求 emit 与收集
 * 在同一调度器，跨调度器 emit 会抛 "Flow invariant is violated"；channelFlow 的 send
 * 允许跨调度器安全发射，正好覆盖「检索 IO + 生成 Default」混用场景。
 */
object LocalAnswerProvider : QnaProvider {
    override val id = "local"
    override val label = "端侧三段式（离线模型）"
    override val kind = ProviderKind.LOCAL

    override fun isReady(ctx: Context): Boolean = AiEngine.isReady

    override suspend fun prepare(ctx: Context) {
        if (AiEngine.isReady) return
        // 自动加载已安装模型（默认 1.7B）；没装则什么都不做，ask 会提示去下载
        val spec = ModelManager.CATALOG.firstOrNull { ModelManager.isInstalled(ctx, it) }
            ?: ModelManager.CATALOG[1]
        if (ModelManager.isInstalled(ctx, spec)) {
            AiEngine.load(ctx, ModelManager.fileOf(ctx, spec).absolutePath, useGpu = true)
        }
    }

    override fun ask(ctx: Context, question: String): Flow<QnaEvent> = channelFlow {
        if (!AiEngine.isReady) {
            send(QnaEvent.Error("端侧模型未加载：点本页右上角「🧠 模型」进入模型页，下载并加载一个 .litertlm 模型（推荐 Qwen3-1.7B），再来问答。"))
            return@channelFlow
        }
        val r = QnaRetrieval.retrieve(question) { send(QnaEvent.Stage(it)) }
        if (r.note.isNotBlank()) send(QnaEvent.Stage(r.note))
        if (r.sources.isNotEmpty()) send(QnaEvent.Sources(r.sources))
        if (r.context.isBlank() && r.note.isBlank()) {
            send(QnaEvent.Stage("（无外部资料，端侧模型按自身知识回答，知识截止约 2025-03，可能过时）"))
        }
        send(QnaEvent.Stage("端侧模型思考中…"))
        val sys = QnaPrompts.system(useCloud = false)
        val usr = QnaPrompts.userWithContext(question, r.context)
        AiEngine.generate(ctx, sys, usr).collect { send(QnaEvent.Delta(it)) }
        send(QnaEvent.Done)
    }
}
