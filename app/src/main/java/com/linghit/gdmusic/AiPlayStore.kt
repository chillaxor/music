package com.linghit.gdmusic

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * AI 玩法模板库。
 *
 * 设计（按用户要求，不固化）：
 *   - 内置 6 个默认模板（写作/阅读/效率/语言），永远存在、不可删；
 *   - 用户可在玩法页把「当前指令框里的文本」存为自定义模板（固化），之后从下拉里选；
 *   - 没存过就用默认内置模板（默认选中第一个）。
 * 自定义模板存 SharedPreferences（JSON），与内置合并后供 Spinner 展示。
 *
 * 模板的 prompt 不写死公众号规则——只是示例指令，用户在指令框里可随手改，
 * 真正决定风格的是玩法专用 system（见 QnaActivity.PLAY_SYSTEM）。
 */
data class PlayTemplate(
    val id: String,
    val name: String,
    val category: String,
    val prompt: String,
    val builtin: Boolean
)

object AiPlayStore {
    private const val FILE = "ai_play"
    private const val KEY = "custom"

    val builtins: List<PlayTemplate> = listOf(
        PlayTemplate(
            "title", "起标题", "写作",
            "给上面的内容起 5 个公众号/小红书标题，每个不超过 20 字，不标题党，用第二人称视角，带一点烟火气。直接列 1-5，不要解释。",
            true
        ),
        PlayTemplate(
            "opening", "写开头", "写作",
            "用第二人称编辑口吻写一段开头钩子：先戳一个读者天天遇到的小痛点，再自然引出上面的内容。120 字以内，不用破折号，引用一律用「」直角引号。",
            true
        ),
        PlayTemplate(
            "deai", "去AI味", "写作",
            "改写上面的文字，去掉 AI 味：长短句混排，口语糙感，不用「赋能/抓手/闭环/颗粒度」这类套话，不用冒号堆砌，保留原意。",
            true
        ),
        PlayTemplate(
            "summary", "摘要", "阅读",
            "把上面的内容压成 3 句人话摘要，保留关键数字和结论，不编造。",
            true
        ),
        PlayTemplate(
            "todos", "提取待办", "效率",
            "从上面这段对话或文字里抽出「要做的事」和「结论」，每条一行，可勾选。时间、人物、截止日原样保留。",
            true
        ),
        PlayTemplate(
            "translate", "翻译改写", "语言",
            "把上面的内容翻成英文，或者按我的要求改成更口语/更正式，保留术语和专有名词。",
            true
        )
    )

    /** 内置 + 自定义，按「内置在前、自定义在后」合并 */
    fun list(ctx: Context): List<PlayTemplate> = builtins + loadCustom(ctx)

    fun loadCustom(ctx: Context): List<PlayTemplate> {
        val raw = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                PlayTemplate(
                    o.getString("id"),
                    o.getString("name"),
                    o.optString("category", "自定义"),
                    o.getString("prompt"),
                    false
                )
            }
        }.getOrDefault(emptyList())
    }

    /** 新增或覆盖（按 id 判重）一个自定义模板 */
    fun saveCustom(ctx: Context, t: PlayTemplate) {
        val cur = loadCustom(ctx).toMutableList()
        val idx = cur.indexOfFirst { it.id == t.id }
        if (idx >= 0) cur[idx] = t else cur.add(t)
        val arr = JSONArray()
        cur.forEach { o ->
            arr.put(JSONObject().apply {
                put("id", o.id)
                put("name", o.name)
                put("category", o.category)
                put("prompt", o.prompt)
            })
        }
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
    }

    /** 生成自定义模板 id（时间戳，避免与内置 id 撞） */
    fun newId(): String = "u" + System.currentTimeMillis().toString(36)
}
