package com.linghit.gdmusic

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/**
 * 悬浮按钮配置页：透明 Activity，弹一个三区配置框。
 * 从设置页「配置悬浮按钮」进入（长按小球不再弹配置）：
 *  - 点击动作：点一下悬浮球执行什么
 *  - 长按气泡：放射展开的快捷入口，可增 / 删 / 改（上限 [FloatButtonStore.MAX_BUBBLES]）
 *  - 图标：相册图片 / 恢复默认
 * 改完即换（服务收到刷新后只改内容，不重建窗口）。
 */
class FloatConfigActivity : AppCompatActivity() {

    private var mainDlg: AlertDialog? = null

    /** 相册选图（GetContent 全版本可用，不需要任何运行时权限；返回的 Uri 只用一次） */
    private val pickImage = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        val path = FloatIcon.saveFromUri(this, uri)
        if (path == null) {
            Toast.makeText(this, "这张图读不出来，换一张试试", Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }
        FloatButtonStore.setIconPath(this, path)
        FloatButtonService.refresh(this)
        Toast.makeText(this, "图标已换成相册图片", Toast.LENGTH_SHORT).show()
        rebuild()
    }

    override fun onCreate(b: Bundle?) {
        ThemePrefs.applyTheme(this)   // 配置框也用当前主题色
        super.onCreate(b)
        showMainDialog()
    }

    override fun onDestroy() {
        mainDlg?.dismiss()
        super.onDestroy()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /** 改完任何一项：刷新悬浮球 + 重建配置框，立刻看到新状态 */
    private fun rebuild() {
        mainDlg?.dismiss()
        showMainDialog()
    }

    // ==================== 主配置框 ====================
    private fun showMainDialog() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = dp(20)
            setPadding(p, dp(8), p, 0)
        }

        // ---- 点击动作 ----
        val action = FloatButtonStore.action(this)
        box.addView(sectionTitle("点击动作（点一下小球）"))
        box.addView(
            targetRow(action, onChange = {
                pickTarget(action) { a ->
                    FloatButtonStore.setAction(this, a)
                    FloatButtonService.refresh(this)
                    Toast.makeText(this, "点击动作已设为：${a.label}", Toast.LENGTH_SHORT).show()
                    rebuild()
                }
            }, onDelete = null)
        )

        // ---- 长按气泡 ----
        val bubbles = FloatButtonStore.bubbles(this)
        box.addView(sectionTitle("长按气泡（${bubbles.size}/${FloatButtonStore.MAX_BUBBLES}）"))
        bubbles.forEachIndexed { i, b ->
            box.addView(
                targetRow(b, onChange = {
                    pickTarget(b) { nb ->
                        val l = FloatButtonStore.bubbles(this)
                        if (i < l.size) l[i] = nb
                        FloatButtonStore.setBubbles(this, l)
                        FloatButtonService.refresh(this)
                        rebuild()
                    }
                }, onDelete = {
                    val l = FloatButtonStore.bubbles(this)
                    if (i < l.size) l.removeAt(i)
                    FloatButtonStore.setBubbles(this, l)
                    FloatButtonService.refresh(this)
                    Toast.makeText(this, "已删除气泡：${b.label}", Toast.LENGTH_SHORT).show()
                    rebuild()
                })
            )
        }
        if (bubbles.size < FloatButtonStore.MAX_BUBBLES) {
            box.addView(
                smallBtn("＋ 添加气泡") {
                    pickTarget(null) { nb ->
                        val l = FloatButtonStore.bubbles(this)
                        if (l.any { it.id() == nb.id() }) {
                            Toast.makeText(this, "这个入口已经在气泡里了", Toast.LENGTH_SHORT).show()
                            return@pickTarget
                        }
                        l.add(nb)
                        FloatButtonStore.setBubbles(this, l)
                        FloatButtonService.refresh(this)
                        rebuild()
                    }
                }.apply { layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(4) } }
            )
        } else {
            box.addView(TextView(this).apply {
                text = "气泡已满（最多 ${FloatButtonStore.MAX_BUBBLES} 个），删掉一个才能加"
                textSize = 12f
                setTextColor(0x99000000.toInt())
                setPadding(0, dp(6), 0, 0)
            })
        }

        // ---- 图标 ----
        box.addView(sectionTitle("图标"))
        val iconRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        iconRow.addView(smallBtn("从相册选图片…") { pickImage.launch("image/*") })
        if (FloatButtonStore.iconPath(this).isNotBlank()) {
            iconRow.addView(
                smallBtn("恢复默认") {
                    FloatIcon.clear(this)
                    FloatButtonStore.setIconPath(this, "")
                    FloatButtonService.refresh(this)
                    Toast.makeText(this, "已恢复默认图标", Toast.LENGTH_SHORT).show()
                    rebuild()
                }.apply { layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { leftMargin = dp(8) } }
            )
        }
        box.addView(iconRow)

        val scroll = ScrollView(this).apply { addView(box) }
        mainDlg = AlertDialog.Builder(this)
            .setTitle("悬浮按钮配置")
            .setView(scroll)
            .setNeutralButton("隐藏悬浮按钮") { _, _ ->
                FloatButtonStore.setEnabled(this, false)
                FloatButtonService.stop(this)
                Toast.makeText(this, "已隐藏，可在设置里重新开启", Toast.LENGTH_SHORT).show()
                finish()
            }
            .setPositiveButton("完成", null)
            .setOnCancelListener { finish() }
            .show()
    }

    // ==================== 通用行 / 控件 ====================
    private fun sectionTitle(text: String) = TextView(this).apply {
        this.text = text
        textSize = 12f
        setTextColor(0x99000000.toInt())
        setPadding(0, dp(12), 0, dp(4))
    }

    /** 深色弹窗背景上的小按钮：深字 + 浅底（AlertDialog 继承浅色主题，白字会看不见） */
    private fun smallBtn(text: String, onClick: () -> Unit) = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(0xDE000000.toInt())
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(8).toFloat()
            setColor(0x14000000)
        }
        val p = dp(12)
        setPadding(p, dp(6), p, dp(6))
        setOnClickListener { onClick() }
    }

    /** 一行跳转目标：emoji + 名称（链接附网址）+「改」+ 可选「✕」 */
    private fun targetRow(
        item: HomeShortcutStore.Item,
        onChange: () -> Unit,
        onDelete: (() -> Unit)?
    ): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(44)
        }
        row.addView(TextView(this).apply {
            val urlPart =
                if (item.type == "link" && item.url.isNotBlank()) "\n${item.url}" else ""
            text = "${item.emoji} ${item.label}$urlPart"
            textSize = 14f
            setTextColor(0xDE000000.toInt())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        row.addView(
            smallBtn("改", onChange),
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { leftMargin = dp(6) }
        )
        if (onDelete != null) {
            row.addView(
                smallBtn("✕", onDelete),
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { leftMargin = dp(6) }
            )
        }
        return row
    }

    // ==================== 目标选择 ====================
    /** 动作目录：与首页「＋」面板一致；改当前项时置顶一项「保持当前」 */
    private fun catalog(): List<HomeShortcutStore.Item> = listOf(
        HomeShortcutStore.Item("page", "add", "记一笔", "✏️"),
        HomeShortcutStore.Item("page", "flow", "收支查询", "📊"),
        HomeShortcutStore.Item("page", "journal", "日志", "📖"),
        HomeShortcutStore.Item("page", "links", "链接收藏", "🔗"),
        HomeShortcutStore.Item("page", "video", "影视仓", "🎬"),
        HomeShortcutStore.Item("page", "voice", "语音助手", "🎤"),
        HomeShortcutStore.Item("page", "home", "首页", "🏠"),
        HomeShortcutStore.Item("page", "netasset", "记账本", "📒"),
        HomeShortcutStore.Item("page", "downloads", "下载管理", "⬇️"),
        HomeShortcutStore.Item("page", "favorites", "我的收藏", "❤️"),
        HomeShortcutStore.Item("page", "settings", "设置", "⚙️"),
        HomeShortcutStore.Item("page", "qna", "问一句", "🤖"),
        HomeShortcutStore.Item("page", "ai", "端侧AI验证", "🧠")
    )

    private fun pickTarget(cur: HomeShortcutStore.Item?, onPicked: (HomeShortcutStore.Item) -> Unit) {
        val items = catalog().toMutableList()
        if (cur != null && cur.type == "link" && cur.url.isNotBlank()) {
            items.add(0, cur)
        }
        val labels = items.map { "${it.emoji} ${it.label}" }.toTypedArray()
        val checked = if (cur == null) -1 else items.indexOfFirst { it.id() == cur.id() }
        AlertDialog.Builder(this)
            .setTitle("选择跳转目标")
            .setSingleChoiceItems(labels, checked) { dlg, i ->
                dlg.dismiss()
                onPicked(items[i])
            }
            .setNeutralButton("自定义链接…") { _, _ -> pickLink(cur) { onPicked(it) } }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 自定义链接：名称 + 网址，走与记账页链接卡片同一套 normalize */
    private fun pickLink(
        cur: HomeShortcutStore.Item?,
        onPicked: (HomeShortcutStore.Item) -> Unit
    ) {
        val nameInput = EditText(this).apply {
            setText(if (cur?.type == "link") cur.label else "")
            hint = "名称，如：摸鱼"
            setSingleLine()
            setTextColor(0xDE000000.toInt())
            setHintTextColor(0x99000000.toInt())
        }
        val urlInput = EditText(this).apply {
            setText(if (cur?.type == "link") cur.url else "")
            hint = "网址，如 https://…"
            setSingleLine()
            setTextColor(0xDE000000.toInt())
            setHintTextColor(0x99000000.toInt())
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = (24 * resources.displayMetrics.density).toInt()
            setPadding(p, p / 2, p, 0)
            addView(nameInput)
            addView(urlInput)
        }
        AlertDialog.Builder(this)
            .setTitle("自定义链接")
            .setView(box)
            .setPositiveButton("保存") { _, _ ->
                val label = nameInput.text.toString().trim().ifBlank { "链接" }
                val raw = urlInput.text.toString().trim()
                if (raw.isBlank()) {
                    Toast.makeText(this, "网址不能为空", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val url = LinkRouter.normalize(raw)
                onPicked(HomeShortcutStore.Item("link", "", label, "🌐", url))
            }
            .setNegativeButton("返回选择列表") { _, _ -> pickTarget(cur) { onPicked(it) } }
            .setOnCancelListener { pickTarget(cur) { onPicked(it) } }
            .show()
    }
}
