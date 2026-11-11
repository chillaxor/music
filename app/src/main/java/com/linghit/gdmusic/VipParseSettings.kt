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

/**
 * 设置页「VIP 链接解析」区块（阶段 1）。
 *
 * 只负责配置的读写与展示：总开关、失败回退、解析接口增删改 / 批量导入 / 官网识别测试。
 * 路由（阶段 2）与播放（阶段 3）尚未接线，所以开关打开也不会改变现有链接行为，
 * 按钮文案里如实写明「阶段 2 接线后生效」。
 *
 * 单独成文件是为了让 SettingsActivity 只多一行调用，保持它已有的 setupXxx 结构。
 */
fun AppCompatActivity.setupVipParseSection() {
    val toggle = findViewById<MaterialButton>(R.id.vipParseToggle)
    val fallback = findViewById<MaterialButton>(R.id.vipFallbackToggle)
    val manage = findViewById<MaterialButton>(R.id.vipSourceBtn)
    val status = findViewById<TextView>(R.id.vipParseStatus)

    fun refresh() {
        val on = VipParseStore.isEnabled(this)
        toggle.text = if (on) "关闭 VIP 链接解析" else "开启 VIP 链接解析"
        val fb = VipParseStore.fallbackOnFail(this)
        fallback.text = if (fb) "失败回退到原网页：开" else "失败回退到原网页：关"
        val src = VipParseStore.sources(this)
        manage.text = if (src.isEmpty()) "管理解析接口…（当前 0 条）"
            else "管理解析接口…（当前 ${src.size} 条）"
        val sel = VipParseStore.selectedSource(this)
        status.text = buildString {
            append("当前：").append(if (on) "已开启（阶段 2 接线后生效）" else "未开启")
            append("\n官网识别：内置 ").append(VipLinkRouter.ruleCount)
                .append(" 条规则 / ").append(VipLinkRouter.platforms.size).append(" 个平台")
            append("\n默认接口：").append(sel?.name ?: "未设置（按顺序尝试）")
            append("\n接口由你自己填写，App 不内置第三方解析站；仅用于你有权观看的内容。")
        }
    }

    toggle.setOnClickListener {
        val cur = VipParseStore.isEnabled(this)
        VipParseStore.setEnabled(this, !cur)
        Toast.makeText(
            this,
            if (cur) "已关闭 VIP 链接解析" else "已开启（阶段 2 接线后生效）",
            Toast.LENGTH_SHORT
        ).show()
        refresh()
    }

    fallback.setOnClickListener {
        VipParseStore.setFallbackOnFail(this, !VipParseStore.fallbackOnFail(this))
        refresh()
    }

    manage.setOnClickListener { showVipSourcesDialog { refresh() } }

    refresh()
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
