package com.linghit.gdmusic

import android.os.Bundle
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/**
 * 悬浮按钮配置页：透明 Activity，只弹一个选择框。
 * 从悬浮球长按进入，选完即换（服务收到刷新后只改图标，不重建窗口）。
 */
class FloatConfigActivity : AppCompatActivity() {

    /** 相册选图（GetContent 全版本可用，不需要任何运行时权限；返回的 Uri 只用一次） */
    private val pickImage = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent()
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
        finish()
    }

    override fun onCreate(b: Bundle?) {
        ThemePrefs.applyTheme(this)   // 配置框也用当前主题色
        super.onCreate(b)
        showActionDialog()
    }

    /** 动作目录：与首页「＋」面板一致；当前是自定义链接时，顶部多一项「保持当前」 */
    private fun catalog(): List<HomeShortcutStore.Item> = listOf(
        HomeShortcutStore.Item("page", "add", "记一笔", "✏️"),
        HomeShortcutStore.Item("page", "flow", "收支查询", "📊"),
        HomeShortcutStore.Item("page", "journal", "日志", "📖"),
        HomeShortcutStore.Item("page", "links", "链接收藏", "🔗"),
        HomeShortcutStore.Item("page", "video", "影视仓", "🎬"),
        HomeShortcutStore.Item("page", "netasset", "记账本", "📒"),
        HomeShortcutStore.Item("page", "downloads", "下载管理", "⬇️"),
        HomeShortcutStore.Item("page", "favorites", "我的收藏", "❤️"),
        HomeShortcutStore.Item("page", "settings", "设置", "⚙️")
    )

    private fun showActionDialog() {
        val cur = FloatButtonStore.action(this)
        val items = catalog().toMutableList()
        // 当前动作是自定义链接：加一项「保持当前」置顶，选中等于不改
        if (cur.type == "link" && cur.url.isNotBlank()) {
            items.add(0, HomeShortcutStore.Item("link", "", "自定义链接：${cur.label}", "🌐", cur.url))
        }
        // 图标项：换相册图片常驻；已用自定义图时才有「恢复默认」
        items.add(HomeShortcutStore.Item("icon", "pick", "从相册选图片…", "🖼️"))
        if (FloatButtonStore.iconPath(this).isNotBlank()) {
            items.add(HomeShortcutStore.Item("icon", "default", "恢复默认图标", "🎯"))
        }
        val labels = items.map { "${it.emoji} ${it.label}" }.toTypedArray()
        val checked = items.indexOfFirst {
            it.type == cur.type && it.target == cur.target &&
                (it.type != "link" || it.url == cur.url)
        }
        AlertDialog.Builder(this)
            .setTitle("悬浮按钮动作")
            .setSingleChoiceItems(labels, checked) { dlg, i ->
                val a = items[i]
                when (a.target) {
                    // 选图不关对话框：相册返回后直接finish
                    "pick" -> { pickImage.launch("image/*"); return@setSingleChoiceItems }
                    "default" -> {
                        FloatIcon.clear(this)
                        FloatButtonStore.setIconPath(this, "")
                        FloatButtonService.refresh(this)
                        Toast.makeText(this, "已恢复默认图标", Toast.LENGTH_SHORT).show()
                        dlg.dismiss(); finish()
                    }
                    else -> {
                        FloatButtonStore.setAction(this, a)
                        FloatButtonService.refresh(this)
                        Toast.makeText(this, "已设为：${a.label}", Toast.LENGTH_SHORT).show()
                        dlg.dismiss(); finish()
                    }
                }
            }
            .setNeutralButton("自定义链接…") { _, _ -> showLinkDialog() }
            .setNegativeButton("隐藏悬浮按钮") { _, _ ->
                FloatButtonStore.setEnabled(this, false)
                FloatButtonService.stop(this)
                Toast.makeText(this, "已隐藏，可在设置里重新开启", Toast.LENGTH_SHORT).show()
                finish()
            }
            .setPositiveButton("取消", null)
            .setOnCancelListener { finish() }
            .show()
    }

    /** 自定义链接：名称 + 网址，走与记账页链接卡片同一套 normalize */
    private fun showLinkDialog() {
        val cur = FloatButtonStore.action(this)
        val nameInput = EditText(this).apply {
            setText(if (cur.type == "link") cur.label else "")
            hint = "名称，如：摸鱼"
            setSingleLine()
        }
        val urlInput = EditText(this).apply {
            setText(if (cur.type == "link") cur.url else "")
            hint = "网址，如 https://…"
            setSingleLine()
        }
        val box = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
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
                FloatButtonStore.setAction(
                    this,
                    HomeShortcutStore.Item("link", "", label, "🌐", url)
                )
                FloatButtonService.refresh(this)
                Toast.makeText(this, "已设为：$label", Toast.LENGTH_SHORT).show()
                finish()
            }
            .setNegativeButton("返回") { _, _ -> showActionDialog() }
            .setOnCancelListener { showActionDialog() }
            .show()
    }
}
