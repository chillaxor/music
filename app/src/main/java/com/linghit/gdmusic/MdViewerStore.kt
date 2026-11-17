package com.linghit.gdmusic

import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.net.URLDecoder
import java.nio.charset.Charset

/**
 * 本地 MD 查看器的数据层：SAF 目录授权 → 递归扫描 .md → 读正文 → 改写图片路径。
 *
 * 为什么不直接读 /sdcard 路径：targetSdk 34 + Android 11+ 分区存储下，App 没有
 * READ_EXTERNAL_STORAGE 也读不到任意公共目录文件；SAF 选一次目录拿到持久授权，
 * 之后往目录里扔新 md / 图片都能直接读，和「每日一图」「离线下载」同一套范式。
 */
object MdViewerStore {
    private const val PREFS = "md_viewer"
    private const val KEY_TREE = "tree_uri"
    private const val MAX_MD = 8 * 1024 * 1024
    private const val MAX_IMG = 24 * 1024 * 1024

    data class MdFile(
        val uri: String,
        val relPath: String,
        val name: String,
        val size: Long,
        val modified: Long,
        /** 扫描时持有的目录 DocumentFile，仅内存用，不传给 JS；图片相对路径靠它解析 */
        val dirDoc: DocumentFile? = null
    )

    fun treeUri(ctx: Context): String? =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_TREE, null)

    fun setTreeUri(ctx: Context, uri: Uri?) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            if (uri == null) remove(KEY_TREE) else putString(KEY_TREE, uri.toString())
        }.apply()
    }

    fun folderName(ctx: Context): String {
        val s = treeUri(ctx) ?: return ""
        val doc = runCatching { DocumentFile.fromTreeUri(ctx, Uri.parse(s)) }.getOrNull()
        return doc?.name ?: s.substringAfterLast('/').substringBefore('?')
    }

    /** 递归扫描授权目录下所有 .md / .markdown，按相对路径排序；跳过隐藏目录 */
    fun scan(ctx: Context): List<MdFile> {
        val s = treeUri(ctx) ?: return emptyList()
        val root = runCatching { DocumentFile.fromTreeUri(ctx, Uri.parse(s)) }.getOrNull() ?: return emptyList()
        val out = mutableListOf<MdFile>()
        val queue = ArrayDeque<Pair<DocumentFile, String>>()
        queue.add(root to "")
        // 防御异常目录树（软链接/环），正常笔记库远到不了这个量级
        var visited = 0
        while (queue.isNotEmpty() && visited++ < 20000) {
            val (dir, relDir) = queue.removeFirst()
            val children = runCatching { dir.listFiles() }.getOrNull() ?: continue
            for (child in children) {
                val nm = child.name ?: continue
                if (child.isDirectory) {
                    if (nm.startsWith(".")) continue // .obsidian / .git / .trash
                    queue.add(child to if (relDir.isEmpty()) nm else "$relDir/$nm")
                } else if (child.isFile) {
                    val ext = nm.substringAfterLast('.', "").lowercase()
                    if (ext == "md" || ext == "markdown") {
                        out.add(
                            MdFile(
                                uri = child.uri.toString(),
                                relPath = if (relDir.isEmpty()) nm else "$relDir/$nm",
                                name = nm,
                                size = child.length(),
                                modified = child.lastModified(),
                                dirDoc = dir
                            )
                        )
                    }
                }
            }
        }
        return out.sortedBy { it.relPath.lowercase() }
    }

    /** 读 md 正文，UTF-8 失败按 GB18030 兜底（中文老笔记常见），统一换行为 \n */
    fun read(ctx: Context, uriStr: String): String? {
        val bytes = ctx.contentResolver.openInputStream(Uri.parse(uriStr))?.use { it.readBytes() }
            ?: return null
        if (bytes.size > MAX_MD) {
            return "（文件超过 8MB，只显示前 8MB）\n\n" + decode(bytes.copyOf(MAX_MD))
        }
        return decode(bytes)
    }

    data class FrontMatter(val meta: Map<String, String>, val body: String)

    /**
     * 拆出文首 YAML frontmatter（--- 包裹的 key: value）。
     * 微信文章收藏等工具把来源/标题/时间写在头部，直接交给 marked 会把开头的
     * --- 当成分割线、属性当正文，必须拆成结构化数据单独展示。
     */
    fun parseFrontmatter(raw: String): FrontMatter {
        val text = raw.removePrefix("\uFEFF")
        val lines = text.lines()
        if (lines.firstOrNull()?.trim() != "---") return FrontMatter(emptyMap(), raw)
        var end = -1
        for (i in 1 until lines.size) {
            if (lines[i].trim() == "---") { end = i; break }
        }
        if (end < 0) return FrontMatter(emptyMap(), raw)
        val meta = linkedMapOf<String, String>()
        for (i in 1 until end) {
            val line = lines[i]
            val pos = line.indexOf(':')
            if (pos <= 0) continue
            val key = line.substring(0, pos).trim()
            var value = line.substring(pos + 1).trim()
            if (value.length >= 2 &&
                ((value.first() == '"' && value.last() == '"') ||
                 (value.first() == '\'' && value.last() == '\''))
            ) value = value.substring(1, value.length - 1)
            if (key.isNotEmpty()) meta[key] = value
        }
        return FrontMatter(meta, lines.subList(end + 1, lines.size).joinToString("\n"))
    }

    private fun decode(bytes: ByteArray): String {
        val bad = '\uFFFD'
        val utf8 = bytes.decodeToString()
        val badUtf8 = utf8.count { it == bad }
        if (badUtf8 == 0) return utf8.replace("\r\n", "\n")
        val gbk = runCatching { String(bytes, Charset.forName("GB18030")) }.getOrNull()
        val text = if (gbk != null && gbk.count { it == bad } < badUtf8) gbk else utf8
        return text.replace("\r\n", "\n")
    }

    /** Obsidian 的 ![[图片.png|300]] 先归一成标准 markdown 图片，再逐条改写 src */
    private val wikiImage = Regex("!\\[\\[([^\\]|]+)(?:\\|[^\\]]*)?\\]\\]")
    private val mdImage = Regex("!\\[([^\\]]*)\\]\\(\\s*([^)]+?)\\s*\\)")

    fun rewriteImages(ctx: Context, baseDir: DocumentFile?, markdown: String): String {
        var out = wikiImage.replace(markdown) { m -> "![](${m.groupValues[1].trim()})" }
        out = mdImage.replace(out) { m ->
            val alt = m.groupValues[1]
            // 去掉可选 title：![](a.png "说明")
            val path = m.groupValues[2].trim().replace(Regex("\\s+[\"'].*[\"']$"), "").trim()
            "![$alt](${toImageUrl(ctx, baseDir, path)})"
        }
        return out
    }

    private fun toImageUrl(ctx: Context, baseDir: DocumentFile?, raw: String): String {
        if (raw.isEmpty()) return raw
        if (raw.startsWith("http://", true) || raw.startsWith("https://", true) ||
            raw.startsWith("data:", true) || raw.startsWith("blob:", true)
        ) return raw

        // 图片链接可能带锚点/查询串（Obsidian 的 |300 已在上面剥掉）
        val stripped = raw.substringBefore('#').substringBefore('?')
        val path = runCatching { URLDecoder.decode(stripped, "UTF-8") }.getOrDefault(stripped).trim()

        if (path.startsWith("content://", true)) return proxy("img", path)
        if (path.startsWith("file://", true)) {
            val f = Uri.parse(path).path?.let { File(it) }
            if (f?.exists() == true) return proxy("img", Uri.fromFile(f).toString())
        }
        if (path.startsWith("/")) {
            val f = File(path)
            if (f.exists()) return proxy("img", Uri.fromFile(f).toString())
        }

        // 相对路径：以 md 所在目录为基准，支持 ../ 和 ./，大小写不敏感兜底
        var dir = baseDir
        val parts = path.split('/').filter { it.isNotEmpty() && it != "." }
        for (part in parts) {
            val current = dir ?: break
            if (part == "..") {
                dir = runCatching { current.parentFile }.getOrNull()
                continue
            }
            val found = runCatching { current.findFile(part) }.getOrNull()
                ?: runCatching {
                    current.listFiles().firstOrNull { it.name?.equals(part, ignoreCase = true) == true }
                }.getOrNull()
            dir = if (found?.isDirectory == true) found else if (found != null && part == parts.last()) {
                return proxy("img", found.uri.toString())
            } else null
        }
        return proxy("missing", path)
    }

    /** 图片走本地代理 URL，WebViewClient 拦截后从 SAF 读出字节，不经网络 */
    private fun proxy(kind: String, payload: String): String {
        val b64 = Base64.encodeToString(
            payload.toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
        return "https://mdviewer.local/$kind/$b64"
    }

    fun unproxy(payload: String): String {
        val padded = payload + "=".repeat((4 - payload.length % 4) % 4)
        return String(Base64.decode(padded, Base64.URL_SAFE), Charsets.UTF_8)
    }

    fun isProxy(url: String): Boolean = url.contains("mdviewer.local/")

    /** 读代理图片，返回 字节 + 文件名（用于推断 MIME） */
    fun readImage(ctx: Context, uriStr: String): Pair<ByteArray?, String> = runCatching {
        val uri = Uri.parse(uriStr)
        if (uri.scheme == "file") {
            val f = File(uri.path ?: "")
            if (!f.exists() || f.length() > MAX_IMG) return null to f.name
            f.readBytes() to f.name
        } else {
            val name = DocumentFile.fromSingleUri(ctx, uri)?.name ?: "image"
            val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            (if (bytes != null && bytes.size <= MAX_IMG) bytes else null) to name
        }
    }.getOrDefault(null to "image")

    fun mimeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "bmp" -> "image/bmp"
        "svg" -> "image/svg+xml"
        "heic", "heif" -> "image/heic"
        else -> "application/octet-stream"
    }

    /** 找不到的图片不显示碎图，给一张带原路径提示的 SVG */
    fun missingSvg(path: String): String {
        val safe = path.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").take(120)
        return """<svg xmlns="http://www.w3.org/2000/svg" width="420" height="88" viewBox="0 0 420 88"><rect width="420" height="88" rx="10" fill="#3a2e2e" stroke="#a06a6a"/><text x="14" y="34" fill="#ffd7d7" font-size="15" font-family="sans-serif">图片没找到</text><text x="14" y="60" fill="#e0aaaa" font-size="12" font-family="sans-serif">$safe</text></svg>"""
    }
}
