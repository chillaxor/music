package com.linghit.gdmusic

import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class DownloadedActivity : AppCompatActivity() {

    private lateinit var adapter: DlAdapter
    private lateinit var emptyText: TextView
    private lateinit var totalText: TextView
    private lateinit var folderTabs: LinearLayout
    private lateinit var searchBox: EditText
    private lateinit var searchClear: TextView
    private lateinit var importBtn: View
    /** 导入状态订阅：导入结束（哪怕是在别的页面结束的）后恢复按钮并刷新列表。
     *  必须在 onDestroy 解绑，否则 ImportProgress 会一直持有本页导致泄漏 */
    private val importStateListener: (ImportProgress.State) -> Unit = { s ->
        if (!s.active) {
            importBtn.isEnabled = true
            if (::adapter.isInitialized) refresh()
        }
    }
    /** 逐条导入订阅：每批新歌进来就实时追加进列表（「导入哪条显示哪条」），
     *  不等职结束才整盘刷新。按 key 去重，避免与 refresh() 整盘重载撞出重复项 */
    private val importItemListener: (List<DownloadedItem>) -> Unit = { items ->
        if (items.isNotEmpty() && ::adapter.isInitialized) {
            val seen = allItems.map { it.key }.toSet()
            val add = items.filter { it.key !in seen }
            if (add.isNotEmpty()) {
                allItems = (allItems + add).sortedWith(compareBy({ it.folder }, { it.song.name }))
                renderTabs()
                applyFilter()
            }
        }
    }
    private var allItems = listOf<DownloadedItem>()
    private var currentItems = listOf<DownloadedItem>()
    /** null = 全部文件夹；否则只显示/只播该文件夹（导入时的目录路径） */
    private var selectedFolder: String? = null
    /** 搜索关键词（歌名/歌手/专辑，大小写不敏感）；空 = 不搜索 */
    private var keyword = ""

    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> if (uri != null) importFolder(uri) }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        ThemePrefs.applyTheme(this)
        setContentView(R.layout.activity_downloaded)

        emptyText = findViewById(R.id.emptyText)
        totalText = findViewById(R.id.totalText)
        folderTabs = findViewById(R.id.folderTabs)
        searchBox = findViewById(R.id.dlSearch)
        searchClear = findViewById(R.id.dlSearchClear)
        importBtn = findViewById(R.id.importBtn)
        ImportProgress.listen(importStateListener)
        ImportProgress.listenItems(importItemListener)

        searchBox.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                keyword = s?.toString()?.trim() ?: ""
                searchClear.visibility = if (keyword.isEmpty()) View.GONE else View.VISIBLE
                applyFilter()
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        // 输入法「搜索」键 = 收起键盘，过滤已实时生效
        searchBox.setOnEditorActionListener { _, _, _ ->
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                .hideSoftInputFromWindow(searchBox.windowToken, 0)
            true
        }
        searchClear.setOnClickListener {
            searchBox.setText("")
            keyword = ""
            searchClear.visibility = View.GONE
            applyFilter()
        }

        adapter = DlAdapter(
            onPlay = { playItem(it) },
            onDelete = { deleteItem(it) },
            onInsert = { insertItem(it) }
        )
        findViewById<RecyclerView>(R.id.downloadedList).apply {
            layoutManager = LinearLayoutManager(this@DownloadedActivity)
            adapter = this@DownloadedActivity.adapter
        }

        findViewById<TextView>(R.id.backBtn).setOnClickListener { finish() }
        findViewById<View>(R.id.playAllBtn).setOnClickListener { playAll() }
        findViewById<View>(R.id.importBtn).setOnClickListener {
            importLauncher.launch(null)
        }

        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh() // 回到本页时刷新（可能从主页下载后回来）
    }

    private fun refresh() {
        allItems = DownloadStore.list(this)
            .sortedWith(compareBy({ it.folder }, { it.song.name }))
        renderTabs()
        applyFilter()
    }

    /** 文件夹 Tab：「全部」+ 每个导入/缓存目录一个；只有一个目录时没必要占地方 */
    private fun renderTabs() {
        folderTabs.removeAllViews()
        val folders = allItems.map { it.folder }.distinct()
        if (folders.size <= 1) {
            selectedFolder = null
            folderTabs.visibility = View.GONE
            return
        }
        folderTabs.visibility = View.VISIBLE
        // 选中的文件夹被删空了：回落到全部
        if (selectedFolder != null && selectedFolder !in folders) selectedFolder = null
        val padH = (12 * resources.displayMetrics.density).toInt()
        val padV = (6 * resources.displayMetrics.density).toInt()
        val marginEnd = (8 * resources.displayMetrics.density).toInt()
        (listOf<String?>(null) + folders).forEach { f ->
            val tv = TextView(this)
            tv.text = f ?: "全部"
            tv.setPadding(padH, padV, padH, padV)
            tv.textSize = 14f
            styleTab(tv, f == selectedFolder)
            tv.setOnClickListener {
                selectedFolder = f
                renderTabs()
                applyFilter()
            }
            folderTabs.addView(
                tv,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.marginEnd = marginEnd }
            )
        }
    }

    private fun styleTab(tv: TextView, selected: Boolean) {
        val bg = GradientDrawable()
        bg.cornerRadius = (18 * resources.displayMetrics.density)
        if (selected) {
            bg.setColor(ContextCompat.getColor(this, R.color.primary))
            tv.setTextColor(ContextCompat.getColor(this, R.color.on_primary))
        } else {
            bg.setColor(ContextCompat.getColor(this, R.color.surface))
            tv.setTextColor(ContextCompat.getColor(this, R.color.on_surface_variant))
        }
        tv.background = bg
    }

    private fun applyFilter() {
        val kw = keyword.lowercase()
        currentItems = allItems.filter { item ->
            (selectedFolder == null || item.folder == selectedFolder) &&
                (kw.isEmpty() ||
                 item.song.name.lowercase().contains(kw) ||
                 item.song.artist.lowercase().contains(kw) ||
                 item.song.album.lowercase().contains(kw))
        }
        adapter.submit(currentItems)
        val totalBytes = currentItems.sumOf { it.sizeBytes }
        totalText.text = buildString {
            append(selectedFolder ?: "全部文件夹")
            append(" · 共 ${currentItems.size} 首")
            if (kw.isNotEmpty()) append("（${allItems.size} 首中匹配）")
            append(" · ${formatSize(totalBytes)}")
        }
        when {
            // 有歌但搜不到：给个明确提示，别显示「还没下载」误导用户
            allItems.isNotEmpty() && currentItems.isEmpty() -> {
                emptyText.text = "没有找到「$keyword」相关的歌曲\n换个关键词试试"
                emptyText.visibility = View.VISIBLE
                findViewById<RecyclerView>(R.id.downloadedList).visibility = View.GONE
            }
            allItems.isEmpty() -> {
                emptyText.text = "还没有下载的歌曲\n在搜索结果里点 ⬇ 离线保存，或点「📁 导入文件夹」把手机里已有的音乐加进来"
                emptyText.visibility = View.VISIBLE
                findViewById<RecyclerView>(R.id.downloadedList).visibility = View.GONE
            }
            else -> {
                emptyText.visibility = View.GONE
                findViewById<RecyclerView>(R.id.downloadedList).visibility = View.VISIBLE
            }
        }
    }

    /** 点某首：临时播放——只播这一首，现有播放列表原样不动；播完自动回到列表里原来那首 */
    private fun playItem(item: DownloadedItem) {
        ensurePlayer()
        PlayerManager.playTemp(item.song)
        backToMain()
    }

    /** 插入播放列表最前面并直接播放：原列表整体后移，「播放中」落到第 1 行 */
    private fun insertItem(item: DownloadedItem) {
        ensurePlayer()
        PlayerManager.insertFrontAndPlay(item.song)
        backToMain()
    }

    /** 进程刚起（冷启动直接落到本页）时 MainActivity 还没跑过初始化：
     *  init 幂等补上，restoreQueueIfEmpty 把上次持久化的播放列表恢复到内存，
     *  否则插入/临时播放会以一个空队列为基准，旧列表凭空消失 */
    private fun ensurePlayer() {
        PlayerManager.init(applicationContext)
        PlayerManager.restoreQueueIfEmpty()
    }

    private fun backToMain() {
        startActivity(Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        })
        finish()
    }

    /** 播放全部：把当前 Tab 的歌灌进播放列表并覆盖原列表，从第一首开始 */
    private fun playAll() {
        val songs = currentItems.map { it.song }
        if (songs.isEmpty()) {
            Toast.makeText(this, "这个文件夹里还没有歌曲", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra("play_queue_json", songsToJsonArray(songs))
            putExtra("play_index", 0)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivity(intent)
        finish()
    }

    private fun deleteItem(item: DownloadedItem) {
        DownloadStore.delete(this, item.key)
        refresh()
    }

    /**
     * 导入用户选中的文件夹：直接交给 MusicImporter 在后台异步跑。
     * MusicImporter 只用 applicationContext，所以离开本页 / 切到任意页面都不受影响，
     * 进度推给 ImportProgress，由 ImportOverlay 在「所有页面」顶部显示直到结束。
     * 本页只负责禁用按钮防重复触发；结束后由 importStateListener 恢复按钮并刷新。
     */
    private fun importFolder(treeUri: Uri) {
        importBtn.isEnabled = false // 防止重复触发导入
        MusicImporter.start(this, treeUri)
    }

    override fun onDestroy() {
        ImportProgress.unlisten(importStateListener)
        ImportProgress.unlistenItems(importItemListener)
        super.onDestroy()
    }

    private fun formatSize(bytes: Long): String {
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb >= 1) "%.1f MB".format(mb) else "${bytes / 1024} KB"
    }

    /** 分区标题：用于按目录分组展示 */
    data class FolderHeader(val folder: String, val count: Int)

    class DlAdapter(
        private val onPlay: (DownloadedItem) -> Unit,
        private val onDelete: (DownloadedItem) -> Unit,
        private val onInsert: (DownloadedItem) -> Unit
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        companion object {
            private const val TYPE_HEADER = 0
            private const val TYPE_SONG = 1
        }

        private val display = mutableListOf<Any>()

        fun submit(items: List<DownloadedItem>) {
            display.clear()
            var lastFolder: String? = null
            for (it in items) {
                if (it.folder != lastFolder) {
                    val count = items.count { i -> i.folder == it.folder }
                    display.add(FolderHeader(it.folder, count))
                    lastFolder = it.folder
                }
                display.add(it)
            }
            notifyDataSetChanged()
        }

        override fun getItemCount() = display.size
        override fun getItemViewType(p: Int) =
            if (display[p] is FolderHeader) TYPE_HEADER else TYPE_SONG

        override fun onCreateViewHolder(parent: ViewGroup, type: Int): RecyclerView.ViewHolder {
            return if (type == TYPE_HEADER) {
                val v = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_folder_header, parent, false)
                HeaderVH(v)
            } else {
                val v = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_downloaded, parent, false)
                SongVH(v)
            }
        }

        override fun onBindViewHolder(h: RecyclerView.ViewHolder, p: Int) {
            if (h is HeaderVH) h.bind(display[p] as FolderHeader)
            else (h as SongVH).bind(display[p] as DownloadedItem)
        }

        class HeaderVH(v: View) : RecyclerView.ViewHolder(v) {
            private val folder = v.findViewById<TextView>(R.id.headerFolder)
            private val count = v.findViewById<TextView>(R.id.headerCount)
            fun bind(x: FolderHeader) {
                folder.text = "📁 ${x.folder}"
                count.text = "${x.count} 首"
            }
        }

        inner class SongVH(v: View) : RecyclerView.ViewHolder(v) {
            private val root = v.findViewById<View>(R.id.itemRoot)
            private val name = v.findViewById<TextView>(R.id.dlName)
            private val artist = v.findViewById<TextView>(R.id.dlArtist)
            private val source = v.findViewById<TextView>(R.id.dlSource)
            private val size = v.findViewById<TextView>(R.id.dlSize)
            private val del = v.findViewById<TextView>(R.id.dlDelete)
            private val ins = v.findViewById<TextView>(R.id.dlInsert)
            fun bind(x: DownloadedItem) {
                name.text = x.song.name.ifBlank { "本地音乐" }
                val a = x.song.artist.ifBlank { null }
                val al = x.song.album.ifBlank { null }
                artist.text = listOfNotNull(a, al).joinToString(" · ")
                val kindTag = if (x.kind == "manual") "📁" else "缓存"
                source.text = "${sourceLabel(x.song.source).ifBlank { "本地" }} · ${kindTag} ${x.folder}"
                val mb = x.sizeBytes / (1024.0 * 1024.0)
                size.text = if (mb >= 1) "%.1f MB".format(mb) else "${x.sizeBytes / 1024} KB"
                source.setTextColor(ContextCompat.getColor(source.context, R.color.primary))
                root.setOnClickListener { onPlay(x) }
                ins.setOnClickListener { onInsert(x) }
                del.setOnClickListener { onDelete(x) }
            }
        }
    }
}
