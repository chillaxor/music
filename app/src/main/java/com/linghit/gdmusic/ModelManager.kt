package com.linghit.gdmusic

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 端侧模型文件管理。
 *
 * 模型放哪：context.filesDir/models/（内部存储，无需任何存储权限）。
 * ⚠️ 千万别把 .litertlm 塞进 assets 或打进 APK —— 几百 MB 会把包撑爆，
 *    官方文档明确要求模型文件放在设备文件系统上。
 *
 * 两种获取方式：
 *  1. 应用内下载：从 HuggingFace 直连拉（见 MODEL_CATALOG 的 URL），带进度回调。
 *  2. 手动导入：SAF 选一个已经下好的 .litertlm 文件，复制进内部存储。
 */
object ModelManager {

    /** 可选模型清单（litert-community 官方已转换好的版本） */
    data class ModelSpec(
        val id: String,
        val displayName: String,
        val repo: String,
        val fileName: String,
        val approxSizeMb: Int,
        val note: String
    ) {
        /** 候选下载源，按顺序尝试（国内直连 HF 常常连不上，必须有镜像兜底） */
        fun urls(): List<String> = listOf(
            // ① HuggingFace 官方（最快，但国内多数网络环境直连失败/极慢）
            "https://huggingface.co/$repo/resolve/main/$fileName",
            // ② hf-mirror.com 国内镜像（HF 官方推荐，文件名路径与官方完全一致）
            "https://hf-mirror.com/$repo/resolve/main/$fileName",
            // ③ 魔搭 ModelScope 镜像（阿里，国内直连快，文件与 HF 同源同体积）
            "https://www.modelscope.cn/api/v1/models/$repo/repo?Revision=master&FilePath=$fileName"
        )
    }

    val CATALOG = listOf(
        ModelSpec(
            id = "qwen3_06b",
            displayName = "Qwen3-0.6B",
            repo = "litert-community/Qwen3-0.6B",
            fileName = "Qwen3-0.6B.litertlm",
            approxSizeMb = 584,
            note = "最快档 · 起标题/打标签/格式转换"
        ),
        ModelSpec(
            id = "qwen3_17b",
            displayName = "Qwen3-1.7B",
            repo = "litert-community/Qwen3-1.7B",
            // ⚠️ 文件名是连字符 Qwen3-1.7B_…，不是下划线 Qwen3_1.7B_…
            //   （仓库里另有 Qwen3_1.7B.litertlm 那是 2GB 的 wi8 版本，不是这个）
            fileName = "Qwen3-1.7B_dynamic_wi4b32_afp32.litertlm",
            approxSizeMb = 932,
            note = "均衡档 · 改写正文，INT4 量化"
        ),
        ModelSpec(
            id = "qwen3_4b",
            displayName = "Qwen3-4B",
            repo = "litert-community/Qwen3-4B",
            fileName = "qwen3_4b_mixed_int4.litertlm",
            approxSizeMb = 2536,
            note = "主力档 · 中文质量最好，16GB 内存可跑"
        )
    )

    fun specById(id: String): ModelSpec? = CATALOG.firstOrNull { it.id == id }

    // ---------- 路径 ----------

    private fun dir(ctx: Context): File = File(ctx.filesDir, "models").apply { mkdirs() }

    fun modelDir(ctx: Context): String = dir(ctx).absolutePath

    fun fileOf(ctx: Context, spec: ModelSpec): File = File(dir(ctx), spec.fileName)

    /** 已下载的模型列表 */
    fun installed(ctx: Context): List<File> =
        dir(ctx).listFiles { f -> f.isFile && f.name.endsWith(".litertlm") }?.sortedBy { it.name } ?: emptyList()

    fun isInstalled(ctx: Context, spec: ModelSpec): Boolean {
        val f = fileOf(ctx, spec)
        // 大小校验：至少要达到标称体积的 80%，防止半截文件被当成完整模型
        return f.exists() && f.length() > spec.approxSizeMb.toLong() * 1024L * 1024L * 0.8
    }

    // ---------- 应用内下载 ----------

    /**
     * 下载模型到内部存储。走 .part 临时文件，支持中断后重下（不续传，够用）。
     *
     * @param onProgress (已下载字节, 总字节, 百分比)
     */
    fun download(
        ctx: Context,
        spec: ModelSpec,
        onProgress: (Long, Long, Int) -> Unit
    ): Result<File> {
        val target = fileOf(ctx, spec)
        val tmp = File(target.parentFile, spec.fileName + ".part")

        val client = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()

        // 依次尝试各下载源：国内直连 huggingface.co 十有八九连不上（实测报
        // "Failed to connect to huggingface.co"），必须有镜像兜底。
        val errors = mutableListOf<String>()
        for (url in spec.urls()) {
            try {
                downloadFrom(client, url, tmp, onProgress)
                // 大小校验：下载不完整就别覆盖已有模型
                val minBytes = spec.approxSizeMb.toLong() * 1024L * 1024L * 0.8
                if (tmp.length() < minBytes) {
                    tmp.delete()
                    error("下载不完整（${tmp.length() / 1024 / 1024}MB，预期约 ${spec.approxSizeMb}MB）")
                }
                if (target.exists()) target.delete()
                if (!tmp.renameTo(target)) {
                    // 某些文件系统 rename 跨挂载会失败，退化为拷贝
                    tmp.copyTo(target, overwrite = true)
                    tmp.delete()
                }
                return Result.success(target)
            } catch (t: Throwable) {
                tmp.delete()
                errors += "${hostOf(url)}: ${t.message}"
            }
        }
        return Result.failure(Exception("所有下载源都失败\n" + errors.joinToString("\n")))
    }

    private fun hostOf(url: String): String = runCatching {
        java.net.URI(url).host
    }.getOrDefault(url)

    /** 单源下载，支持断点续传（.part 已存在则带 Range 头续下） */
    private fun downloadFrom(
        client: OkHttpClient,
        url: String,
        tmp: File,
        onProgress: (Long, Long, Int) -> Unit
    ) {
        val resumeAt = if (tmp.exists()) tmp.length() else 0L
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "GDMusicPlayer/3.0")
            .apply { if (resumeAt > 0) header("Range", "bytes=$resumeAt-") }
            .build()

        client.newCall(req).execute().use { resp ->
            // 206 = 断点续传成功；200 = 服务端不支持续传，从头下
            val appending = resp.code == 206
            if (!resp.isSuccessful) error("HTTP ${resp.code}")
            val bodyTotal = resp.body?.contentLength() ?: -1L
            val total = if (bodyTotal > 0) bodyTotal + (if (appending) resumeAt else 0L) else -1L

            resp.body?.byteStream()?.use { input ->
                java.io.FileOutputStream(tmp, appending).use { out ->
                    val buf = ByteArray(64 * 1024)
                    var sum = resumeAt
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        sum += n
                        val pct = if (total > 0) (sum * 100 / total).toInt() else 0
                        onProgress(sum, total, pct)
                    }
                }
            } ?: error("响应体为空")
        }
    }

    // ---------- 手动导入（SAF） ----------

    /**
     * 从 SAF 选中的 Uri 复制模型到内部存储。
     * 用 contentResolver 流式拷贝，不把整个文件读进内存。
     */
    fun importFromUri(ctx: Context, uri: Uri): Result<File> = runCatching {
        val name = queryDisplayName(ctx, uri) ?: "imported.litertlm"
        val safeName = if (name.endsWith(".litertlm")) name else "$name.litertlm"
        val target = File(dir(ctx), safeName)
        val tmp = File(dir(ctx), "$safeName.part")

        ctx.contentResolver.openInputStream(uri)?.use { input ->
            tmp.outputStream().use { out ->
                input.copyTo(out, 64 * 1024)
            }
        } ?: error("无法读取所选文件")

        if (target.exists()) target.delete()
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
        target
    }

    private fun queryDisplayName(ctx: Context, uri: Uri): String? = runCatching {
        ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
        }
    }.getOrNull()

    // ---------- 删除 ----------

    fun delete(ctx: Context, file: File): Boolean = runCatching {
        // 只允许删 models 目录下的文件，防止误删
        if (file.parentFile?.absolutePath != dir(ctx).absolutePath) return false
        file.delete()
    }.getOrDefault(false)

    /** 清理所有模型（释放空间） */
    fun clearAll(ctx: Context): Long {
        var freed = 0L
        installed(ctx).forEach {
            freed += it.length()
            it.delete()
        }
        return freed
    }

    /** 已占用的存储（字节） */
    fun usedBytes(ctx: Context): Long =
        installed(ctx).sumOf { it.length() }
}
