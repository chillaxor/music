package com.linghit.gdmusic

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.button.MaterialButton
import androidx.activity.result.contract.ActivityResultContracts

class SettingsActivity : AppCompatActivity() {

    companion object {
        const val PREFS = "music"
        const val KEY_API = "api_base"
        const val DEFAULT_API = "https://music-api.gdstudio.xyz/api.php"
    }

    private val prefs: SharedPreferences by lazy { getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        ThemePrefs.applyTheme(this)
        setContentView(R.layout.activity_settings)

        val input = findViewById<TextInputEditText>(R.id.apiInput)
        // 当前已保存的地址（没有则用默认）
        val saved = prefs.getString(KEY_API, DEFAULT_API) ?: DEFAULT_API
        input.setText(saved)

        findViewById<android.widget.TextView>(R.id.backBtn).setOnClickListener { finish() }

        findViewById<MaterialButton>(R.id.apiSave).setOnClickListener {
            val raw = input.text.toString().trim()
            val value = if (raw.isBlank()) DEFAULT_API else raw
            prefs.edit().putString(KEY_API, value).apply()
            Toast.makeText(this, "已保存，返回首页重新搜索生效", Toast.LENGTH_SHORT).show()
            finish()
        }

        findViewById<MaterialButton>(R.id.apiReset).setOnClickListener {
            prefs.edit().putString(KEY_API, DEFAULT_API).apply()
            input.setText(DEFAULT_API)
            Toast.makeText(this, "已恢复默认接口", Toast.LENGTH_SHORT).show()
        }

        // ---- 崩溃日志：闪退后重开 App 即可查看/复制，无需 adb ----
        findViewById<MaterialButton>(R.id.crashViewBtn).setOnClickListener { showCrashLog() }
        findViewById<MaterialButton>(R.id.crashClearBtn).setOnClickListener {
            CrashHandler.clear(this)
            Toast.makeText(this, "已清除崩溃记录", Toast.LENGTH_SHORT).show()
        }

        // ---- 电池优化白名单：国产 ROM 息屏保活关键一步 ----
        findViewById<MaterialButton>(R.id.batteryBtn).setOnClickListener { requestBatteryWhitelist() }

        // ---- 桌面歌词（悬浮窗） ----
        setupDesktopLyric()

        // ---- 常驻悬浮按钮 ----
        setupFloatButton()

        // ---- 启动背景图 ----
        setupSplashBg()

        // 版本与构建时间：装没装上新包，来这里看一眼就知道（崩溃日志头部也会带同样的信息）
        findViewById<TextView>(R.id.versionInfo).text =
            "GD音乐 版本 ${BuildConfig.VERSION_NAME} · 构建 ${BuildConfig.BUILD_TIME}"
    }

    private fun setupDesktopLyric() {
        val toggle = findViewById<MaterialButton>(R.id.deskLyricToggle)
        val statusText = findViewById<TextView>(R.id.deskLyricStatus)
        val topBar = findViewById<SeekBar>(R.id.deskLyricTop)
        val leftBar = findViewById<SeekBar>(R.id.deskLyricLeft)
        val widthBar = findViewById<SeekBar>(R.id.deskLyricWidth)
        val fontBar = findViewById<SeekBar>(R.id.deskLyricFont)
        val colorBtn = findViewById<MaterialButton>(R.id.deskLyricColor)

        fun refresh() {
            val on = DesktopLyric.enabled
            toggle.text = if (on) "关闭桌面歌词" else "开启桌面歌词"
            val perm = if (DesktopLyric.hasPermission()) "悬浮窗权限：已授予" else "悬浮窗权限：未授予（开启时会引导授权）"
            statusText.text = perm
            colorBtn.text = "配色：${DesktopLyric.colorName()}"
            topBar.progress = (DesktopLyric.topPercent * 100).toInt()
            leftBar.progress = (DesktopLyric.leftPercent * 100).toInt()
            widthBar.progress = (DesktopLyric.widthPercent * 100).toInt()
            fontBar.progress = DesktopLyric.fontSizeSp.toInt()
            findViewById<TextView>(R.id.deskLyricTopLabel).text = "上下位置（${topBar.progress}%）"
            findViewById<TextView>(R.id.deskLyricLeftLabel).text = "左右位置（${leftBar.progress}%）"
            findViewById<TextView>(R.id.deskLyricWidthLabel).text = "歌词条宽度（${widthBar.progress}%）"
            findViewById<TextView>(R.id.deskLyricFontLabel).text = "字号（${fontBar.progress}sp）"
        }

        toggle.setOnClickListener {
            if (DesktopLyric.enabled) {
                DesktopLyric.enabled = false
                Toast.makeText(this, "桌面歌词已关闭", Toast.LENGTH_SHORT).show()
            } else {
                if (!DesktopLyric.hasPermission()) { DesktopLyric.requestPermission(); return@setOnClickListener }
                DesktopLyric.enabled = true
                Toast.makeText(this, "桌面歌词已开启", Toast.LENGTH_SHORT).show()
            }
            refresh()
        }

        topBar.setOnSeekBarChangeListener(simpleSeek { p -> DesktopLyric.topPercent = p / 100f; refresh() })
        leftBar.setOnSeekBarChangeListener(simpleSeek { p -> DesktopLyric.leftPercent = p / 100f; refresh() })
        widthBar.setOnSeekBarChangeListener(simpleSeek { p -> DesktopLyric.widthPercent = (0.3f + p / 100f * 0.7f); refresh() })
        fontBar.setOnSeekBarChangeListener(simpleSeek { p -> DesktopLyric.fontSizeSp = p.coerceAtLeast(10).toFloat(); refresh() })

        colorBtn.setOnClickListener {
            DesktopLyric.colorIndex = (DesktopLyric.colorIndex + 1) % 3
            refresh()
        }

        refresh()
    }

    private fun simpleSeek(onChange: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) { if (fromUser) onChange(p) }
        override fun onStartTrackingTouch(sb: SeekBar?) {}
        override fun onStopTrackingTouch(sb: SeekBar?) {}
    }

    /** 从权限设置页回来时刷新权限状态（授权后用户还要再点一次开关才生效） */
    override fun onResume() {
        super.onResume()
        floatBtnRefresh?.invoke()
        splashRefresh?.invoke()
    }

    private var floatBtnRefresh: (() -> Unit)? = null

    private fun setupFloatButton() {
        val toggle = findViewById<MaterialButton>(R.id.floatBtnToggle)
        val configBtn = findViewById<MaterialButton>(R.id.floatBtnConfig)
        val statusText = findViewById<TextView>(R.id.floatBtnStatus)

        fun refresh() {
            val on = FloatButtonStore.enabled(this)
            toggle.text = if (on) "关闭常驻悬浮按钮" else "开启常驻悬浮按钮"
            val perm = if (DesktopLyric.hasPermission()) "悬浮窗权限：已授予" else "悬浮窗权限：未授予（开启时会引导授权）"
            val a = FloatButtonStore.action(this)
            val linkPart = if (a.type == "link" && a.url.isNotBlank()) "（${a.url}）" else ""
            val iconPart = if (FloatButtonStore.iconPath(this).isNotBlank()) " · 自定义图标" else ""
            statusText.text = "$perm\n当前动作：${a.emoji} ${a.label}$linkPart$iconPart"
        }
        floatBtnRefresh = { runCatching { refresh() } }

        toggle.setOnClickListener {
            if (FloatButtonStore.enabled(this)) {
                FloatButtonStore.setEnabled(this, false)
                FloatButtonService.stop(this)
                Toast.makeText(this, "悬浮按钮已关闭", Toast.LENGTH_SHORT).show()
            } else {
                if (!DesktopLyric.hasPermission()) { DesktopLyric.requestPermission(); return@setOnClickListener }
                FloatButtonStore.setEnabled(this, true)
                FloatButtonService.start(this)
                Toast.makeText(this, "已开启，长按小球可改动作", Toast.LENGTH_SHORT).show()
            }
            refresh()
        }

        configBtn.setOnClickListener {
            startActivity(Intent(this, FloatConfigActivity::class.java))
        }

        refresh()
    }

    /** 启动背景图：相册选图（受限解码落盘）/ 开关 / 恢复默认 */
    private fun setupSplashBg() {
        val toggle = findViewById<MaterialButton>(R.id.splashToggle)
        val pickBtn = findViewById<MaterialButton>(R.id.splashPick)
        val resetBtn = findViewById<MaterialButton>(R.id.splashReset)
        val statusText = findViewById<TextView>(R.id.splashStatus)

        fun refresh() {
            val has = SplashStore.hasCustom(this)
            toggle.text = if (has) "关闭自定义启动背景" else "开启自定义启动背景"
            pickBtn.text = if (has) "换一张启动背景图…" else "从相册选启动背景图…"
            resetBtn.isVisible = SplashStore.path(this) != null
            statusText.text = if (has) {
                "已启用：打开 App 先显示自定义背景图（约 0.6 秒）\n图片按原图保存，未压缩未裁剪（显示时自动适配屏幕）"
            } else {
                "未启用：打开 App 显示默认启动样式（应用图标 + 主题色）"
            }
        }

        toggle.setOnClickListener {
            if (SplashStore.path(this) == null) {
                Toast.makeText(this, "先选一张图片", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val on = !SplashStore.enabled(this)
            SplashStore.setEnabled(this, on)
            Toast.makeText(this, if (on) "已开启，下次启动 App 生效" else "已关闭，下次启动 App 生效", Toast.LENGTH_SHORT).show()
            refresh()
        }

        pickBtn.setOnClickListener { splashPicker.launch("image/*") }
        splashRefresh = { runCatching { refresh() } }

        resetBtn.setOnClickListener {
            SplashStore.clear(this)
            Toast.makeText(this, "已恢复默认启动样式", Toast.LENGTH_SHORT).show()
            refresh()
        }

        refresh()
    }

    private var splashRefresh: (() -> Unit)? = null

    /** 相册选图回调：GetContent 无运行时权限；受限解码后落盘并自动开启 */
    private val splashPicker =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            if (uri == null) return@registerForActivityResult
            // 原图字节级拷贝：不压缩、不裁切、不改尺寸，保留相册原始质量。
            // 显示时由 SplashActivity 按屏幕采样解码，清晰且不会 OOM
            val path = ImagePrep.copyOriginal(this, uri, SplashStore.file(this).name)
            if (path != null) {
                SplashStore.enable(this, path)
                Toast.makeText(this, "已按原图保存，下次启动 App 生效", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "图片保存失败，换一张试试", Toast.LENGTH_SHORT).show()
            }
            splashRefresh?.invoke()
        }

    private fun showCrashLog() {
        val log = CrashHandler.read(this)
        if (log.isBlank()) {
            Toast.makeText(this, "暂无崩溃记录（最近没有闪退）", Toast.LENGTH_SHORT).show()
            return
        }
        val tv = TextView(this).apply {
            text = log
            setTextIsSelectable(true)
            textSize = 11f
            setPadding(32, 24, 32, 24)
        }
        val scroll = ScrollView(this).apply { addView(tv) }
        AlertDialog.Builder(this)
            .setTitle("崩溃日志（可复制发给开发者）")
            .setView(scroll)
            .setPositiveButton("复制全部") { _, _ ->
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("crash_log", log))
                Toast.makeText(this, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun requestBatteryWhitelist() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            Toast.makeText(this, "系统版本较低，无需设置", Toast.LENGTH_SHORT).show()
            return
        }
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            Toast.makeText(this, "已在白名单中，无需再设置", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            startActivity(Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:$packageName")
            ))
            Toast.makeText(this, "请在弹窗中选择「允许」", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            // 部分 ROM 不支持该跳转，退回到电池优化列表页
            runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                .onFailure { Toast.makeText(this, "本机不支持跳转，请手动在「电池→后台管理」中设置", Toast.LENGTH_LONG).show() }
        }
    }
}
