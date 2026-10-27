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
 *  - 分块写盘（每 200 首落一次），避免结尾一次性写几千条把主线程/IO 卡住；
 *  - 结果用 applicationContext 弹 Toast，在哪都能看到。
 */
object MusicImporter {

    private val main = Handler(Looper.getMainLooper())

    /** 支持的音频格式：常见无损/有损都覆盖；缺歌词完全不影响导入（歌词是可选的） */
    private val AUDIO_EXT = setOf(
        "mp3", "m4a", "flac", "wav", "ogg", "aac", "opus",
        "wma", "ape", "mid", "midi", "amr", "mp4", "m4r",
        "aiff", "aif", "3gp", "caf"
    )

    /** 每积累这么多首就落一次盘（避免结尾一次性巨写） */
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

        // —— 阶段①：扫描，只收集音频文件 + 统计非音频后缀，不读标签（快） ——
        val collected = mutableListOf<Pair<DocumentFile, String>>()
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
                    else if (ext.isNotBlank()) skipped[ext] = (skipped[ext] ?: 0) + 1
                }
            }
        }

        // —— 阶段②：逐文件读标签 + 匹配歌词 + 分块写盘（慢，进度条切 determinate） ——
        val total = collected.size
        val batch = mutableListOf<DownloadedItem>()   // 累积到 CHUNK 再落盘（避免结尾巨写）
        val emitBuf = mutableListOf<DownloadedItem>() // 累积到 step 就推给列表（导入哪条显示哪条）
        var lrcFound = 0
        var lrcMissing = 0
        // 进度按总量动态步进：总量小（<50）也每首更新，避免进度条卡在 1/N；
        // 总量大则约每 2% 更新一次，既平滑又不至于 UI 刷太频繁
        val step = if (total / 50 > 1) total / 50 else 1
        for ((i, pair) in collected.withIndex()) {
            val (file, path) = pair
            val nm = file.name ?: continue
            val base = nm.substringBeforeLast('.')
            var (name, artist) = DownloadStore.parseAudioName(nm)
            var album = ""
            // 优先读文件真实标签（MP3 ID3 / FLAC 等），比按文件名猜准得多
            runCatching {
                AudioTagUtil.readMeta(appCtx, file.uri.toString())
            }.getOrNull()?.let { m ->
                if (m.title.isNotBlank()) name = m.title
                if (m.artist.isNotBlank()) artist = m.artist
                album = m.album
            }
            val song = Song("", name, artist, album, "", "", "", file.uri.toString())
            // 同名伴生歌词：宽容匹配（.lrc / .lrc.txt / .txt / .krc 等写法都认）；
            // 没有歌词文件时留空，照常导入，播放时按内嵌/在线歌词递补
            val parent = file.parentFile ?: root
            val lrcDoc = if (parent != null) findLrcIn(parent, base) else null
            if (lrcDoc != null) lrcFound++ else lrcMissing++
            val item = DownloadedItem(
                song.key(), song, file.uri,
                path.ifBlank { root?.name ?: "导入" },
                "manual", file.length(),
                lrcDoc?.uri?.toString() ?: ""
            )
            batch.add(item)
            emitBuf.add(item)
            // 落盘照旧分块（每 CHUNK 首一次），不拖慢 IO
            if (batch.size >= CHUNK) {
                DownloadStore.addScanned(appCtx, batch)
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
        if (batch.isNotEmpty()) DownloadStore.addScanned(appCtx, batch)
        if (emitBuf.isNotEmpty()) ImportProgress.emitItems(emitBuf.toList())

        Log.d(
            "DlImport",
            "importFolder=$folderName 导入=${collected.size} 匹配到歌词=$lrcFound 无歌词=$lrcMissing 跳过非音频=$skipped " +
                "(若导入=0 且 跳过=空，说明系统没枚举到文件——多为 Android 11+ 该文件夹的 SAF 权限/选取问题)"
        )

        val msg = if (collected.isEmpty())
            "在「$folderName」里没找到可导入的音频\n（支持 mp3/m4a/flac/wav/ogg/aac/opus/wma/ape 等；无歌词也能导入）"
        else "已导入 ${collected.size} 首本地音乐"
        ImportProgress.end()
        main.post { Toast.makeText(appCtx, msg, Toast.LENGTH_LONG).show() }
    }

    /**
     * 在音频同目录里找它的伴生歌词，尽量宽容（很多人从网上下的歌词文件名不规整）：
     *  ① 精确：<歌名>.lrc / <歌名>.lrc.txt / <歌名>.txt / <歌名>.krc（含小写变体）
     *  ② 兜底：目录下「文件名以歌名开头」且以 .lrc/.txt/.krc 结尾的任意文件
     *     （覆盖「歌名 - 歌词.lrc」「歌名( live ).txt」这类写法）
     * 找不到返回 null —— 没歌词也照样导入，播放时再按内嵌/在线递补。
     */
    private fun findLrcIn(dir: DocumentFile, base: String): DocumentFile? {
        if (base.isBlank()) return null
        val lb = base.lowercase()
        for (n in listOf("$base.lrc", "$lb.lrc", "$base.lrc.txt", "$lb.lrc.txt",
                "$base.txt", "$lb.txt", "$base.krc", "$lb.krc")) {
            runCatching { dir.findFile(n) }?.getOrNull()?.let { return it }
        }
        val files = runCatching { dir.listFiles() }.getOrNull() ?: return null
        val isLrc = { n: String -> n.endsWith(".lrc") || n.endsWith(".txt") || n.endsWith(".krc") }
        files.firstOrNull { f ->
            if (!f.isFile) return@firstOrNull false
            val n = f.name?.lowercase() ?: return@firstOrNull false
            n.startsWith(lb) && isLrc(n)
        }?.let { return it }
        return files.firstOrNull { f ->
            if (!f.isFile) return@firstOrNull false
            val n = f.name?.lowercase() ?: return@firstOrNull false
            n.contains(lb) && isLrc(n)
        }
    }
}
