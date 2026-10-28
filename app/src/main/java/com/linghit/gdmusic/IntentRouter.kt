package com.linghit.gdmusic

import java.util.regex.Pattern

/**
 * 语音意图路由：规则快路优先（毫秒级、零依赖），识别不到才走端侧 LLM 兜底。
 *
 * 三类可落地动作：
 *  - Play   播放音乐（本地库优先，在线源兜底；复用 PlayerManager）
 *  - Record 记账（写入 networth_store，复刻 index.html 的 flow 字段与 applyFlow 余额逻辑）
 *  - Remind 提醒（ReminderStore + ReminderScheduler）
 * 其余当 Chat（闲聊，由 VoiceActivity 决定是否丢给端侧 LLM）。
 *
 * 记账字段说明（对应 index.html 的 flow 结构）：
 *  - type   支出 / 收入 / 转账
 *  - account 账户（见下方「账户两段式抽取」）
 *  - who    消费对象（「给我妈买」→ 我妈）
 *  - to     转账转入账户（仅转账）
 *  - note   备注（剩余描述词，如「餐/淘宝/午饭」）
 *
 * ★ 账户两段式抽取（本次改造重点）：
 *   路由层「不猜账户名」——猜错比不猜更糟。只产出按优先级排好的候选串 accountHints：
 *     ① 明确指定：「用/从/在 + 名称」        → explicitAccount = true
 *     ② 句首名称：「花呗付 / 招行记 / 微信买」
 *     ③ 文本窗口：剩余描述里的 2~4 字连续片段
 *   再由 LedgerBridge 拿记账页里真实存在的 assets/liabs 账户名去校验：
 *     匹配上才落库；匹配不上就明确报错并列出可用账户（而不是偷偷用默认账户）。
 */
sealed class VoiceIntent {
    data class Play(val query: String) : VoiceIntent()
    data class Record(
        val amount: Double,
        val type: String = "支出",
        val accountHints: List<String> = emptyList(),
        val explicitAccount: Boolean = false,
        val who: String = "",
        val to: String = "",
        val note: String = ""
    ) : VoiceIntent()
    data class Remind(val timeText: String, val content: String) : VoiceIntent()
    data class Chat(val text: String) : VoiceIntent()
}

object IntentRouter {
    /**
     * 记账触发词：仅用于判定「这句是不是在记账」。
     * ⚠️ 不能含裸「花」——「花呗」这类账户名里带「花」，含了会把整句误判/误剥。
     */
    private val RECORD_TRIGGER = Regex("(记一笔|记一笔账|记账|记|花了|支出|消费|付了|买了|充值|买|付|转)")

    /**
     * 金额：数字（12 / 12.5）、中文数字（十二 / 二十八）、单个中文数字必须跟货币单位（十元）。
     * ⚠️ 中文数字不能允许单个「一」——否则「记一笔12元」里的「一」会被当成金额 1。
     */
    private val AMOUNT_RE = Regex(
        "([0-9]+(?:\\.[0-9]+)?|[零一两二三四五六七八九十百千]{2,}|[零一两二三四五六七八九十百千](?=\\s*[元块蚊刀]))"
    )

    private val CN_NUM = mapOf(
        "零" to 0, "一" to 1, "二" to 2, "两" to 2, "三" to 3, "四" to 4, "五" to 5,
        "六" to 6, "七" to 7, "八" to 8, "九" to 9, "十" to 10, "百" to 100, "千" to 1000
    )

    fun route(text: String): VoiceIntent {
        val t = text.trim().replace("。", "").replace("，", " ")

        // 1) 播放
        val playM = Regex("(播放|来首|来一首|听听|听首|听一下|我想听|放首|放一首)\\s*[:：]?\\s*(.+)").find(t)
        if (playM != null) {
            val q = playM.groupValues[2].trim().removeSuffix("歌").trim()
            if (q.isNotBlank()) return VoiceIntent.Play(q)
        }

        // 2) 记账：先判触发词，再在整句里找金额（金额在原句找，才能让账户名保留上下文）
        if (RECORD_TRIGGER.containsMatchIn(t)) {
            val amtM = AMOUNT_RE.find(t)
            if (amtM != null) {
                val amount = parseAmount(amtM.groupValues[1])
                if (amount > 0) {
                    // 只摘掉金额，其余文本（含动词）全部保留 —— 动词是账户名的边界信号
                    val raw = t.replace(amtM.value, " ")
                    return parseRecord(amount, raw)
                }
            }
        }

        // 3) 提醒
        val remM = Regex("(提醒|提醒我|定时|别忘了|记着|记得|到时)\\s*[:：]?\\s*(.+)").find(t)
        if (remM != null) {
            val rest = remM.groupValues[2]
            val timeM = Regex("(今天|明天|后天|大后天|周一|周二|周三|周四|周五|周六|周日|周天|上午|中午|下午|晚上|早上|今晚|明早|(\\d{1,2})\\s*[点:：时])").find(rest)
            val timeText = timeM?.value ?: "待定"
            val content = rest.replaceFirst(Regex("^\\s*${Pattern.quote(timeText)}\\s*[:：]?\\s*"), "")
                .ifBlank { rest }
            return VoiceIntent.Remind(timeText, content)
        }

        // 4) 兜底闲聊
        return VoiceIntent.Chat(t)
    }

    private fun parseRecord(amount: Double, raw: String): VoiceIntent.Record {
        // 类型
        var type = "支出"
        if (raw.contains("转账") || raw.contains("转给") || raw.contains("转到") || raw.contains("转入") ||
            raw.contains("转出") || raw.contains("转") ||
            (raw.contains("还") && (raw.contains("给") || raw.contains("到") || raw.contains("卡") || raw.contains("账")))
        ) {
            type = "转账"
        } else if (raw.contains("收入") || raw.contains("工资") || raw.contains("到账") ||
            raw.contains("收款") || raw.contains("入账") || raw.contains("收了")
        ) {
            type = "收入"
        }

        // 消费对象：给/为/替/帮 X (买|付|花|的|吃|喝)  —— X 必须懒惰匹配，否则「给我妈买花呗」会把「花呗」也吃进来
        var who = ""
        val whoM = Regex("(给|为|替|帮)\\s*([\\u4e00-\\u9fa5A-Za-z0-9]{1,6}?)\\s*(买|付|花|的|吃|喝)").find(raw)
        if (whoM != null) who = whoM.groupValues[2]

        // 转账转入账户：转(给|到|入)? X / 还给 X
        var to = ""
        if (type == "转账") {
            val toM = Regex("(转(给|到|入)?|还给)\\s*([\\u4e00-\\u9fa5A-Za-z0-9]{1,6})").find(raw)
            if (toM != null) to = toM.groupValues[3]
        }

        // ---- 账户候选（按优先级）----
        val hints = mutableListOf<String>()
        var explicit = false
        var explicitSpan: String? = null   // 明确账户在原文里的整段（备注清理用）
        var leadSpan: String? = null       // 句首账户的整段

        // ① 明确指定：用/从/在 + 名称（不做任何字符剥离，交给 LedgerBridge 用真实账户名校验）
        val accM = Regex("(用|从|在|用我的|从我的)\\s*(我的)?\\s*([\\u4e00-\\u9fa5A-Za-z0-9]{1,8})").find(raw)
        if (accM != null) {
            val h = trimTrailingVerb(accM.groupValues[3])
            if (h.isNotBlank()) { hints.add(h); explicit = true; explicitSpan = accM.value }
        }

        // ② 句首「名称 + 动词」：花呗付 / 招行记 / 微信买
        val leadM = Regex("^\\s*([\\u4e00-\\u9fa5A-Za-z0-9]{1,6}?)\\s*(付|记|转|买|支出|消费|充值|还)").find(raw)
        if (leadM != null) {
            val h = trimTrailingVerb(leadM.groupValues[1])
            if (h.isNotBlank() && h !in hints) { hints.add(h); leadSpan = h }
        }

        // ③ 文本窗口候选：剔掉消费对象/转账对象片段后，剩下的 2~4 字连续片段（长的优先）
        val rest = raw
            .replace(Regex("(给|为|替|帮)\\s*[\\u4e00-\\u9fa5A-Za-z0-9]{1,6}?\\s*(买|付|花|的|吃|喝)"), " ")
            .replace(Regex("(转(给|到|入)?|还给)\\s*[\\u4e00-\\u9fa5A-Za-z0-9]{1,6}"), " ")
        for (len in listOf(4, 3, 2)) {
            var i = 0
            while (i + len <= rest.length) {
                val w = rest.substring(i, i + len)
                if (w.any { it.isLetter() } && w.all { it.isLetterOrDigit() } && w !in hints) hints.add(w)
                i++
            }
        }

        // ---- 备注：只剔除「真正当账户用过的那两段」+ 消费对象/转账对象 + 类型词 + 残留动词 ----
        var noteSrc = raw
        if (!explicitSpan.isNullOrBlank()) noteSrc = noteSrc.replaceFirst(explicitSpan, " ")
        if (!leadSpan.isNullOrBlank()) noteSrc = noteSrc.replaceFirst(leadSpan, " ")
        noteSrc = noteSrc
            .replace(Regex("(给|为|替|帮)\\s*[\\u4e00-\\u9fa5A-Za-z0-9]{1,6}?\\s*(买|付|花|的|吃|喝)"), " ")
            .replace(Regex("(转(给|到|入)?|还给)\\s*[\\u4e00-\\u9fa5A-Za-z0-9]{1,6}"), " ")
            .replace(Regex("记一笔|记一笔账|记账|花了|支出|消费|付了|买了|充值|转账|收入|工资|到账|收款|入账|收了"), " ")
            // ⚠️ 只剥动词/单位，不能剥「花/支/宝」——「花呗」「支付宝」这类账户名里就是这些字
            .replace(Regex("[付记了元块用从在消充]"), " ")
            .replace(Regex("[，。、\\s]+"), " ")
            .trim()

        return VoiceIntent.Record(amount, type, hints, explicit, who, to, noteSrc)
    }

    /** 剥掉账户名末尾的动词尾巴：「花呗付」→「花呗」、「工资卡转」→「工资卡」
     *  （不剥「宝/支」等字，否则「支付宝」会被拆成「支付」） */
    private fun trimTrailingVerb(s: String): String {
        var r = s
        while (r.length > 2 && r.last() in "付记转买还给到") r = r.dropLast(1)
        return r
    }

    /** 中文数字 → 阿拉伯数字（支持 十二 / 二十 / 一百零五 等基础组合） */
    private fun parseAmount(raw: String): Double {
        if (raw.matches(Regex("^[0-9.]+$"))) return raw.toDoubleOrNull() ?: 0.0
        var section = 0
        var num = 0
        for (ch in raw) {
            val v = CN_NUM[ch.toString()] ?: continue
            when {
                v < 10 -> num = v
                v == 10 -> { section += (if (num == 0) 1 else num) * 10; num = 0 }
                v == 100 -> { section += (if (num == 0) 1 else num) * 100; num = 0 }
                v == 1000 -> { section += (if (num == 0) 1 else num) * 1000; num = 0 }
            }
        }
        return (section + num).toDouble()
    }
}
