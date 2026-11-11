package com.linghit.gdmusic

import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 设置页「VIP 链接解析」区块（阶段 1）。
 *
 * 只负责配置的读写与展示：总开关、失败回退、解析接口增删改 / 批量导入 / 官网识别测试、
 * 直连搜索模板、脚本订阅（自动同步上游 @include / 解析源 / 直连模板）。
 * 解析接口可由脚本订阅自动同步，也可手动填写：影视仓订阅里的 parses 属于采集站接口，
 * 对腾讯/爱奇艺等官网播放页无效，因此不提供「一键导入影视仓接口」。
 * 路由与播放由 VipLinkDispatcher + VideoActivity + video.html 的 __resolveVipAt 负责，
 * 这里不碰任何跳转逻辑。
 *
 * 单独成文件是为了让 SettingsActivity 只多一行调用，保持它已有的 setupXxx 结构。
 */
fun AppCompatActivity.setupVipParseSection() {
    val toggle = findViewById<MaterialButton>(R.id.vipParseToggle)
    val fallback = findViewById<MaterialButton>(R.id.vipFallbackToggle)
    val manage = findViewById<MaterialButton>(R.id.vipSourceBtn)
    val directBtn = findViewById<MaterialButton>(R.id.vipDirectBtn)
    val scriptBtn = findViewById<MaterialButton>(R.id.vipScriptBtn)
    val status = findViewById<TextView>(R.id.vipParseStatus)

    fun refresh() {
        val on = VipParseStore.isEnabled(this)
        // buildString 的 lambda 里 this 是 StringBuilder，先在此处算好再引用
        val directOn = VipParseStore.directConfigured(this)
        val scriptVer = VipScriptUpdater.version(this)
        toggle.text = if (on) "关闭 VIP 链接解析" else "开启 VIP 链接解析"
        val fb = VipParseStore.fallbackOnFail(this)
        fallback.text = if (fb) "失败回退到原网页：开" else "失败回退到原网页：关"
        val src = VipParseStore.sources(this)
        manage.text = if (src.isEmpty()) "管理解析接口…（当前 0 条）"
            else "管理解析接口…（当前 ${src.size} 条）"
        directBtn.text = if (directOn) "直连搜索设置…（已启用，优先）"
            else "直连搜索设置…（未启用）"
        scriptBtn.text = if (scriptVer.isBlank()) "脚本订阅…（未同步，用内置规则）"
            else "脚本订阅…（v$scriptVer 已同步）"
        val sel = VipParseStore.selectedSource(this)
        status.text = buildString {
            append("当前：").append(if (on) "已开启（点官网链接自动解析）" else "未开启")
            append("\n官网识别：").append(VipLinkRouter.ruleCount)
                .append(" 条规则 / ").append(VipLinkRouter.platforms.size).append(" 个平台")
                .append(if (VipLinkRouter.subscribedRuleCount > 0) "（脚本订阅）" else "（内置兜底）")
            append("\n直连搜索：").append(if (directOn) "已启用（片名→联想→播放列表）" else "未配置")
            append("\n默认接口：").append(sel?.name ?: "未设置（按顺序尝试前 8 条）")
            append("\n配置由「脚本订阅」从上游自动同步；你手填的接口与模板不会被覆盖。")
            append("\n直连失败会回落到解析接口。影视仓的采集接口对官网链接无效。")
            append("\n仅用于你有权观看的内容。")
        }
    }

    toggle.setOnClickListener {
        val cur = VipParseStore.isEnabled(this)
        VipParseStore.setEnabled(this, !cur)
        Toast.makeText(
            this,
            if (cur) "已关闭 VIP 链接解析" else "已开启：点官网链接会先解析再进原生播放器",
            Toast.LENGTH_SHORT
        ).show()
        refresh()
    }

    fallback.setOnClickListener {
        VipParseStore.setFallbackOnFail(this, !VipParseStore.fallbackOnFail(this))
        refresh()
    }

    manage.setOnClickListener { showVipSourcesDialog { refresh() } }
    directBtn.setOnClickListener { showVipDirectDialog { refresh() } }
    scriptBtn.setOnClickListener { showVipScriptDialog { refresh() } }

    refresh()
}

/** 订阅状态文本。注意别在 buildString 里传 Context（lambda 的 this 是 StringBuilder） */
private fun AppCompatActivity.vipScriptStatus(): String {
    val v = VipScriptUpdater.version(this)
    val n = VipScriptUpdater.sourceCount(this)
    val total = VipParseStore.sources(this).size
    val rules = VipLinkRouter.ruleCount
    val fromScript = VipLinkRouter.subscribedRuleCount
    val ok = VipScriptUpdater.lastOk(this)
    val err = VipScriptUpdater.lastError(this)
    val direct = VipParseStore.directConfigured(this)
    val from = VipScriptUpdater.fromUrl(this)
    val time = if (ok > 0) SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ok)) else ""
    return buildString {
        append("脚本版本：").append(if (v.isBlank()) "未同步（用内置 35 条兜底规则）" else "v$v")
        append("\n官网识别：").append(rules).append(" 条")
            .append(if (fromScript > 0) "（来自订阅）" else "（内置兜底）")
        append("\n解析接口：订阅 ").append(n).append(" 条 / 当前共 ").append(total).append(" 条")
        append("\n直连模板：").append(if (direct) "已就绪" else "未就绪")
        if (time.isNotEmpty()) append("\n上次成功：").append(time)
        if (from.isNotBlank()) append("\n来源：").append(from)
        if (err.isNotBlank()) append("\n上次失败：").append(err)
    }
}

/** 脚本订阅对话框：状态 / 自动检查 / 自定义地址 / 手动同步 / 离线粘贴导入 / 清除 */
private fun AppCompatActivity.showVipScriptDialog(onDone: () -> Unit) {
    val act = this
    val pad = (16 * resources.displayMetrics.density).toInt()

    fun tv(text: String, size: Float = 13f) = TextView(act).apply {
        this.text = text
        textSize = size
        setPadding(0, 8, 0, 8)
    }

    val root = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(pad, pad / 2, pad, pad / 2)
    }

    root.addView(
        tv(
            "订阅 88lin/video_vip 的 user.js：运行时读取它的 @include 规则、解析接口与无损云 suggest/detail 模板，" +
                "自动填好官网识别和直连搜索。上游改版时按版本号刷新，配置跟着一起更新。\n" +
                "脚本只当文本读，不执行它的代码；解析域名只存本机，不写进仓库。", 12f
        )
    )

    val statusBox = tv(vipScriptStatus(), 12f)
    root.addView(statusBox)
    fun reload() {
        statusBox.text = vipScriptStatus()
        onDone()
    }

    val autoCb = CheckBox(act).apply {
        text = "冷启动自动检查更新（24 小时一次）"
        isChecked = VipScriptUpdater.autoEnabled(act)
    }
    root.addView(autoCb)

    root.addView(tv("自定义订阅地址（留空＝自动试 GitHub raw 与脚本 @updateURL 镜像）", 12f))
    val urlEt = EditText(act).apply {
        hint = "https://…/video_vip.user.js"
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        maxLines = 2
        setText(VipScriptUpdater.customUrl(act))
    }
    root.addView(urlEt)

    val result = tv("", 12f)
    root.addView(Button(act).apply {
        text = "立即检查更新"
        setOnClickListener {
            val btn = this
            VipScriptUpdater.setAutoEnabled(act, autoCb.isChecked)
            VipScriptUpdater.setCustomUrl(act, urlEt.text.toString())
            btn.isEnabled = false
            btn.text = "同步中…"
            result.text = "正在拉取脚本…"
            VipScriptUpdater.syncNow(act) { r ->
                btn.isEnabled = true
                btn.text = "立即检查更新"
                result.text = (if (r.ok) "✓ " else "✗ ") + r.message
                Log.i("VipScript", "manual sync ok=${r.ok} msg=${r.message}")
                reload()
            }
        }
    })
    root.addView(Button(act).apply {
        text = "仅保存开关与地址"
        setOnClickListener {
            VipScriptUpdater.setAutoEnabled(act, autoCb.isChecked)
            VipScriptUpdater.setCustomUrl(act, urlEt.text.toString())
            Toast.makeText(act, "已保存", Toast.LENGTH_SHORT).show()
        }
    })
    root.addView(result)

    root.addView(tv("离线导入：把 user.js 全文粘进来，走同一套解析（无网络时可用）", 12f))
    val pasteEt = EditText(act).apply {
        hint = "// ==UserScript== …"
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        maxLines = 5
    }
    root.addView(pasteEt)
    root.addView(Button(act).apply {
        text = "导入粘贴的脚本"
        setOnClickListener {
            val r = VipScriptUpdater.applyText(act, pasteEt.text.toString(), "手动粘贴")
            result.text = (if (r.ok) "✓ " else "✗ ") + r.message
            Log.i("VipScript", "paste import ok=${r.ok} msg=${r.message}")
            reload()
        }
    })

    root.addView(Button(act).apply {
        text = "清除订阅数据（恢复内置规则）"
        setOnClickListener {
            AlertDialog.Builder(act)
                .setTitle("清除订阅数据？")
                .setMessage("会移除脚本同步来的接口与识别规则；你手填的接口和直连模板保留。")
                .setPositiveButton("清除") { _, _ ->
                    VipScriptUpdater.clear(act)
                    result.text = "已恢复内置规则"
                    reload()
                }
                .setNegativeButton("取消", null)
                .show()
        }
    })

    root.addView(
        tv(
            "脚本里「无损云解析」那条没有 ?url= 前缀，走的是 suggest/detail 接口，" +
                "订阅后会写进「直连搜索设置」两条模板；其余 7 条会写进「管理解析接口」。\n" +
                "你手填过的接口与模板不会被订阅覆盖。", 11f
        )
    )

    val scroll = ScrollView(act).apply { addView(root) }
    AlertDialog.Builder(act)
        .setTitle("脚本订阅")
        .setView(scroll)
        .setPositiveButton("完成") { _, _ -> onDone() }
        .setNegativeButton("取消", null)
        .show()
}

/**
 * 二期直连搜索：片名联想接口 + 详情接口，两条模板可手填，也可由脚本订阅自动写入。
 *
 * 只写占位符说明，不预置任何第三方站点地址——这个仓库是 public，
 * 一旦把接口写进代码就会永久留在 git 历史里。
 */
private fun AppCompatActivity.showVipDirectDialog(onDone: () -> Unit) {
    val act = this
    val pad = (16 * resources.displayMetrics.density).toInt()

    fun tv(text: String, size: Float = 13f) = TextView(act).apply {
        this.text = text
        textSize = size
        setPadding(0, 8, 0, 8)
    }

    val root = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(pad, pad / 2, pad, pad / 2)
    }

    root.addView(
        tv(
            "开启后，点官网播放页会先按片名搜索片源、取回真实播放列表，交给原生播放器；" +
                "不成功才回落到「管理解析接口」里的页面型接口。\n" +
                "片名优先取快捷位名称，其次读官网网页标题，识别不出来时解析面板会让你手填。", 12f
        )
    )

    val enableCb = CheckBox(act).apply {
        text = "启用直连搜索（优先于解析接口）"
        isChecked = VipParseStore.directEnabled(act)
    }
    root.addView(enableCb)

    root.addView(tv("联想搜索模板（返回 {\"list\":[{\"id\",\"name\"}]}）", 13f))
    val suggestEt = EditText(act).apply {
        hint = "https://你的站点/...?wd={title}"
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        maxLines = 3
        setText(VipParseStore.directSuggest(act))
    }
    root.addView(suggestEt)

    root.addView(tv("详情模板（返回 {\"list\":[{\"vod_play_url\":...}]}）", 13f))
    val detailEt = EditText(act).apply {
        hint = "https://你的接口/...?ac=detail&ids={ids}"
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        maxLines = 3
        setText(VipParseStore.directDetail(act))
    }
    root.addView(detailEt)

    root.addView(
        tv(
            "{title} 会自动做 URL 编码；{ids} 是候选影片 id 的逗号分隔串。\n" +
                "播放列表按「三个美元符号分线路 → # 分集 → 单个美元符号分名称与地址」解析。\n" +
                "接口地址不随包分发，也不会提交到仓库；仅用于你有权观看的内容。", 11f
        )
    )

    val result = tv("", 12f)
    root.addView(result)
    root.addView(Button(act).apply {
        text = "保存"
        setOnClickListener {
            val s = suggestEt.text.toString().trim()
            val d = detailEt.text.toString().trim()
            val on = enableCb.isChecked
            if (on) {
                val e1 = VipParseStore.validateTemplate(s, "{title}")
                val e2 = VipParseStore.validateTemplate(d, "{ids}")
                if (e1 != null || e2 != null) {
                    result.text = "未保存：${e1 ?: e2}"
                    return@setOnClickListener
                }
            }
            VipParseStore.setDirectConfig(act, on, s, d)
            result.text = if (VipParseStore.directConfigured(act)) "已保存，直连搜索已启用"
                else "已保存，直连搜索未启用"
            Toast.makeText(act, result.text, Toast.LENGTH_SHORT).show()
            onDone()
        }
    })

    val scroll = ScrollView(act).apply { addView(root) }
    AlertDialog.Builder(act)
        .setTitle("直连搜索设置")
        .setView(scroll)
        .setPositiveButton("完成") { _, _ -> onDone() }
        .setNegativeButton("取消", null)
        .show()
}

private fun AppCompatActivity.showVipSourcesDialog(onDone: () -> Unit) {
    val act = this
    val pad = (16 * resources.displayMetrics.density).toInt()

    fun tv(text: String, size: Float = 13f) = TextView(act).apply {
        this.text = text
        textSize = size
        setPadding(0, 8, 0, 8)
    }

    val root = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(pad, pad / 2, pad, pad / 2)
    }

    root.addView(
        tv("格式：名称 | 接口前缀 | 1 或 0\n1 = 原链接做 URL 编码（默认），0 = 原样拼接；前缀必须以 ?url= 或 &url= 结尾。", 12f)
    )

    // ---- 官网识别测试：验收时可用控件树直接读到结果 ----
    root.addView(tv("官网识别测试", 13f))
    val testEt = EditText(act).apply {
        hint = "粘贴一条官网播放页链接"
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        maxLines = 2
    }
    root.addView(testEt)
    val testResult = tv("", 12f)
    val testBtn = Button(act).apply { text = "识别测试" }
    testBtn.setOnClickListener {
        val u = testEt.text.toString().trim()
        val p = if (u.isEmpty()) null else VipLinkRouter.platformOf(u)
        testResult.text = when {
            u.isEmpty() -> "先填一条链接"
            p == null -> "未命中：会按普通网页打开"
            else -> "命中：$p · 开关打开时会走解析链"
        }
        Log.i("VipParse", "probe url=$u platform=$p")
    }
    root.addView(testBtn)
    root.addView(testResult)

    // ---- 当前接口列表 ----
    root.addView(tv("当前接口（★ = 首选）", 13f))
    val listBox = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
    root.addView(listBox)

    fun renderList() {
        listBox.removeAllViews()
        val list = VipParseStore.sources(act)
        val sel = VipParseStore.selectedId(act)
        if (list.isEmpty()) {
            listBox.addView(tv("　（空）还没添加任何接口", 12f))
            return
        }
        list.forEach { s ->
            val row = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(TextView(act).apply {
                text = "${if (s.id == sel) "★ " else "　"}${s.name} · ${if (s.encoded) "编码" else "裸拼"}\n　${s.prefix}"
                textSize = 12f
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(Button(act).apply {
                text = "默认"
                setOnClickListener {
                    VipParseStore.setSelectedId(act, s.id)
                    renderList()
                }
            })
            row.addView(Button(act).apply {
                text = "删除"
                setOnClickListener {
                    VipParseStore.remove(act, s.id)
                    renderList()
                    onDone()
                }
            })
            listBox.addView(row)
        }
    }
    renderList()

    // 影视仓订阅里的 parses 是「采集站解析」接口，喂官网播放页只会超时；
    // 不再提供一键导入，避免把两类完全不同的接口混在一起。
    root.addView(
        tv(
            "格式：https://解析站/?url=（官网链接自动拼在后面）\n" +
                "影视仓订阅里的采集解析接口不适用于官网播放页，请勿填入。", 11f
        )
    )

    // ---- 新增一条 ----
    root.addView(tv("新增一条", 13f))
    val nameEt = EditText(act).apply {
        hint = "名称（如 线路1）"
        maxLines = 1
    }
    val prefixEt = EditText(act).apply {
        hint = "接口前缀 https://…/?url="
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        maxLines = 2
    }
    val encCb = CheckBox(act).apply {
        text = "对原链接做 URL 编码（关掉＝原样拼接）"
        isChecked = true
    }
    root.addView(nameEt)
    root.addView(prefixEt)
    root.addView(encCb)
    root.addView(Button(act).apply {
        text = "添加"
        setOnClickListener {
            val prefix = prefixEt.text.toString().trim()
            val err = VipParseStore.validatePrefix(prefix)
            if (err != null) {
                Toast.makeText(act, err, Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            val name = nameEt.text.toString().trim().ifBlank { "线路" }
            val ok = VipParseStore.add(act, name, prefix, encCb.isChecked)
            Toast.makeText(act, if (ok) "已添加：$name" else "已存在相同前缀，未重复添加", Toast.LENGTH_SHORT).show()
            nameEt.setText("")
            prefixEt.setText("")
            renderList()
            onDone()
        }
    })

    // ---- 批量导入 ----
    root.addView(tv("批量导入（每行一条，追加）", 13f))
    val batchEt = EditText(act).apply {
        hint = "线路1|https://…/?url=|1\n线路2|https://…/?url=|0"
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        maxLines = 6
    }
    root.addView(batchEt)
    root.addView(Button(act).apply {
        text = "按行导入"
        setOnClickListener {
            val n = VipParseStore.importLines(act, batchEt.text.toString())
            Toast.makeText(act, "导入 $n 条", Toast.LENGTH_SHORT).show()
            renderList()
            onDone()
        }
    })

    root.addView(Button(act).apply {
        text = "清空全部接口"
        setOnClickListener {
            AlertDialog.Builder(act)
                .setTitle("清空全部？")
                .setMessage("会删除所有已填的解析接口，不可撤销。")
                .setPositiveButton("清空") { _, _ ->
                    VipParseStore.replaceAll(act, emptyList())
                    VipParseStore.setSelectedId(act, "")
                    renderList()
                    onDone()
                }
                .setNegativeButton("取消", null)
                .show()
        }
    })

    val scroll = ScrollView(act).apply { addView(root) }
    AlertDialog.Builder(act)
        .setTitle("解析接口管理")
        .setView(scroll)
        .setPositiveButton("完成") { _, _ -> onDone() }
        .setNegativeButton("取消", null)
        .show()
}
