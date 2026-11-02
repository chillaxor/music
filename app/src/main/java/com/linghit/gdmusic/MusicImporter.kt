package com.linghit.gdmusic

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.documentfile.provider.DocumentFile
import java.util.ArrayDeque

/**
 * 本地音乐导入器：完全脱离 Activity 的后台异步任务。
 *
 * 设计要点：
 *  - 只吃 applicationContext：用户在导入过程中跳到任意页面甚至按 Home，导入照样跑完；
 *  - 进度全部推给 ImportProgress，由 ImportOverlay 在每个页面顶部显示，不再依赖某个页面活着；
 *  - 结果用 applicationContext 弹 Toast，在哪都能看到。
 *
 * 导入性能优化（针对「全部塞进一个扁平目录 + 首次/重复都慢」）：
 *  A 歌词匹配改 O(1) 内存查表，干掉原来每首对父目录 listFiles 的 O(N²)；
 *  B 增量跳过：已存在的歌跳过最贵的标签读取；
 *  C 标签读取轻量化：导入时只读 title/artist/album，duration 交给播放器在播放时取；
 *  D 写盘优化：一次 load + 全程 upsert + 结尾一次性 save，不再每 200 首全量读写 JSON。
 */
object MusicImporter {

    private val main = Handler(Looper.getMainLooper())

    /** 支持的音频格式：常见无损/有损都覆盖；缺歌词完全不影响导入（歌词是可选的） */
    private val AUDIO_EXT = setOf(
        "mp3", "m4a", "flac", "wav", "ogg", "aac", "opus",
        "wma", "ape", "mid", "midi", "amr", "mp4", "m4r",
        "aiff", "aif", "3gp", "caf"
    )

    /** 伴生歌词扩展名（阶段①顺手建索引用） */
    private val LRC_EXT = setOf("lrc", "txt", "krc")

    /** 每积累这么多首就批量 upsert 一次（注：不再每次落盘，落盘统一在结尾 commitImport） */
    private const val CHUNK = 200

    /**
     * 启动一次导入（立刻返回，真正的工作在后台线程）。
     * 重复调用只会再起一个任务，调用方应自己禁用按钮防重复（见 DownloadedActivity）。
     */
    fun start(ctx: Context, treeUri: Uri) {
        val appCtx = ctx.applicationContext
        try {
            appCtx.contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (_: Exception) { /* 少数机型不授予持久权限，本次仍可扫 */ }

        Thread { run(appCtx, treeUri) }.start()
    }

    private fun run(appCtx: Context, treeUri: Uri) {
        val root = DocumentFile.fromTreeUri(appCtx, treeUri)
        val folderName = root?.name ?: "导入"
        val skipped = mutableMapOf<String, Int>()

        // —— 阶段①：扫描，收集音频 + 顺手建歌词索引（都在同一次 listFiles 遍历里，零额外 IPC） ——
        val collected = mutableListOf<Pair<DocumentFile, String>>()
        // 目录path -> (歌词 base 小写 -> 文件)，阶段②用 O(1) 查表，取代每首对父目录 listFiles
        val lyricIndex = mutableMapOf<String, MutableMap<String, DocumentFile>>()
        val queue: ArrayDeque<Pair<DocumentFile, String>> = ArrayDeque()
        if (root != null) queue.add(root to (root.name ?: "导入"))
        ImportProgress.start(folderName)
        while (queue.isNotEmpty()) {
            val (dir, path) = queue.removeFirst()
            val children = runCatching { dir.listFiles() }.getOrNull() ?: continue
            for (child in children) {
                if (child.isDirectory) {
                    queue.add(child to "$path/${child.name}")
                } else if (child.isFile) {
                    val nm = child.name ?: continue
                    val ext = nm.substringAfterLast('.', "").lowercase()
                    if (ext in AUDIO_EXT) collected.add(child to path)
                    else if (ext in LRC_EXT) {
                        // A：歌词顺手收进索引（按父目录 path 归类，与 collected 的 path 一致）
                        val lb = nm.substringBeforeLast('.', nm).lowercase()
                        lyricIndex.getOrPut(path) { mutableMapOf() }[lb] = child
                    }
                    else if (ext.isNotBlank()) skipped[ext] = (skipped[ext] ?: 0) + 1
                }
            }
        }

        // —— 阶段②：逐文件处理（A 查表 / B 增量 / C 轻量标签 / D 批量写盘） ——
        val total = collected.size
        val session = DownloadStore.beginImport(appCtx)   // 一次 load + 已有 uri 集合（B 用）
        val batch = mutableListOf<DownloadedItem>()   // 累积到 CHUNK 再 upsert（避免结尾巨写）
        val emitBuf = mutableListOf<DownloadedItem>() // 累积到 step 就推给列表（导入哪条显示哪条）
        var lrcFound = 0
        var lrcMissing = 0
        var alreadyCount = 0   // 已存在、跳过读标签的（B）
        // 进度按总量动态步进：总量小（<50）也每首更新，避免进度条卡在 1/N；
        // 总量大则约每 2% 更新一次，既平滑又不至于 UI 刷太频繁
        val step = if (total / 50 > 1) total / 50 else 1
        for ((i, pair) in collected.withIndex()) {
            val (file, path) = pair
            val nm = file.name ?: continue
            val base = nm.substringBeforeLast('.')
            val uriStr = file.uri.toString()
            val already = uriStr in session.have   // B：是否已在索引里
            var (name, artist) = DownloadStore.parseAudioName(nm)
            var album = ""
            if (!already) {
                // 优先读文件真实标签（MP3 ID3 / FLAC 等），比按文件名猜准得多（C：已不含 duration/bitrate）
                runCatching {
                    AudioTagUtil.readMeta(appCtx, uriStr)
                }.getOrNull()?.let { m ->
                    if (m.title.isNotBlank()) name = m.title
                    if (m.artist.isNotBlank()) artist = m.artist
                    album = m.album
                }
            } else {
                alreadyCount++
            }
            val song = Song("", name, artist, album, "", "", "", uriStr)
            // A：歌词 O(1) 内存查表，取代每首对父目录 listFiles 的 O(N²)
            val lrcDoc = lookupLrc(lyricIndex[path], base)
            if (lrcDoc != null) lrcFound++ else lrcMissing++
            val item = DownloadedItem(
                song.key(), song, file.uri,
                path.ifBlank { root?.name ?: "导入" },
                "manual", file.length(),
                lrcDoc?.uri?.toString() ?: ""
            )
            batch.add(item)
            emitBuf.add(item)
            // 批量 upsert（不落盘），落盘统一在结尾 commitImport（D）
            if (batch.size >= CHUNK) {
                DownloadStore.addScanned(session, batch)
                batch.clear()
            }
            // 列表实时更新：每 step 首推一批给 DownloadedActivity（无订阅页面时自动丢弃）
            if (emitBuf.size >= step || i == total - 1) {
                ImportProgress.emitItems(emitBuf.toList())
                emitBuf.clear()
            }
            if (i % step == 0 || i == total - 1) {
                ImportProgress.progress("正在导入 ${i + 1} / $total 首…", i + 1, total)
            }
        }
        if (batch.isNotEmpty()) DownloadStore.addScanned(session, batch)
        DownloadStore.commitImport(appCtx, session)   // D：一次性落盘

        val newCount = collected.size - alreadyCount
        Log.d(
            "DlImport",
            "importFolder=$folderName 扫描=${collected.size} 新增=$newCount 已存在=$alreadyCount " +
                "匹配歌词=$lrcFound 无歌词=$lrcMissing 跳过非音频=$skipped " +
                "(若扫描=0 且 跳过=空，说明系统没枚举到文件——多为 Android 11+ 该文件夹的 SAF 权限/选取问题)"
        )

        val msg = if (collected.isEmpty())
            "在「$folderName」里没找到可导入的音频\n（支持 mp3/m4a/flac/wav/ogg/aac/opus/wma/ape 等；无歌词也能导入）"
        else if (alreadyCount > 0)
            "已导入 $newCount 首，已存在 $alreadyCount 首"
        else "已导入 ${collected.size} 首本地音乐"
        ImportProgress.end()
        main.post { Toast.makeText(appCtx, msg, Toast.LENGTH_LONG).show() }
    }

    /**
     * 在内存歌词索引里找伴生歌词（O(目录文件数) 内存查找，无 SAF IPC）：
     *  ① 精确：<歌名小写> 命中；
     *  ② 宽松：key 以歌名开头（覆盖「歌名 - 歌词.lrc」）；
     *  ③ 兜底：key 含歌名。
     * 找不到返回 null —— 没歌词也照样导入，播放时再按内嵌/在线递补。
     */
    private fun lookupLrc(dirMap: Map<String, DocumentFile>?, base: String): DocumentFile? {
        if (dirMap == null || base.isBlank()) return null
        val lb = base.lowercase()
        dirMap[lb]?.let { return it }
        dirMap.entries.firstOrNull { it.key.startsWith(lb) }?.value?.let { return it }
        dirMap.entries.firstOrNull { it.key.contains(lb) }?.value?.let { return it }
        return null
    }
}
