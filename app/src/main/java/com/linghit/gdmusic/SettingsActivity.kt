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
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.button.MaterialButton

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
            statusText.text = "$perm\n当前动作：${a.emoji} ${a.label}$linkPart"
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
