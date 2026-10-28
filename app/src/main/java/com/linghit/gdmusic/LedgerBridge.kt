package com.linghit.gdmusic

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 记账原生桥：直接写 networth_store 的 payload（与 index.html 的 window.Android.saveData 同一份数据）。
 *
 * 关键点（之前版本 bug）：
 *  - flow 真实字段是 {date, account, type, amount, note, to, who}，
 *    account 必须是 assets/liabs 里真实存在的账户名（资产账户用 .cur、负债账户用 .amt）；
 *  - 记账页的 applyFlow(f) 会同步调账户余额，index.html 每次保存都重算净资产。
 *    所以这里必须复刻 applyFlow 的余额逻辑，否则流水记了、余额没动，净资产对不上。
 *
 * 边界：若记账页正打开且未保存，理论上可能被其内存覆盖（常见场景是语音时不在记账页）。
 * 下次打开记账页时 onResume 会调 __reloadFromNative 从这份 payload 重新拉取，所以这是唯一真源。
 */
object LedgerBridge {

    data class LedgerRequest(
        val amount: Double,
        val type: String = "支出",          // 支出 / 收入 / 转账
        val accountHints: List<String> = emptyList(),  // 账户候选（按优先级，来自 IntentRouter）
        val explicitAccount: Boolean = false,          // 用户是否明确说了账户（说了就只认它，认不到就报错）
        val note: String? = null,            // 备注
        val who: String? = null,             // 消费对象（给谁/为谁）
        val to: String? = null               // 转账转入账户（仅 type=转账）
    )

    data class RecordResult(val ok: Boolean, val message: String)

    fun record(ctx: Context, req: LedgerRequest): RecordResult = runCatching {
        val prefs = ctx.getSharedPreferences("networth_store", Context.MODE_PRIVATE)
        val raw = prefs.getString("payload", "") ?: ""
        val data = if (raw.isBlank()) JSONObject() else JSONObject(raw)
        if (!data.has("assets") || data.get("assets") !is JSONArray) data.put("assets", JSONArray())
        if (!data.has("liabs") || data.get("liabs") !is JSONArray) data.put("liabs", JSONArray())
        if (!data.has("flows") || data.get("flows") !is JSONArray) data.put("flows", JSONArray())

        val account: String = if (req.explicitAccount) {
            // 用户明确指定了账户：只认这一条候选，认不到就报错 + 列出真实账户（不偷偷换成默认账户）
            val h = req.accountHints.firstOrNull()
            if (h.isNullOrBlank()) {
                defaultAccount(data)
                    ?: return@runCatching RecordResult(false, "还没有账户，请先打开记账页添加一个资产/负债账户")
            } else {
                val m = matchAccount(data, h)
                when {
                    m.account == null -> return@runCatching RecordResult(
                        false, "没找到账户「$h」，你的账户：${listAccountNames(data)}"
                    )
                    m.candidates.size > 1 -> return@runCatching RecordResult(
                        false, "「$h」匹配到多个账户：${m.candidates.joinToString("、")}，请说完整名字"
                    )
                    else -> m.account
                }
            }
        } else {
            // 未明确指定：按候选优先级逐个试；有歧义的候选先跳过，继续往下找
            var hit: String? = null
            var ambiguous: List<String> = emptyList()
            for (h in req.accountHints) {
                val m = matchAccount(data, h)
                if (m.account == null) continue
                if (m.candidates.size > 1) { if (ambiguous.isEmpty()) ambiguous = m.candidates; continue }
                hit = m.account
                break
            }
            if (hit == null && ambiguous.isNotEmpty()) {
                return@runCatching RecordResult(
                    false, "匹配到多个账户：${ambiguous.joinToString("、")}，请说完整名字"
                )
            }
            hit ?: defaultAccount(data)
                ?: return@runCatching RecordResult(false, "还没有账户，请先打开记账页添加一个资产/负债账户")
        }

        // 转账的转入账户同样要落到真实账户名（否则 applyFlow 找不到目标账户，余额不会平移）
        val toAccount = if (req.type == "转账" && !req.to.isNullOrBlank()) {
            val m = matchAccount(data, req.to)
            when {
                m.account == null -> return@runCatching RecordResult(
                    false, "没找到转入账户「${req.to}」，你的账户：${listAccountNames(data)}"
                )
                m.candidates.size > 1 -> return@runCatching RecordResult(
                    false, "转入账户「${req.to}」匹配到多个：${m.candidates.joinToString("、")}，请说完整名字"
                )
                else -> m.account
            }
        } else ""

        val f = JSONObject().apply {
            put("date", today())
            put("account", account)
            put("type", req.type)
            put("amount", String.format(Locale.US, "%.2f", req.amount))
            // 备注里如果混进了账户名，去掉（账户已经单独成字段了）
            put("note", (req.note ?: "").replace(account, "").replace(toAccount, "").trim())
            put("to", toAccount)
            if (!req.who.isNullOrBlank()) put("who", req.who)
        }

        applyFlow(data, f)   // 复刻 index.html applyFlow：同步调整账户余额

        val flows = data.getJSONArray("flows")
        flows.put(0, f)      // 插到最前（复刻 data.flows.unshift(f)）

        prefs.edit().putString("payload", data.toString()).apply()
        RecordResult(true, buildMessage(f))
    }.getOrElse { RecordResult(false, "记账失败：${it.message}") }

    /**
     * 候选串 → 真实账户名的鲁棒匹配。
     *
     * 四级降级：
     *  1) 精确相等（账户就叫这个名字）
     *  2) 互相包含（候选「花呗付」⊃账户「花呗」；账户「微信钱包」⊃候选「微信」）
     *  3) 前缀扫描：把候选从头逐级截短再试（候选「招行卡付」→ 招行卡付 / 招行卡 / 招行）
     *  4) 子序列判定（缩写语义）：候选「招行」的字符按顺序出现在「招商银行」里 → 命中
     *
     * ⚠️ 第 4 级必须用「子序列」而不是「字符集合重合」：
     *    子序列：招行 ⊆ 招商银行 ✓，但「余额宝付」⊄ 支付宝 ✗（余额宝 ≠ 支付宝，不会误配）
     *    字符重合：会算出「余额宝付」与「支付宝」重合 3/4 → 把余额宝的账记到支付宝头上（错账）
     *
     * ⚠️ 同一级命中多个账户时：**取名字最短的那个**（最具体），与账户表顺序无关。
     *    例：账户有「中信信用卡」和「中信卡」，说「中信」→ 落「中信卡」（更短=更具体），
     *    而不是取决于谁建在前面。
     * ⚠️ 同长度仍有多候选（微信钱包 / 微信支付）→ 真区分不开，返回歧义让用户说全名。
     */
    private data class AccMatch(
        val account: String?,        // 选中的账户（null = 没匹配上）
        val candidates: List<String> // 同级命中的全部候选（size>1 = 有歧义）
    )

    private fun matchAccount(data: JSONObject, hint: String): AccMatch {
        val h = hint.trim()
        if (h.isEmpty()) return AccMatch(null, emptyList())
        val names = accountNames(data)
        if (names.isEmpty()) return AccMatch(null, emptyList())

        // 1) 精确
        val exact = names.filter { it == h }
        if (exact.isNotEmpty()) return AccMatch(exact.first(), exact)
        // 2) 互相包含
        pickShortest(names.filter { it.contains(h) || h.contains(it) })?.let { return it }

        // 3) 前缀扫描 + 4) 子序列
        for (len in h.length downTo 2) {
            val p = h.substring(0, len)
            val ex = names.filter { it == p }
            if (ex.isNotEmpty()) return AccMatch(ex.first(), ex)
            pickShortest(names.filter { it.contains(p) || p.contains(it) })?.let { return it }
            pickShortest(names.filter { isSubsequence(p, it) })?.let { return it }
        }
        return AccMatch(null, emptyList())
    }

    /** 在候选里挑「名字最短」的（= 最具体）；同长度的全部保留 → 作为歧义候选 */
    private fun pickShortest(list: List<String>): AccMatch? {
        if (list.isEmpty()) return null
        val minLen = list.minOf { it.length }
        val cands = list.filter { it.length == minLen }
        return AccMatch(cands.first(), cands)
    }

    /** sub 的字符是否按顺序出现在 full 中（缩写判定） */
    private fun isSubsequence(sub: String, full: String): Boolean {
        var i = 0
        for (c in full) {
            if (i < sub.length && sub[i] == c) i++
        }
        return i == sub.length
    }

    private fun accountNames(data: JSONObject): List<String> {
        val out = mutableListOf<String>()
        for (key in listOf("assets", "liabs")) {
            val arr = data.optJSONArray(key) ?: continue
            for (i in 0 until arr.length()) {
                val n = arr.getJSONObject(i).optString("name")
                if (n.isNotBlank()) out.add(n)
            }
        }
        return out
    }

    private fun defaultAccount(data: JSONObject): String? {
        val fd = data.optJSONObject("flowDefault")
        val def = fd?.optString("account")?.takeIf { it.isNotBlank() }
        if (def != null) return def
        val assets = data.getJSONArray("assets")
        if (assets.length() > 0) return assets.getJSONObject(0).optString("name")
        val liabs = data.getJSONArray("liabs")
        if (liabs.length() > 0) return liabs.getJSONObject(0).optString("name")
        return null
    }

    private fun listAccountNames(data: JSONObject): String =
        accountNames(data).joinToString("、").ifBlank { "（暂无，请先在记账页添加）" }

    /** 在 assets/liabs 里按账户名精确取账户对象（applyFlow 用） */
    private fun findAccount(data: JSONObject, arrKey: String, name: String): JSONObject? {
        if (name.isBlank()) return null
        val arr = data.optJSONArray(arrKey) ?: return null
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.optString("name") == name) return o
        }
        return null
    }

    /** 复刻 index.html applyFlow(f)：同步调整 assets[].cur / liabs[].amt */
    private fun applyFlow(data: JSONObject, f: JSONObject) {
        val amt = f.optDouble("amount", 0.0)
        val account = f.optString("account")
        val type = f.optString("type")
        val a = findAccount(data, "assets", account)
        val la = findAccount(data, "liabs", account)
        when (type) {
            "支出" -> {
                if (a != null) a.put("cur", (a.optDouble("cur") - amt).toString())
                else if (la != null) la.put("amt", (la.optDouble("amt") + amt).toString())
            }
            "收入" -> {
                if (a != null) a.put("cur", (a.optDouble("cur") + amt).toString())
                else if (la != null) la.put("amt", (la.optDouble("amt") - amt).toString())
            }
            "转账" -> {
                val to = f.optString("to")
                if (a != null) a.put("cur", (a.optDouble("cur") - amt).toString())
                else if (la != null) la.put("amt", (la.optDouble("amt") + amt).toString())
                val b = findAccount(data, "assets", to)
                val lb = findAccount(data, "liabs", to)
                if (b != null) b.put("cur", (b.optDouble("cur") + amt).toString())
                else if (lb != null) lb.put("amt", (lb.optDouble("amt") - amt).toString())
            }
        }
    }

    private fun buildMessage(f: JSONObject): String {
        val type = f.optString("type")
        val account = f.optString("account")
        val amt = f.optString("amount")
        val note = f.optString("note").let { if (it.isNotBlank()) "（$it）" else "" }
        val who = f.optString("who").let { if (it.isNotBlank()) " 给$it" else "" }
        val to = if (type == "转账") "→${f.optString("to")}" else ""
        return "已记一笔：$type ¥$amt $account$to$note$who"
    }

    private fun today(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date())
}
