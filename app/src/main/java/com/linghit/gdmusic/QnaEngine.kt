package com.linghit.gdmusic

import android.content.Context
import kotlinx.coroutines.flow.Flow

/**
 * 问答事件流：进度 / 流式增量 / 来源 / 错误 / 完成。
 * 端侧与云端两套 Provider 共用同一套事件协议，UI 无需关心背后是谁在答。
 */
sealed interface QnaEvent {
    /** 阶段进度提示（搜索中 / 抓取中 / 整理中 / 思考中…） */
    data class Stage(val text: String) : QnaEvent
    /** 流式输出的增量文本 */
    data class Delta(val text: String) : QnaEvent
    /** 引用来源（可点击跳转） */
    data class Sources(val items: List<SourceItem>) : QnaEvent
    /** 错误（含可恢复提示，例如云端未配置） */
    data class Error(val message: String) : QnaEvent
    /** 完成 */
    data object Done : QnaEvent
}

data class SourceItem(val title: String, val url: String, val snippet: String = "")

enum class ProviderKind { LOCAL, CLOUD }

/**
 * 问答 Provider 抽象。
 *
 * 关键设计：检索（搜索 → 抓取 → 整理）与「谁来读材料」解耦。
 *   - 端侧三段式：检索在手机上跑，最后用离线 Qwen3 读材料；
 *   - 升级到云端：检索仍端侧跑（模型自己打不开网页），最后改用云端大模型读材料。
 * 所以「升级到云端」只是把 AnswerProvider 的实现换掉，检索链路完全复用。
 */
interface QnaProvider {
    val id: String
    val label: String
    val kind: ProviderKind
    /** 是否已可工作（云端需配好 endpoint + key） */
    fun isReady(ctx: Context): Boolean
    /** 本地模型需先加载引擎；云端通常无需 */
    suspend fun prepare(ctx: Context)
    /** 问一句，返回事件流 */
    fun ask(ctx: Context, question: String): Flow<QnaEvent>
}

/** 云端配置（OpenAI 兼容：endpoint 填 base URL，如 https://api.siliconflow.cn/v1） */
data class CloudConfig(val endpoint: String, val apiKey: String, val model: String) {
    fun isValid(): Boolean = endpoint.isNotBlank() && apiKey.isNotBlank() && model.isNotBlank()
}

object CloudConfigStore {
    private const val FILE = "qna_cloud"
    fun load(ctx: Context): CloudConfig {
        val p = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        return CloudConfig(
            endpoint = p.getString("endpoint", "") ?: "",
            apiKey = p.getString("apiKey", "") ?: "",
            model = p.getString("model", "") ?: ""
        )
    }
    fun save(ctx: Context, c: CloudConfig) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().apply {
            putString("endpoint", c.endpoint)
            putString("apiKey", c.apiKey)
            putString("model", c.model)
            apply()
        }
    }
}

object QnaPrompts {
    /** 输入是否为单个网址？（直接抓取模式，跳过搜索） */
    fun isSingleUrl(text: String): Boolean {
        val t = text.trim()
        return t.matches(Regex("^https?://\\S+$", RegexOption.IGNORE_CASE))
    }

    fun system(useCloud: Boolean): String = buildString {
        append("你是「从前慢」App 内置的问答助手，帮用户查资料、查数据、写公众号和小红书内容。\n")
        append("回答规则：\n")
        append("1. 用简体中文，第二人称口吻，长短句混排，口语化，不要书面腔。\n")
        append("2. 不用破折号堆砌；不用「赋能/抓手/闭环/颗粒度/底层逻辑」这类套话。\n")
        append("3. 下方会给你几条带编号的参考资料，优先依据资料回答，句末用 [编号] 标注来源。\n")
        append("4. 资料里的数字、价格、日期、百分比原样保留，不要估算改写。\n")
        append("5. 资料不足就如实说「资料里没提到」，不要编造。\n")
        append("6. 若用户要写内容（公众号/小红书），直接给成稿，去 AI 味。\n")
    }

    /** 组装用户消息：问题 + 资料 */
    fun userWithContext(question: String, context: String): String = buildString {
        if (context.isNotBlank()) {
            append("【参考资料】\n")
            append(context)
            append("\n\n")
        }
        append("【问题】")
        append(question)
    }
}

object QnaEngine {
    private const val FILE = "qna"
    private const val KEY_PROVIDER = "provider"

    /** 全部 Provider；新增「升级到云端」类型时只往这里加 */
    val providers: List<QnaProvider> = listOf(LocalAnswerProvider, CloudAnswerProvider)

    fun active(ctx: Context): QnaProvider {
        val id = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY_PROVIDER, null)
        return providers.firstOrNull { it.id == id } ?: LocalAnswerProvider
    }

    fun setActive(ctx: Context, id: String) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(KEY_PROVIDER, id).apply()
    }

    fun byId(id: String): QnaProvider? = providers.firstOrNull { it.id == id }
}
