package com.linghit.gdmusic

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Environment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.app.ActivityCompat
import androidx.documentfile.provider.DocumentFile
import androidx.media3.exoplayer.ExoPlayer
import com.google.android.material.button.MaterialButton
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class Song(
    val id:String, val name:String, val artist:String, val album:String,
    val source:String, val picId:String, val lyricId:String,
    /** 本地文件地址（导入/缓存歌专用）：有了它 key 才唯一，否则导入歌 id/source 全空会共用一个 key "|"，
     *  导致 localUri 按 key 查永远命中第一条 → 点哪首都播固定的那一首 */
    val localUri:String=""
) {
    fun key() = if(localUri.isNotBlank()) "local|$localUri" else "$source|$id"
}

/** 音源 id -> 中文名。保留旧音源(tencent/kugou/kuwo/migu)兜底，避免旧收藏/离线记录显示原始 id */
fun sourceLabel(s:String) = when(s){
    "netease"->"网易云"; "joox"->"JOOX"; "bilibili"->"B站"
    "tencent"->"QQ音乐"; "kugou"->"酷狗"; "kuwo"->"酷我"; "migu"->"咪咕"
    "tidal"->"Tidal"; "qobuz"->"Qobuz"; "apple"->"Apple Music"
    "ytmusic"->"YouTube Music"; "spotify"->"Spotify"
    else->s
}

class MainActivity : AppCompatActivity(), PlayerUiListener {
    // 接口地址改为可配置：默认 GD 音乐台，用户可在「设置」里更换（防接口失效无需重装）
    private val api: String
        get() {
            val raw = prefs.getString(SettingsActivity.KEY_API, SettingsActivity.DEFAULT_API)
                ?: SettingsActivity.DEFAULT_API
            return normalizeApi(raw)
        }
    // 实测结论(2026-09-28)：本 API 只有 netease 能端到端播放。
    private val sources=listOf("netease")
    // 每页条数，与 API 的 count 对应。实测：count=50 稳定；count=100 返回 0 条，故取 50
    private val pageSize=50
    private var currentPage=1
    private var isLoadingMore=false
    private var hasMore=true
    private var lastKeyword=""
    private val client=OkHttpClient.Builder().connectTimeout(10,TimeUnit.SECONDS)
        .readTimeout(15,TimeUnit.SECONDS).build()
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)

    private lateinit var input:EditText; private lateinit var status:TextView
    private lateinit var nowPlaying:TextView; private lateinit var nowArtist:TextView
    private lateinit var lyricsText:TextView; private lateinit var cover:ImageView
    // 播放器已从 Activity 成员改为全局单例（PlayerManager），从根本上避免息屏被回收导致停播
    private val player get() = PlayerManager.getPlayer()
    private lateinit var playPauseBtn:ImageButton; private lateinit var seekBar:SeekBar
    private lateinit var curTime:TextView; private lateinit var totalTime:TextView
    private lateinit var modeBtn:ImageButton
    private lateinit var lyricFloatBtn:ImageButton
    private lateinit var playerBar:View
    // 分段切换：播放列表（默认）/ 搜索
    private lateinit var tabPlaylist:TextView
    private lateinit var tabSearch:TextView
    private lateinit var playlistPanel:View
    private lateinit var searchPanel:View
    private lateinit var queueList:RecyclerView
    private lateinit var queueEmpty:View
    private lateinit var queueCount:TextView
    private lateinit var queueAdapter:QueueAdapter
    private var showSearch=false
    private val uiHandler=Handler(Looper.getMainLooper())
    private var seeking=false
    private lateinit var adapter:SongAdapter
    private var selectedSources=sources
    private val prefs by lazy { getSharedPreferences("music",Context.MODE_PRIVATE) }

    /** Android 13+ 通知权限请求器：授权后开启前台播放通知（息屏续播的锁屏/通知栏控制） */
    private val notifPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        // 权限状态由 PlayerManager 实时查询，这里只需在刚授权时补一次前台保活
        if (granted) runCatching { PlayerManager.updateForeground() }
    }

    private fun syncNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    /** 上次若崩过，重开 App 直接弹窗展示堆栈，可一键复制（免 adb 排查） */
    private fun showCrashLogIfAny() {
        val log = CrashHandler.read(this)
        if (log.isBlank()) return
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("上次崩溃信息")
            .setMessage(log.takeLast(4000))
            .setPositiveButton("复制") { d, _ ->
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("crash", log))
                Toast.makeText(this, "已复制崩溃日志", Toast.LENGTH_SHORT).show()
                CrashHandler.clear(this); d.dismiss()
            }
            .setNegativeButton("清除") { d, _ -> CrashHandler.clear(this); d.dismiss() }
            .setNeutralButton("关闭", null)
            .show()
    }

    // 离线下载：静默缓存存到 App 私有音乐目录（卸载即清，不污染相册）
    private val musicDir by lazy { getExternalFilesDir(Environment.DIRECTORY_MUSIC)!!.apply { mkdirs() } }
    private val dlStates = mutableMapOf<String,String>() // song.key() -> none|downloading|done|error

    // 手动下载：临时挂起的歌曲 + SAF 目录选择器（每次点⬇都弹系统文件夹选择器）
    private var pendingDownload: Song? = null
    private val pickDirLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val song = pendingDownload ?: return@registerForActivityResult
        pendingDownload = null
        if (uri == null) return@registerForActivityResult
        try {
            contentResolver.takePersistableUriPermission(uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        } catch (e: Exception) { /* 少数机型不授予持久权限，本次仍可用 */ }
        prefs.edit().putString("lastTreeUri", uri.toString()).apply()
        scope.launch(Dispatchers.IO) { downloadToTree(song, uri) }
    }

    override fun onCreate(b:Bundle?) {
        super.onCreate(b); setContentView(R.layout.activity_main)
        input=findViewById(R.id.searchInput); status=findViewById(R.id.statusText)
        nowPlaying=findViewById(R.id.nowPlaying); nowArtist=findViewById(R.id.nowArtist)
        lyricsText=findViewById(R.id.lyricsText); cover=findViewById(R.id.coverImage)
        CrashHandler.init(this) // 崩溃捕获：闪退后重开 App 进「设置 → 崩溃日志」即可查看（免 adb）
        showCrashLogIfAny()     // 上次崩过则直接弹窗显示堆栈，可一键复制
        DesktopLyric.init(this) // 桌面歌词（悬浮窗）控制器

        PlayerManager.init(this) // 初始化全局播放管理器（幂等）
        // 通知权限（Android 13+ 必须，否则播放时前台服务 startForeground 会崩溃）
        syncNotificationPermission()
        playPauseBtn=findViewById(R.id.playPauseBtn); seekBar=findViewById(R.id.seekBar)
        curTime=findViewById(R.id.curTime); totalTime=findViewById(R.id.totalTime)
        modeBtn=findViewById(R.id.modeBtn)
        lyricFloatBtn=findViewById(R.id.lyricFloatBtn)
        playerBar=findViewById(R.id.playerBar)
        playerBar.visibility=View.GONE // 首页/搜索时若无正在播放的歌，先隐藏播放条
        playPauseBtn.setOnClickListener{
            if(player.isPlaying){ player.pause() }
            else { if(player.duration>0 && player.currentPosition>=player.duration) player.seekTo(0); player.play() }
        }
        modeBtn.setOnClickListener {
            val m = (PlayerManager.playMode + 1) % 3
            PlayerManager.setMode(m)
            val tip = when (m) { 1 -> "列表循环"; 2 -> "随机播放"; else -> "顺序播放（播完停）" }
            status.text = tip
            Toast.makeText(this, tip, Toast.LENGTH_SHORT).show()
        }
        lyricFloatBtn.setOnClickListener { toggleDesktopLyric() }
        applyPlayMode()
        refreshLyricBtn()
        // 上次开着桌面歌词的话，重启后自动恢复（进程被杀窗口也会没，需重建）
        if (DesktopLyric.enabled && DesktopLyric.hasPermission()) DesktopLyric.show()
        seekBar.setOnSeekBarChangeListener(object:android.widget.SeekBar.OnSeekBarChangeListener{
            override fun onProgressChanged(sb:android.widget.SeekBar?,p:Int,fromUser:Boolean){ if(fromUser) curTime.text=fmt(p.toLong()) }
            override fun onStartTrackingTouch(sb:android.widget.SeekBar?){ seeking=true }
            override fun onStopTrackingTouch(sb:android.widget.SeekBar?){ seeking=false; sb?.let{ player.seekTo(it.progress.toLong()) } }
        })
        uiHandler.post(progressRunnable)

        adapter=SongAdapter(
            onPlay = { PlayerManager.playFromList(adapter.snapshot(), it) },
            onFavorite = { s -> onStarClick(s); adapter.notifyDataSetChanged() },
            isFavorite = { isFavorite(it) },
            onDownload = { requestManualDownload(it) },
            dlState = { dlStates[it.key()] ?: if (isDownloaded(it)) "done" else "none" }
        )
        handlePlayIntent()
        findViewById<RecyclerView>(R.id.songList).apply {
            val lm=LinearLayoutManager(this@MainActivity)
            layoutManager=lm; adapter=this@MainActivity.adapter
            addOnScrollListener(object:RecyclerView.OnScrollListener(){
                override fun onScrolled(rv:RecyclerView,dx:Int,dy:Int){
                    if(dy>0 && !isLoadingMore && hasMore &&
                        lm.findLastVisibleItemPosition() >= this@MainActivity.adapter.itemCount-3){
                        loadMore()
                    }
                }
            })
        }
        findViewById<Button>(R.id.searchButton).setOnClickListener { search() }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_NULL) { search(); true }
            else false
        }

        findViewById<Button>(R.id.favoriteButton).setOnClickListener {
            PlayerManager.current?.let { onStarClick(it) }
        }
        findViewById<View>(R.id.openDownloads).setOnClickListener {
            startActivity(Intent(this, DownloadedActivity::class.java))
        }
        findViewById<View>(R.id.openFavorites).setOnClickListener {
            startActivity(Intent(this, FavoritesActivity::class.java))
        }
        findViewById<View>(R.id.openSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        // 净资产记账（移植的 WebView 记账页）：播放列表面板顶部的入口卡片
        findViewById<View>(R.id.openNetAsset).setOnClickListener {
            startActivity(Intent(this, NetAssetActivity::class.java))
        }
        findViewById<Button>(R.id.sourceAll).setOnClickListener { selectedSources=sources; status.text="全部音源"; updateSourceChips() }
        findViewById<Button>(R.id.sourceNetease).setOnClickListener { choose("netease") }
        findViewById<Button>(R.id.playAllResults).setOnClickListener { PlayerManager.playAllResults(adapter.snapshot()) }
        updateSourceChips()

        // ===== 播放列表页（进 App 默认落点）=====
        tabPlaylist=findViewById(R.id.tabPlaylist); tabSearch=findViewById(R.id.tabSearch)
        playlistPanel=findViewById(R.id.playlistPanel); searchPanel=findViewById(R.id.searchPanel)
        queueList=findViewById(R.id.queueList); queueEmpty=findViewById(R.id.queueEmpty)
        queueCount=findViewById(R.id.queueCount)
        tabPlaylist.setOnClickListener { switchTab(false) }
        tabSearch.setOnClickListener { switchTab(true) }
        findViewById<View>(R.id.gotoSearchBtn).setOnClickListener { switchTab(true) }
        findViewById<View>(R.id.playAllQueue).setOnClickListener {
            val q=PlayerManager.queueSnapshot()
            if(q.isEmpty()) Toast.makeText(this,"播放列表是空的",Toast.LENGTH_SHORT).show()
            else PlayerManager.playAllResults(q)
        }
        findViewById<View>(R.id.clearQueue).setOnClickListener {
            if(PlayerManager.queueSnapshot().isEmpty()){ Toast.makeText(this,"播放列表已经是空的",Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            PlayerManager.clearQueue()
            Toast.makeText(this,"播放列表已清空",Toast.LENGTH_SHORT).show()
        }
        queueAdapter=QueueAdapter(
            onPlay = { idx, _ ->
                // 界面数据可能比真实队列旧（极端情况），越界直接忽略，别崩
                val snap=PlayerManager.queueSnapshot()
                val s=snap.getOrNull(idx) ?: return@QueueAdapter
                PlayerManager.playInternal(s, snap, idx)
            },
            onRemove = { idx, _ -> PlayerManager.removeFromQueue(idx) }
        )
        queueList.apply {
            layoutManager=LinearLayoutManager(this@MainActivity)
            adapter=queueAdapter
        }
        switchTab(false) // 默认进入播放列表

        // 注册为播放 UI 监听，并把当前播放状态同步到播放条（用于息屏后重新打开 App）
        PlayerManager.uiListener = this
        // 进程上次被杀过：恢复上次的播放列表和高亮（不自动播放），
        // 否则用户从下载页点过播放全部、退出重进列表就空了
        PlayerManager.restoreQueueIfEmpty()
        PlayerManager.onUiAttached()
    }

    /** 分段切换：showSearch=false 播放列表 / true 搜索 */
    private fun switchTab(search: Boolean){
        showSearch=search
        playlistPanel.visibility=if(search) View.GONE else View.VISIBLE
        searchPanel.visibility=if(search) View.VISIBLE else View.GONE
        tabPlaylist.setBackgroundResource(if(search) R.drawable.bg_pill_surface else R.drawable.bg_pill_primary)
        tabPlaylist.setTextColor(ContextCompat.getColor(this, if(search) R.color.on_surface_variant else R.color.on_primary))
        tabSearch.setBackgroundResource(if(search) R.drawable.bg_pill_primary else R.drawable.bg_pill_surface)
        tabSearch.setTextColor(ContextCompat.getColor(this, if(search) R.color.on_primary else R.color.on_surface_variant))
        if(!search) refreshQueue()
    }

    /** 刷新播放列表页：标题计数 + 空状态显隐。线程免疫（onSongStart 可能来自播放器回调线程） */
    private fun refreshQueue(){
        val block={
            val q=PlayerManager.queueSnapshot()
            queueCount.text = if(q.isEmpty()) "播放列表" else "播放列表 · ${q.size} 首"
            queueAdapter.submit(q)
            queueEmpty.visibility=if(q.isEmpty()) View.VISIBLE else View.GONE
            queueList.visibility=if(q.isEmpty()) View.GONE else View.VISIBLE
        }
        if(Looper.myLooper()==Looper.getMainLooper()) block() else uiHandler.post(block)
    }

    private fun applyPlayMode() {
        val m = PlayerManager.playMode
        modeBtn.setImageResource(when (m) { 1 -> R.drawable.ic_repeat; 2 -> R.drawable.ic_shuffle; else -> R.drawable.ic_sequence })
        modeBtn.contentDescription = when (m) { 1 -> "列表循环"; 2 -> "随机播放"; else -> "顺序播放" }
    }

    /** 桌面歌词开关：无悬浮窗权限时先引导授权 */
    private fun toggleDesktopLyric() {
        if (DesktopLyric.enabled) {
            DesktopLyric.enabled = false
            refreshLyricBtn()
            status.text = "桌面歌词已关闭"
            return
        }
        if (!DesktopLyric.hasPermission()) {
            AlertDialog.Builder(this)
                .setTitle("需要悬浮窗权限")
                .setMessage("桌面歌词要浮在其他应用上方显示，请在系统设置里允许「显示在其他应用上层」。")
                .setPositiveButton("去授权") { _, _ -> DesktopLyric.requestPermission() }
                .setNegativeButton("取消", null)
                .show()
            return
        }
        DesktopLyric.enabled = true // setter 内部会尝试 show
        val ok = DesktopLyric.isShowing()
        refreshLyricBtn()
        status.text = if (ok) "桌面歌词已开启" else "桌面歌词开启失败（可到设置里调位置/字号）"
    }

    private fun refreshLyricBtn() {
        val on = DesktopLyric.enabled
        lyricFloatBtn.setColorFilter(
            ContextCompat.getColor(this, if (on) R.color.primary else R.color.star_off))
    }

    private fun choose(s:String){selectedSources=listOf(s);status.text="当前音源：${nameOf(s)}";updateSourceChips()}

    private val chipDefs = listOf(
        "all" to R.id.sourceAll, "netease" to R.id.sourceNetease
    )
    private fun updateSourceChips(){
        val white = ContextCompat.getColor(this, R.color.on_primary)
        val variant = ContextCompat.getColor(this, R.color.on_surface_variant)
        val primary = ContextCompat.getColor(this, R.color.primary)
        val surface = ContextCompat.getColor(this, R.color.surface)
        val outline = ContextCompat.getColor(this, R.color.outline)
        for ((key, id) in chipDefs){
            val btn = findViewById<MaterialButton>(id)
            val on = (key == "all" && selectedSources.size == sources.size) ||
                    (key != "all" && selectedSources == listOf(key))
            btn.backgroundTintList = ColorStateList.valueOf(if (on) primary else surface)
            btn.setTextColor(if (on) white else variant)
            btn.strokeColor = ColorStateList.valueOf(outline)
            btn.strokeWidth = if (on) 0 else 1
        }
    }

    /** 去重键必须带 source：否则不同音源的"同名歌"会被合并，只留先查到的 netease */
    private fun dedupKey(x:Song)=x.source.lowercase()+"|"+x.name.lowercase()+"|"+x.artist.lowercase()

    private fun search(){
        val kw=input.text.toString().trim(); if(kw.isEmpty())return
        lastKeyword=kw; currentPage=1; hasMore=true; isLoadingMore=false
        status.text="正在搜索……"
        scope.launch{
            val failed=mutableListOf<String>()
            val results=withContext(Dispatchers.IO){ fetchPage(kw,1,failed) }
                .distinctBy{ dedupKey(it) }
            adapter.submit(results)
            status.text = buildStatus(results.size, failed)
        }
    }

    /** 滚动到底部自动加载下一页 */
    private fun loadMore(){
        if(isLoadingMore || !hasMore || lastKeyword.isBlank()) return
        isLoadingMore=true
        val next=currentPage+1
        status.text="正在加载第 $next 页……"
        scope.launch{
            val failed=mutableListOf<String>()
            val page=withContext(Dispatchers.IO){ fetchPage(lastKeyword,next,failed) }
            val seen=adapter.snapshot().map{ dedupKey(it) }.toHashSet()
            val fresh=page.distinctBy{ dedupKey(it) }.filter{ dedupKey(it) !in seen }
            adapter.append(fresh)
            currentPage=next
            hasMore = page.isNotEmpty() && fresh.isNotEmpty()
            isLoadingMore=false
            status.text = if(hasMore) "已加载 ${adapter.snapshot().size} 首 · 继续下滑加载更多"
            else "已加载全部 ${adapter.snapshot().size} 首"
        }
    }

    private fun buildStatus(count:Int, failed:List<String>):String{
        val base = if(failed.isEmpty()) "找到 $count 首歌曲"
                   else "找到 $count 首（${failed.joinToString("、"){ sourceLabel(it) }} 暂不可用）"
        return if(count>0 && hasMore) "$base · 下滑可加载更多" else base
    }

    private fun fetchPage(kw:String,page:Int,failed:MutableList<String>):List<Song>{
        return selectedSources.flatMap{ s->
            runCatching{ searchSource(kw,s,page) }
                .onFailure{ failed += s }
                .getOrDefault(emptyList())
        }
    }

    private fun searchSource(kw:String,source:String,page:Int=1):List<Song>{
        val u="$api?types=search&source=$source&name=${enc(kw)}&count=$pageSize&pages=$page"
        val a=JSONArray(get(u)); val out=mutableListOf<Song>()
        for(i in 0 until a.length()){
            val o=a.getJSONObject(i); val ar=o.optJSONArray("artist")
            val artist=if(ar!=null)(0 until ar.length()).joinToString(" / "){ar.optString(it)}
            else o.optString("artist")
            out+=Song(o.optString("id"),o.optString("name"),artist,o.optString("album"),
                o.optString("source",source),o.optString("pic_id"),o.optString("lyric_id",o.optString("id")))
        }
        return out
    }

    // ---- 收藏（多歌单） ----
    /** ⭐ 点击：单歌单直接 toggle；多歌单弹选择框 */
    private fun onStarClick(s:Song){
        val pls=PlaylistStore.getPlaylists(this)
        if(pls.size<=1){
            if(PlaylistStore.isInPlaylist(this, PlaylistStore.DEFAULT_ID, s))
                PlaylistStore.removeSong(this, PlaylistStore.DEFAULT_ID, s)
            else PlaylistStore.addSong(this, PlaylistStore.DEFAULT_ID, s)
            refreshStar(s)
        } else {
            showPlaylistPicker(s)
        }
    }

    private fun refreshStar(s:Song){
        if(s==PlayerManager.current) findViewById<Button>(R.id.favoriteButton).text=if(isFavorite(s))"★ 已收藏" else "☆ 收藏"
        adapter.notifyDataSetChanged()
    }

    private fun showPlaylistPicker(s:Song){
        val pls=PlaylistStore.getPlaylists(this)
        val names=pls.map{ it.name }.toTypedArray()
        val checked=pls.map{ PlaylistStore.isInPlaylist(this, it.id, s) }.toBooleanArray()
        AlertDialog.Builder(this)
            .setTitle("收藏到歌单")
            .setMultiChoiceItems(names, checked){ _, which, isChecked ->
                val p=pls[which]
                if(isChecked) PlaylistStore.addSong(this, p.id, s) else PlaylistStore.removeSong(this, p.id, s)
            }
            .setPositiveButton("完成"){ d,_ -> d.dismiss(); refreshStar(s) }
            .setNeutralButton("＋新建"){ _,_ -> newPlaylistThenAdd(s) }
            .show()
    }

    private fun newPlaylistThenAdd(s:Song){
        val edit=EditText(this); edit.hint="歌单名称"
        AlertDialog.Builder(this)
            .setTitle("新建歌单")
            .setView(edit)
            .setPositiveButton("创建"){ _,_ ->
                val id=PlaylistStore.createPlaylist(this, edit.text.toString())
                PlaylistStore.addSong(this, id, s)
                refreshStar(s)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun isFavorite(s:Song)=PlaylistStore.isFavoritedAnywhere(this, s)

    private fun saveHistory(s:Song){
        val old=prefs.getStringSet("history",emptySet())!!.toMutableList()
        old.remove(s.key()); old.add(0,s.key()); if(old.size>50)old.removeAt(old.lastIndex)
        prefs.edit().putStringSet("history",old.toSet()).apply()
    }

    // ---- 离线下载 ----
    private fun baseName(s:Song)=s.key().replace(Regex("[^a-zA-Z0-9]"),"_")
    private fun localUri(s:Song):Uri? = DownloadStore.localUri(this, s)
    private fun isDownloaded(s:Song)=localUri(s)!=null

    private fun requestManualDownload(s:Song){
        val k=s.key()
        if(isDownloaded(s)){ setDl(k,"done"); return }
        pendingDownload=s
        pickDirLauncher.launch(null)
    }

    /** 手动下载：把音频写到用户选的 SAF 目录里。注意：本方法在 Dispatchers.IO 上跑，
     *  所有碰 UI 的操作（adapter.notifyDataSetChanged）必须经 setDl 切回主线程，
     *  否则 RecyclerView 在子线程 requestLayout 直接崩（CalledFromWrongThreadException） */
    private fun downloadToTree(s:Song, treeUri:Uri){
        val k=s.key()
        setDl(k,"downloading")
        try{
            val o=JSONObject(get("$api?types=url&source=${enc(s.source)}&id=${enc(s.id)}&br=320"))
            val url=o.optString("url")
            if(url.isBlank()||url=="null"){ setDl(k,"error"); return }
            val resp=client.newCall(Request.Builder().url(url).header("User-Agent","GDMusicPlayer/3.0").build()).execute()
            if(!resp.isSuccessful){ resp.close(); setDl(k,"error"); return }
            val ct=resp.header("Content-Type")
            val ext=extFrom(url,ct)
            val mime=mimeFromExt(ext)
            val tree=DocumentFile.fromTreeUri(this, treeUri)
            val folderName=tree?.name ?: "音乐"
            val safeName="${s.name}-${s.artist}".replace(Regex("[\\\\/:*?\"<>|]"),"_").take(80)
            val doc=tree?.createFile(mime, "$safeName.$ext")
            if(doc==null){ setDl(k,"error"); return }
            val os=contentResolver.openOutputStream(doc.uri)
            if(os==null){ setDl(k,"error"); return }
            resp.body?.byteStream()?.use{ inp-> os.use{ inp.copyTo(it) } }
            resp.close()
            val size=doc.length()
            DownloadStore.add(this, s, doc.uri, "manual", folderName, size)
            setDl(k,"done")
        }catch(e:Exception){ setDl(k,"error") }
    }

    /** 下载状态刷新。做成线程免疫：不管谁在什么线程调，都保证切到主线程再碰 RecyclerView，
     *  杜绝 CalledFromWrongThreadException（downloadToTree 跑在 Dispatchers.IO 上） */
    private fun setDl(k:String, st:String){
        if(Looper.myLooper()==Looper.getMainLooper()){
            dlStates[k]=st; adapter.notifyDataSetChanged()
        }else{
            uiHandler.post{ dlStates[k]=st; adapter.notifyDataSetChanged() }
        }
    }

    private fun mimeFromExt(ext:String)=when(ext.lowercase()){
        "mp3"->"audio/mpeg"; "m4a"->"audio/mp4"; "flac"->"audio/flac"
        "wav"->"audio/wav"; "ogg"->"audio/ogg"; "opus"->"audio/ogg"
        "aac"->"audio/aac"; else->"audio/mpeg"
    }

    private fun extFrom(url:String, ct:String?):String{
        val p=url.substringBefore('?').substringAfterLast('.',"")
        if(p.length in 2..4) return p.lowercase()
        return when{
            ct?.contains("mpeg")==true || ct?.contains("mp3")==true -> "mp3"
            ct?.contains("mp4")==true || ct?.contains("m4a")==true -> "m4a"
            ct?.contains("flac")==true -> "flac"
            ct?.contains("wav")==true -> "wav"
            ct?.contains("ogg")==true || ct?.contains("opus")==true -> "ogg"
            else -> "mp3"
        }
    }

    private fun get(u:String):String{
        val r=Request.Builder().url(u).header("User-Agent","GDMusicPlayer/3.0").build()
        return client.newCall(r).execute().use{if(!it.isSuccessful)throw Exception("HTTP ${it.code}");it.body?.string()?:""}
    }
    private fun enc(s:String)=URLEncoder.encode(s,"UTF-8")
    private fun nameOf(s:String)=sourceLabel(s)
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent)
        handlePlayIntent()
    }

    /**
     * 统一处理外部（收藏页/下载页）传入的播放意图。
     * 旧代码分两个函数且都读 play_queue_json，会被连续执行两次导致重复起播，现合并。
     * 优先级：队列播放 > 单首本地 > 单首在线。
     */
    private fun handlePlayIntent(){
        // 1) 队列播放（下载页/收藏页的「播放全部」和「点单首」都走这支，idx 区分起点）
        val queueJson=intent.getStringExtra("play_queue_json")
        if(queueJson!=null){
            val list=songsFromJsonArray(queueJson)
            val idx=intent.getIntExtra("play_index",0).coerceIn(0, maxOf(0, list.size-1))
            if(list.isNotEmpty()){
                PlayerManager.playInternal(list[idx], list, idx)
                // 显式刷新一次播放列表页：不依赖 onSongStart 回调（起播失败时回调可能不来）
                refreshQueue()
            }
            return
        }
        // 2) 单首本地播放（uri 直传）
        val uriStr=intent.getStringExtra("play_local_uri")
        if(uriStr!=null){
            val json=intent.getStringExtra("play_local_json")
            val song=if(json!=null) runCatching{
                val o=JSONObject(json)
                Song(o.optString("id"),o.optString("name"),o.optString("artist"),
                    o.optString("album"),o.optString("source"),o.optString("picId"),
                    o.optString("lyricId",o.optString("id")),o.optString("localUri").ifBlank{uriStr})
            }.getOrDefault(Song("","","","","","","",uriStr)) else Song("","","","","","","",uriStr)
            PlayerManager.playInternal(song, listOf(song), 0)
            return
        }
        // 3) 单首在线播放
        val json=intent.getStringExtra("play_song_json") ?: return
        runCatching{
            val o=JSONObject(json)
            val s=Song(o.optString("id"),o.optString("name"),o.optString("artist"),
                o.optString("album"),o.optString("source"),o.optString("picId"),
                o.optString("lyricId",o.optString("id")))
            PlayerManager.playFromList(listOf(s), s)
        }
    }

    private val progressRunnable=object:Runnable{
        override fun run(){ updateProgress(); uiHandler.postDelayed(this,500) }
    }
    private fun updateProgress(){
        val d=player.duration
        if(d>0){
            seekBar.max=d.toInt()
            if(!seeking) seekBar.progress=player.currentPosition.toInt()
            curTime.text=fmt(player.currentPosition); totalTime.text=fmt(d)
        }else{
            curTime.text=fmt(player.currentPosition); totalTime.text="00:00"
        }
        playPauseBtn.setImageResource(if(player.isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
        DesktopLyric.onProgress(player.currentPosition) // 桌面歌词按进度滚动到当前行
    }
    private fun fmt(ms:Long):String{
        if(ms<0) return "00:00"
        val s=(ms/1000).toInt(); val m=s/60; return "%02d:%02d".format(m,s%60)
    }

    /** 把用户填写的接口地址归一化：去掉首尾空白与末尾斜杠，确保以 /api.php 结尾 */
    private fun normalizeApi(raw:String):String{
        var s=raw.trim()
        if(s.isBlank()) s=SettingsActivity.DEFAULT_API
        while(s.endsWith("/")) s=s.substring(0, s.length-1)
        if(!s.endsWith("/api.php", ignoreCase=true)) s+="/api.php"
        return s
    }

    override fun onDestroy(){
        // 不再释放播放器：播放由前台服务保活，息屏/退出首页都不会中断；仅断开 UI 监听
        uiHandler.removeCallbacks(progressRunnable)
        scope.cancel()
        PlayerManager.uiListener = null
        super.onDestroy()
    }

    // ===== PlayerUiListener：播放管理器把状态推给这里，刷新底部播放条 =====
    override fun onSongStart(song: Song, isLocal: Boolean, statusText: String) {
        playerBar.visibility = View.VISIBLE
        nowPlaying.text = song.name
        nowArtist.text = "${song.artist} · ${song.album}"
        findViewById<Button>(R.id.favoriteButton).text = if(isFavorite(song))"★ 已收藏" else "☆ 收藏"
        cover.setImageBitmap(null)
        lyricsText.text = "加载中……"
        status.text = statusText
        // 关键：播放条换了歌，播放列表的「播放中」标记必须同步刷新，
        // 否则自动切歌/点歌后高亮停在旧歌，出现「显示在播 A、实际响 B」
        refreshQueue()
    }
    override fun onCover(bitmap: Bitmap?) {
        if (bitmap != null) cover.setImageBitmap(bitmap)
        else cover.setImageResource(android.R.color.transparent)
    }
    override fun onLyrics(text: String) { lyricsText.text = text; DesktopLyric.setLyrics(text) }
    override fun onStatus(text: String) { status.text = text }
    override fun onPlayStateChanged(isPlaying: Boolean) {
        playPauseBtn.setImageResource(if(isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
    }
    override fun onModeChanged(mode: Int) { applyPlayMode() }
    override fun onCacheStateChanged(key: String, state: String) {
        dlStates[key] = state; adapter.notifyDataSetChanged()
    }
    override fun onQueueChanged() { refreshQueue() }
}

/** 歌单/下载页把歌曲列表传给 MainActivity 的公共序列化（同包可见） */
fun songsToJsonArray(list: List<Song>): String {
    val a=JSONArray(); list.forEach { a.put(FavoritesStore.songToJson(it)) }; return a.toString()
}
fun songsFromJsonArray(str: String?): List<Song> {
    if(str.isNullOrBlank()) return emptyList()
    val a=JSONArray(str); val out=mutableListOf<Song>()
    for(i in 0 until a.length()) FavoritesStore.fromJsonStr(a.optString(i))?.let { out.add(it) }
    return out
}

class SongAdapter(
    private val onPlay:(Song)->Unit,
    private val onFavorite:(Song)->Unit,
    private val isFavorite:(Song)->Boolean,
    private val onDownload:(Song)->Unit,
    private val dlState:(Song)->String
):RecyclerView.Adapter<SongAdapter.VH>(){
    private val data=mutableListOf<Song>()
    fun submit(l:List<Song>){data.clear();data.addAll(l);notifyDataSetChanged()}
    fun append(l:List<Song>){
        if(l.isEmpty())return
        val start=data.size; data.addAll(l); notifyItemRangeInserted(start,l.size)
    }
    fun snapshot():List<Song> = data.toList()
    override fun getItemCount()=data.size
    override fun onCreateViewHolder(p:ViewGroup,v:Int)=VH(LayoutInflater.from(p.context).inflate(R.layout.item_song,p,false))
    override fun onBindViewHolder(h:VH,p:Int)=h.bind(data[p])
    inner class VH(v:View):RecyclerView.ViewHolder(v){
        private val content=v.findViewById<View>(R.id.contentLayout)
        private val n=v.findViewById<TextView>(R.id.songName)
        private val a=v.findViewById<TextView>(R.id.songArtist)
        private val s=v.findViewById<TextView>(R.id.songSource)
        private val dl=v.findViewById<TextView>(R.id.dlBtn)
        private val fav=v.findViewById<TextView>(R.id.favBtn)
        fun bind(x:Song){
            n.text=x.name
            a.text="${x.artist} · ${x.album}"
            s.text=sourceLabel(x.source)
            val on=isFavorite(x)
            fav.text=if(on)"★" else "☆"
            fav.setTextColor(ContextCompat.getColor(fav.context, if(on) R.color.star_on else R.color.star_off))
            val st=dlState(x)
            dl.text=when(st){"downloading"->"⏳";"done"->"✓";"error"->"⚠";else->"⬇"}
            dl.setTextColor(ContextCompat.getColor(dl.context, when(st){
                "done"->R.color.download_done
                "error"->R.color.download_err
                "downloading"->R.color.primary
                else->R.color.star_off
            }))
            content.setOnClickListener{ onPlay(x) }
            fav.setOnClickListener{ onFavorite(x) }
            dl.setOnClickListener{ if(dlState(x)!="done" && dlState(x)!="downloading") onDownload(x) }
        }
    }
}

/** 播放列表适配器：行点击=切到该首播放，右侧 ✕=从队列移除；当前播放项高亮 */
class QueueAdapter(
    private val onPlay:(Int, Song)->Unit,
    private val onRemove:(Int, Song)->Unit
):RecyclerView.Adapter<QueueAdapter.VH>(){
    private val data=mutableListOf<Song>()
    fun submit(l:List<Song>){data.clear();data.addAll(l);notifyDataSetChanged()}
    override fun getItemCount()=data.size
    override fun onCreateViewHolder(p:ViewGroup,v:Int)=VH(LayoutInflater.from(p.context).inflate(R.layout.item_queue_song,p,false))
    override fun onBindViewHolder(h:VH,p:Int)=h.bind(data[p],p)
    inner class VH(private val root:View):RecyclerView.ViewHolder(root){
        private val idx=root.findViewById<TextView>(R.id.qIndex)
        private val n=root.findViewById<TextView>(R.id.qName)
        private val a=root.findViewById<TextView>(R.id.qArtist)
        private val playing=root.findViewById<TextView>(R.id.qPlaying)
        private val rm=root.findViewById<TextView>(R.id.qRemove)
        fun bind(x:Song, position:Int){
            // 高亮「正在播放」：优先对象 identity（队列里就是 current 那个对象），
            // 再退按键匹配。不能只按键——同一首歌在队列里出现两次时按键会撞车，
            // 高亮落到第一首上，而实际播的是第二首（用户看到「显示在播 A、实际响 B」）
            val cur=PlayerManager.current
            val isCur=cur!=null && (cur===x || cur.key()==x.key())
            idx.text=(position+1).toString()
            idx.setTextColor(ContextCompat.getColor(root.context, if(isCur) R.color.primary else R.color.on_surface_variant))
            n.text=x.name
            n.setTextColor(ContextCompat.getColor(root.context, if(isCur) R.color.primary else R.color.on_surface))
            a.text=if(x.artist.isBlank()) x.album else "${x.artist} · ${x.album}"
            playing.visibility=if(isCur) View.VISIBLE else View.GONE
            root.setOnClickListener{ onPlay(position, x) }
            rm.setOnClickListener{ onRemove(position, x) }
        }
    }
}
