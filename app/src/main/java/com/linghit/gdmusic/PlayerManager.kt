package com.linghit.gdmusic

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Environment
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** 播放器 UI 回调：PlayerManager 负责播放逻辑，MainActivity 负责把状态显示到播放条 */
interface PlayerUiListener {
    fun onSongStart(song: Song, isLocal: Boolean, statusText: String)
    fun onCover(bitmap: Bitmap?)
    fun onLyrics(text: String)
    fun onStatus(text: String)
    fun onPlayStateChanged(isPlaying: Boolean)
    fun onModeChanged(mode: Int)
    fun onCacheStateChanged(key: String, state: String)
    /** 队列变化（增删清空）：通知播放列表页刷新 */
    fun onQueueChanged() {}
}

/**
 * 全局播放管理器（单例）。播放器用 Application Context 创建，挂在进程里，
 * 不受首页 Activity 被系统回收影响，从根本解决「息屏后播放停止」。
 * 配合前台服务 + 通知实现息屏续播（通知栏带上一首/暂停/下一首控制）。
 */
object PlayerManager {

    private lateinit var appContext: Context
    private lateinit var client: OkHttpClient
    private lateinit var player: ExoPlayer
    private var wifiLock: WifiManager.WifiLock? = null
    private var svc: PlaybackService? = null

    var uiListener: PlayerUiListener? = null

    /** 是否拥有通知权限：实时查询真实授权状态，不依赖外部赋值（避免"以为有权限"→startForeground 崩进程） */
    val canPostNotification: Boolean
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                appContext, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else true

    /** 前台保活曾失败过（ROM 限制/缺权限）：本次进程内不再重试，避免反复崩溃 */
    private var foregroundFailed = false

    var current: Song? = null
        private set
    val queue = mutableListOf<Song>()
    var queueIndex = 0
        private set
    var playMode = 0 // 0 顺序播完停，1 列表循环，2 随机
    private var currentIsLocal = false
    private var lastStatusText = ""

    private val prefs by lazy { appContext.getSharedPreferences("music", Context.MODE_PRIVATE) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    fun getPlayer(): ExoPlayer = player

    fun init(context: Context) {
        if (::player.isInitialized) return
        appContext = context.applicationContext
        client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
        player = ExoPlayer.Builder(appContext).build()
        player.addListener(playerListener)
    }

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            if (state != Player.STATE_ENDED) return
            if (queue.isEmpty()) return
            val next = computeNext()
            // 必须传拷贝：playInternal 里会 queue.clear() 再 addAll(queueSongs)，
            // 直接传活的 queue 等于 clear 完再 addAll 自己 → 队列被清空（界面显示过期数据、后续点歌越界）
            if (next in queue.indices) playInternal(queue[next], queue.toList(), next)
        }
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            uiListener?.onPlayStateChanged(isPlaying)
            if (isPlaying) acquireWifiLock() else releaseWifiLock()
            updateForeground()
        }
    }

    private fun computeNext(): Int = when (playMode) {
        1 -> (queueIndex + 1) % queue.size
        2 -> { var r = queueIndex; while (queue.size > 1 && r == queueIndex) r = (0 until queue.size).random(); r }
        else -> queueIndex + 1
    }

    // ---------- 接口地址（与设置页共用，每次播放重新读取，改设置立即生效） ----------
    private fun readApi(): String {
        val raw = prefs.getString(SettingsActivity.KEY_API, SettingsActivity.DEFAULT_API)
            ?: SettingsActivity.DEFAULT_API
        return normalizeApi(raw)
    }
    private fun normalizeApi(raw: String): String {
        var s = raw.trim()
        if (s.isBlank()) s = SettingsActivity.DEFAULT_API
        while (s.endsWith("/")) s = s.substring(0, s.length - 1)
        if (!s.endsWith("/api.php", ignoreCase = true)) s += "/api.php"
        return s
    }
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    // ---------- 对外播放控制 ----------
    fun playFromList(list: List<Song>, song: Song) {
        if (list.isEmpty()) return
        val idx = list.indexOfFirst { it.key() == song.key() }.coerceAtLeast(0)
        playInternal(song, list, idx)
    }

    fun playAllResults(list: List<Song>) {
        if (list.isEmpty()) return
        playInternal(list[0], list, 0)
    }

    fun playInternal(song: Song, queueSongs: List<Song>, index: Int) {
        val apiBase = readApi()
        queue.clear(); queue.addAll(queueSongs); queueIndex = index
        current = song
        persistQueue()
        val local = runCatching { DownloadStore.localUri(appContext, song) }.getOrNull()
        if (local != null) {
            currentIsLocal = true
            lastStatusText = "离线播放 · 本地文件"
            // onSongStart 放到 runCatching 外面：哪怕 setMediaItem 失败，UI/播放列表也要先刷新，
            // 否则用户点了歌界面没反应，以为「没加载进播放列表」
            saveHistory(song)
            uiListener?.onSongStart(song, true, lastStatusText)
            loadCover(song); loadLyrics(song)
            ensureForegroundStarted()
            runCatching {
                player.setMediaItem(MediaItem.fromUri(local)); player.prepare(); player.play()
                // 本地文件没有 lyricId（导入的本地音乐）：改读文件内嵌的歌词标签
                if (song.lyricId.isBlank() && local.scheme == "file") {
                    scope.launch(Dispatchers.IO) {
                        val lrc = AudioTagUtil.readLyric(local.path ?: return@launch)
                        if (!lrc.isNullOrBlank()) withContext(Dispatchers.Main) {
                            uiListener?.onLyrics(lrc)
                        }
                    }
                }
            }.onFailure {
                // 明确起播失败：停掉上一首，别让用户以为「还是固定那一首在响」
                runCatching { player.pause() }
                uiListener?.onStatus("播放失败：${it.message}")
            }
            return
        }
        currentIsLocal = false
        lastStatusText = "正在获取播放地址……"
        uiListener?.onSongStart(song, false, lastStatusText)
        scope.launch {
            try {
                val o = withContext(Dispatchers.IO) {
                    JSONObject(get(apiBase, "$apiBase?types=url&source=${enc(song.source)}&id=${enc(song.id)}&br=320"))
                }
                val url = o.optString("url")
                if (url.isBlank() || url == "null") throw Exception("没有可播放地址")
                player.setMediaItem(MediaItem.fromUri(url)); player.prepare(); player.play()
                saveHistory(song)
                lastStatusText = "在线播放 · ${sourceLabel(song.source)}（已后台缓存，下次离线可听）"
                uiListener?.onSongStart(song, false, lastStatusText)
                loadCover(song); loadLyrics(song)
                ensureForegroundStarted()
                cacheDownload(song, apiBase)
            } catch (e: Exception) {
                // 同上：起播失败要停掉正在响的上一首，并把错误暴露出来
                runCatching { player.pause() }
                uiListener?.onStatus("播放失败：${e.message}")
            }
        }
    }

    fun togglePlayPause() = runCatching {
        if (player.isPlaying) player.pause()
        else { if (player.duration > 0 && player.currentPosition >= player.duration) player.seekTo(0); player.play() }
    }
    fun seekTo(ms: Long) = runCatching { player.seekTo(ms) }

    fun skipNext() { val n = computeNext(); if (n in queue.indices) playInternal(queue[n], queue, n) }
    fun skipPrev() {
        if (player.currentPosition > 3000) { player.seekTo(0); return }
        val p = if (queueIndex - 1 >= 0) queueIndex - 1
        else if (playMode == 1 && queue.isNotEmpty()) queue.size - 1 else 0
        if (p in queue.indices) playInternal(queue[p], queue, p)
    }
    fun setMode(mode: Int) { playMode = mode; uiListener?.onModeChanged(mode) }

    /** 从队列删除一首（播放列表页的 ✕）：删当前播放项之前的要前移索引，正在播的删了也不中断当前曲目 */
    fun removeFromQueue(index: Int) {
        if (index < 0 || index >= queue.size) return
        queue.removeAt(index)
        if (index < queueIndex) queueIndex--
        else if (queueIndex >= queue.size) queueIndex = (queue.size - 1).coerceAtLeast(0)
        persistQueue()
        uiListener?.onQueueChanged()
    }

    /** 清空播放列表（当前播放不中断，只是没有后继曲目） */
    fun clearQueue() {
        queue.clear(); queueIndex = 0
        persistQueue()
        uiListener?.onQueueChanged()
    }

    /** 当前队列快照（只读拷贝，供播放列表页渲染） */
    fun queueSnapshot(): List<Song> = queue.toList()

    // ---------- 队列持久化：进程被杀后重进 App 原样恢复列表和「播放中」位置 ----------
    // 队列原先只在内存里，用户从下载页点了播放全部、退出重进就全没了。
    // 存 SharedPreferences（与设置共用 "music"）：列表 JSON + 播到第几首。
    // 只恢复列表和高亮，不自动起播——开 App 就响会很突兀，且后台起播有被系统拦的风险。
    private fun persistQueue() {
        runCatching {
            prefs.edit()
                .putString("play_queue_v1", songsToJsonArray(queue))
                .putInt("play_index_v1", queueIndex)
                .apply()
        }
    }

    /** 内存队列为空时恢复上次的播放列表（有活队列则不碰，避免覆盖当前会话） */
    fun restoreQueueIfEmpty() {
        if (queue.isNotEmpty()) return
        val json = prefs.getString("play_queue_v1", null) ?: return
        val list = runCatching { songsFromJsonArray(json) }.getOrNull() ?: return
        if (list.isEmpty()) return
        val idx = prefs.getInt("play_index_v1", 0).coerceIn(0, list.size - 1)
        queue.clear(); queue.addAll(list); queueIndex = idx
        current = queue[idx]
        // 恢复「是否本地」状态：能让播放条显示对的状态文案（本地/在线）
        currentIsLocal = runCatching { DownloadStore.localUri(appContext, current!!) }.getOrNull() != null
        lastStatusText = if (currentIsLocal) "离线播放 · 本地文件" else "在线播放"
        uiListener?.onQueueChanged()
        uiListener?.onSongStart(current!!, currentIsLocal, lastStatusText)
    }

    /** 首页 Activity 重新创建时，把当前播放状态同步到新的 UI */
    fun onUiAttached() {
        if (!::player.isInitialized) return
        current?.let { uiListener?.onSongStart(it, currentIsLocal, lastStatusText) }
        uiListener?.onPlayStateChanged(player.isPlaying)
        uiListener?.onModeChanged(playMode)
    }

    // ---------- 封面 / 歌词 / 缓存 ----------
    private fun loadCover(s: Song) {
        if (s.picId.isBlank()) { uiListener?.onCover(null); return }
        val apiBase = readApi()
        scope.launch(Dispatchers.IO) {
            runCatching {
                val u = JSONObject(get(apiBase, "$apiBase?types=pic&source=${enc(s.source)}&id=${enc(s.picId)}&size=500")).optString("url")
                if (u.isBlank()) { withContext(Dispatchers.Main) { uiListener?.onCover(null) }; return@runCatching }
                val bytes = client.newCall(Request.Builder().url(u).build()).execute().use { it.body?.bytes() }
                val bmp = bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                withContext(Dispatchers.Main) { uiListener?.onCover(bmp) }
            }.onFailure { withContext(Dispatchers.Main) { uiListener?.onCover(null) } }
        }
    }
    private fun loadLyrics(s: Song) {
        val apiBase = readApi()
        scope.launch {
            runCatching {
                val o = withContext(Dispatchers.IO) { JSONObject(get(apiBase, "$apiBase?types=lyric&source=${enc(s.source)}&id=${enc(s.lyricId)}")) }
                val l = o.optString("lyric")
                withContext(Dispatchers.Main) { uiListener?.onLyrics(if (l.isBlank()) "暂无歌词" else l) }
            }.onFailure { withContext(Dispatchers.Main) { uiListener?.onLyrics("暂无歌词") } }
        }
    }

    private fun get(apiBase: String, u: String): String {
        val r = Request.Builder().url(u).header("User-Agent", "GDMusicPlayer/3.0").build()
        return client.newCall(r).execute().use { if (!it.isSuccessful) throw Exception("HTTP ${it.code}"); it.body?.string() ?: "" }
    }

    private fun cacheDownload(s: Song, apiBase: String) {
        val k = s.key()
        if (DownloadStore.localUri(appContext, s) != null) return
        scope.launch(Dispatchers.IO) {
            try {
                val o = JSONObject(get(apiBase, "$apiBase?types=url&source=${enc(s.source)}&id=${enc(s.id)}&br=320"))
                val url = o.optString("url")
                if (url.isBlank() || url == "null") return@launch
                val resp = client.newCall(Request.Builder().url(url).header("User-Agent", "GDMusicPlayer/3.0").build()).execute()
                if (!resp.isSuccessful) { resp.close(); return@launch }
                val ct = resp.header("Content-Type")
                val ext = extFrom(url, ct)
                val musicDir = appContext.getExternalFilesDir(Environment.DIRECTORY_MUSIC)!!
                val tmp = File(musicDir, baseName(s) + ".tmp")
                val out = File(musicDir, baseName(s) + "." + ext)
                resp.body?.byteStream()?.use { inp -> FileOutputStream(tmp).use { inp.copyTo(it) } }
                resp.close()
                if (tmp.exists() && tmp.length() > 0) tmp.renameTo(out)
                DownloadStore.add(appContext, s, Uri.fromFile(out), "cache", "应用缓存", out.length())
                // 回调会触发 MainActivity 的 adapter.notifyDataSetChanged，必须回主线程，
                // 否则 RecyclerView 在 IO 线程 requestLayout 直接崩
                withContext(Dispatchers.Main) { uiListener?.onCacheStateChanged(k, "done") }
            } catch (e: Exception) { /* 缓存失败不影响播放 */ }
        }
    }
    private fun baseName(s: Song) = s.key().replace(Regex("[^a-zA-Z0-9]"), "_")
    private fun extFrom(url: String, ct: String?): String {
        val p = url.substringBefore('?').substringAfterLast('.', "")
        if (p.length in 2..4) return p.lowercase()
        return when {
            ct?.contains("mpeg") == true || ct?.contains("mp3") == true -> "mp3"
            ct?.contains("mp4") == true || ct?.contains("m4a") == true -> "m4a"
            ct?.contains("flac") == true -> "flac"
            ct?.contains("wav") == true -> "wav"
            ct?.contains("ogg") == true || ct?.contains("opus") == true -> "ogg"
            else -> "mp3"
        }
    }

    private fun saveHistory(s: Song) {
        val old = prefs.getStringSet("history", emptySet())!!.toMutableList()
        old.remove(s.key()); old.add(0, s.key()); if (old.size > 50) old.removeAt(old.lastIndex)
        prefs.edit().putStringSet("history", old.toSet()).apply()
    }

    // ---------- WifiLock：在线播放时防止息屏断 WiFi 导致缓冲中断 ----------
    /** 缺 WAKE_LOCK 权限或个别 ROM 限制时 acquire() 会抛 SecurityException，必须兜住，否则一点播放就崩 */
    private fun acquireWifiLock() {
        runCatching {
            if (wifiLock == null) {
                val wm = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                @Suppress("DEPRECATION")
                wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "GDMusic:wifi")
            }
            if (wifiLock?.isHeld != true) wifiLock?.acquire()
        }.onFailure { wifiLock = null }
    }
    private fun releaseWifiLock() { runCatching { if (wifiLock?.isHeld == true) wifiLock?.release() } }

    // ---------- 前台服务 + 通知（息屏续播 + 通知栏控制） ----------
    private const val NOTIF_ID = 1001
    private const val CHANNEL_ID = "gdmusic_playback"

    fun attachService(s: PlaybackService) { svc = s }
    fun detachService() { svc = null }

    /**
     * 启动保活服务。关键：这里用普通 startService，而不是 startForegroundService。
     * startForegroundService 会给服务加一条「5 秒内必须 startForeground」的契约，
     * 一旦通知权限缺失或被 ROM 拦截，系统就抛 RemoteServiceException 直接崩进程。
     * 普通 startService 没有这条契约：起不来就静默失败，播放照常，绝不会崩。
     */
    fun ensureForegroundStarted() {
        if (foregroundFailed || !canPostNotification) return
        runCatching { createChannel() }
        try {
            appContext.startService(Intent(appContext, PlaybackService::class.java))
        } catch (e: Exception) {
            // 后台启动被限制等：本次进程内放弃前台保活，但不影响播放
            foregroundFailed = true
        }
    }

    fun updateForeground() {
        if (foregroundFailed || !canPostNotification) return
        if (svc == null) { ensureForegroundStarted(); return }
        val s = svc ?: return
        runCatching { createChannel() }
        val n = runCatching { buildNotification(player.isPlaying) }.getOrNull() ?: return
        // 常驻模式：播放与暂停都保持前台通知（暂停时显示暂停态、可点通知恢复），
        // 不再 stopForeground —— 避免服务降为后台后被系统回收导致息屏停播。
        startForegroundSafe(s, n)
    }

    /** 先按 Android 14 要求的「带类型」方式起前台，失败再退回老式调用，都失败就放弃保活（不崩） */
    private fun startForegroundSafe(s: PlaybackService, n: Notification) {
        try {
            ServiceCompat.startForeground(
                s, NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } catch (e: Exception) {
            try {
                @Suppress("DEPRECATION")
                s.startForeground(NOTIF_ID, n)
            } catch (e2: Exception) {
                foregroundFailed = true
                runCatching { s.stopSelf() }
            }
        }
    }

    /** 用户点通知「关闭」：暂停播放并停掉前台服务（撤掉通知），播放器实例保留（点播可再起服务） */
    fun stop() {
        runCatching { player.pause() }
        releaseWifiLock()
        runCatching { svc?.stopForeground(true) }
        runCatching { svc?.stopSelf() }
        svc = null
        uiListener?.onPlayStateChanged(false)
    }

    /**
     * 前台服务刚被 startForegroundService 启动时调用：必须无条件先 startForeground，
     * 否则 Android 8+ 会在 5 秒后抛 RemoteServiceException 直接崩进程。
     * （之前按播放状态判断，而此时 ExoPlayer 还在缓冲、isPlaying=false，
     *   于是走了 stopForeground 分支 → 服务从未 startForeground → 崩溃。）
     */
    fun showForegroundNotification(s: PlaybackService) {
        if (foregroundFailed || !canPostNotification) {
            // 无通知权限/已判定不可用：不能起前台，直接停服务（用 startService 起的，停掉不会触发契约崩溃）
            runCatching { s.stopSelf() }
            return
        }
        runCatching { createChannel() }
        val n = runCatching { buildNotification(player.isPlaying) }.getOrNull()
        if (n == null) { foregroundFailed = true; runCatching { s.stopSelf() }; return }
        startForegroundSafe(s, n)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
                val ch = NotificationChannel(CHANNEL_ID, "播放控制", NotificationManager.IMPORTANCE_LOW)
                ch.setShowBadge(false)
                mgr.createNotificationChannel(ch)
            }
        }
    }

    private fun buildNotification(isPlaying: Boolean): Notification {
        val song = current
        val contentIntent = Intent(appContext, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN; addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val pi = PendingIntent.getActivity(appContext, 0, contentIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val builder = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(song?.name ?: "GD 音乐")
            .setContentText(song?.let { "${it.artist} · ${it.album}" } ?: "未播放")
            .setContentIntent(pi)
            .setOngoing(isPlaying)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        builder.addAction(android.R.drawable.ic_media_previous, "上一首", makePI("PREV"))
        builder.addAction(NotificationCompat.Action(
            if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play, "播放/暂停", makePI("TOGGLE")))
        builder.addAction(android.R.drawable.ic_media_next, "下一首", makePI("NEXT"))
        // 常驻通知无法滑动清除，必须给一个主动关闭入口
        builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "关闭", makePI("STOP"))
        return builder.build()
    }

    private fun makePI(action: String): PendingIntent {
        val intent = Intent(appContext, PlayerBroadcastReceiver::class.java).apply { this.action = action }
        return PendingIntent.getBroadcast(appContext, action.hashCode(), intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    fun releaseAll() {
        runCatching { player.release() }
        releaseWifiLock()
        svc?.stopForeground(true)
        svc?.stopSelf()
        svc = null
        scope.cancel()
    }
}
