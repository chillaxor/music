package com.linghit.gdmusic

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.ArrayDeque

class DownloadedActivity : AppCompatActivity() {

    private lateinit var adapter: DlAdapter
    private lateinit var emptyText: TextView
    private lateinit var totalText: TextView
    private var currentItems = listOf<DownloadedItem>()

    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> if (uri != null) importFolder(uri) }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        setContentView(R.layout.activity_downloaded)

        emptyText = findViewById(R.id.emptyText)
        totalText = findViewById(R.id.totalText)

        adapter = DlAdapter(
            onPlay = { playItem(it) },
            onDelete = { deleteItem(it) }
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
        currentItems = DownloadStore.list(this)
            .sortedWith(compareBy({ it.folder }, { it.song.name }))
        adapter.submit(currentItems)
        val totalBytes = currentItems.sumOf { it.sizeBytes }
        totalText.text = "共 ${currentItems.size} 首 · ${formatSize(totalBytes)}"
        emptyText.visibility = if (currentItems.isEmpty()) View.VISIBLE else View.GONE
        findViewById<RecyclerView>(R.id.downloadedList).visibility =
            if (currentItems.isEmpty()) View.GONE else View.VISIBLE
    }

    /** 点某首：把整个下载列表当作队列，从这一首播起（顺序播完停/循环/随机由主播放器决定） */
    private fun playItem(item: DownloadedItem) {
        val songs = currentItems.map { it.song }
        val idx = currentItems.indexOfFirst { it.key == item.key }.coerceAtLeast(0)
        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra("play_queue_json", songsToJsonArray(songs))
            putExtra("play_index", idx)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivity(intent)
        finish()
    }

    private fun playAll() {
        val songs = currentItems.map { it.song }
        if (songs.isEmpty()) return
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

    /** 导入用户选中的文件夹：递归扫描音频文件并写入下载索引（卸载重装后可据此回显） */
    private fun importFolder(treeUri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (_: Exception) { /* 少数机型不授予持久权限，本次仍可扫 */ }

        Thread {
            val root = DocumentFile.fromTreeUri(this, treeUri)
            val items = mutableListOf<DownloadedItem>()
            val audioExt = setOf("mp3", "m4a", "flac", "wav", "ogg", "aac", "opus")
            val queue: ArrayDeque<Pair<DocumentFile, String>> = ArrayDeque()
            if (root != null) queue.add(root to (root.name ?: "导入"))
            while (queue.isNotEmpty()) {
                val (dir, path) = queue.removeFirst()
                dir.listFiles().forEach { child ->
                    if (child.isDirectory) {
                        queue.add(child to "$path/${child.name}")
                    } else if (child.isFile) {
                        val nm = child.name ?: return@forEach
                        val ext = nm.substringAfterLast('.', "").lowercase()
                        if (ext in audioExt) {
                            var (name, artist) = DownloadStore.parseAudioName(nm)
                            var album = ""
                            // 优先读文件真实标签（MP3 ID3 / FLAC 等），比按文件名猜准得多
                            runCatching {
                                AudioTagUtil.readMeta(this@DownloadedActivity, child.uri.toString())
                            }.getOrNull()?.let { m ->
                                if (m.title.isNotBlank()) name = m.title
                                if (m.artist.isNotBlank()) artist = m.artist
                                album = m.album
                            }
                            val song = Song("", name, artist, album, "", "", "", child.uri.toString())
                            val key = song.key()
                            // 同名伴生歌词：拷贝音乐时最常见的歌词载体（.lrc 优先，其次 .txt）
                            val base = nm.substringBeforeLast('.')
                            val lrcDoc = dir.findFile("$base.lrc") ?: dir.findFile("$base.txt")
                            items.add(
                                DownloadedItem(
                                    key, song, child.uri,
                                    path.ifBlank { root?.name ?: "导入" },
                                    "manual", child.length(),
                                    lrcDoc?.uri?.toString() ?: ""
                                )
                            )
                        }
                    }
                }
            }
            DownloadStore.addScanned(this, items)
            runOnUiThread {
                refresh()
                Toast.makeText(
                    this,
                    if (items.isEmpty()) "该文件夹里没有找到音频文件"
                    else "已导入 ${items.size} 首本地音乐",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }.start()
    }

    private fun formatSize(bytes: Long): String {
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb >= 1) "%.1f MB".format(mb) else "${bytes / 1024} KB"
    }

    /** 分区标题：用于按目录分组展示 */
    data class FolderHeader(val folder: String, val count: Int)

    class DlAdapter(
        private val onPlay: (DownloadedItem) -> Unit,
        private val onDelete: (DownloadedItem) -> Unit
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
                del.setOnClickListener { onDelete(x) }
            }
        }
    }
}
