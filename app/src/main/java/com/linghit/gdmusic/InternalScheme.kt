package com.linghit.gdmusic

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.MediaStore
import android.widget.Toast

/**
 * App 内部伪协议路由。
 *
 * 为什么需要：系统相册/相机是 App 不是网页，普通 URL 打不开；而在
 * 悬浮球 / 首页快捷位 / 链接页配「自定义链接」时只能填地址。
 * 于是约定 app:// 前缀作为内部动作白名单——
 *   app://gallery           打开系统相册（荣耀/华为/其他安卓通吃）
 *   app://camera            打开系统相机
 *   app://com.xxx.yyy       直接启动指定包名的 App（如 app://com.tencent.mm）
 *
 * 相册/相机包名依次试（荣耀 MagicOS 图库是 com.hihonor.photos，老机型/
 * 其他 ROM 各不相同），全部失败降级系统图片选择器（ACTION_PICK），
 * 再不行 ACTION_GET_CONTENT，保证一定能进「相册选图」界面。
 */
object InternalScheme {

    const val GALLERY = "app://gallery"
    const val CAMERA = "app://camera"

    /** 白名单（配置页校验也用它） */
    val SUPPORTED = listOf(GALLERY, CAMERA)

    /** 是否是 app:// 内部协议（不管动作认不认识，都由本模块接管，不再走深链/浏览器） */
    fun isInternal(url: String?): Boolean =
        !url.isNullOrBlank() && url.trim().startsWith("app://", true)

    /**
     * 尝试执行内部动作。
     * @return true = 已处理（调用方不要再走深链/浏览器逻辑）；
     *         false = 动作不认识（调用方按普通链接继续）
     */
    fun open(ctx: Context, url: String): Boolean {
        val u = LinkRouter.normalize(url).lowercase()
        return when {
            u == GALLERY -> openGallery(ctx)
            u == CAMERA -> openCamera(ctx)
            // app://com.xxx.yyy：host 带点号视为包名，直接启动指定 App
            u.removePrefix("app://").contains('.') ->
                openPackage(ctx, u.removePrefix("app://"))
            else -> false
        }
    }

    // ---- 直接启动指定包名的 App ----
    private fun openPackage(ctx: Context, pkg: String): Boolean {
        val intent = ctx.packageManager.getLaunchIntentForPackage(pkg)
        if (intent == null) {
            toast(ctx, "未安装该应用：$pkg")
            return true
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return if (tryStart(ctx, intent)) true
        else {
            toast(ctx, "无法打开：$pkg")
            true
        }
    }

    // ---- 相册：包名逐个试，降级 ACTION_PICK → GET_CONTENT ----
    private fun openGallery(ctx: Context): Boolean {
        val pkgs = listOf(
            "com.hihonor.photos",      // 荣耀 MagicOS（用户实机确认）
            "com.hihonor.gallery",     // 荣耀旧版
            "com.huawei.photos",       // 华为 HarmonyOS 4+
            "com.android.gallery",     // 老荣耀/华为/部分国产 ROM
            "com.android.gallery3d",   // AOSP 系
            "com.miui.gallery"         // 小米（备用）
        )
        for (p in pkgs) {
            val intent = ctx.packageManager.getLaunchIntentForPackage(p) ?: continue
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (tryStart(ctx, intent)) return true
        }
        // 降级①：系统图片选择器（各 App 「选图片」弹出的那个界面）
        val pick = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
            .apply { type = "image/*"; addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        if (tryStart(ctx, pick)) return true
        // 降级②：内容选择器
        val get = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "image/*"
            addCategory(Intent.CATEGORY_OPENABLE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (tryStart(ctx, get)) return true
        toast(ctx, "未找到相册应用")
        return true
    }

    // ---- 相机：同样多包名 + 降级 ----
    private fun openCamera(ctx: Context): Boolean {
        val pkgs = listOf(
            "com.hihonor.camera",
            "com.huawei.camera",
            "com.android.camera",
            "com.android.camera2"
        )
        for (p in pkgs) {
            val intent = ctx.packageManager.getLaunchIntentForPackage(p) ?: continue
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (tryStart(ctx, intent)) return true
        }
        val shoot = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (tryStart(ctx, shoot)) return true
        val get = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "image/*"
            addCategory(Intent.CATEGORY_OPENABLE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (tryStart(ctx, get)) return true
        toast(ctx, "未找到相机应用")
        return true
    }

    private fun tryStart(ctx: Context, intent: Intent): Boolean =
        try {
            ctx.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            false
        } catch (e: Exception) {
            // 荣耀某些 ROM 对 NEW_TASK + getLaunchIntent 组合偶发
            // SecurityException，静默换下一个候选
            false
        }

    private fun toast(ctx: Context, msg: String) {
        Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
    }
}
