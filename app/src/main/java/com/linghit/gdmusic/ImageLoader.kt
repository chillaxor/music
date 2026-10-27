package com.linghit.gdmusic

import android.graphics.BitmapFactory
import android.widget.ImageView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 轻量图片加载器：OkHttp 拉取远程封面/台标，解码后塞进 ImageView。
 * 用 tag 防止列表复用时旧图错位。无缓存（封面体积不大，且避免引入 Glide）。
 */
object ImageLoader {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun load(iv: ImageView, url: String?) {
        if (url.isNullOrBlank()) { iv.setImageDrawable(null); return }
        iv.tag = url
        scope.launch {
            try {
                val bytes = client.newCall(
                    Request.Builder().url(url).header("User-Agent", "GDMusicPlayer/3.0").build()
                ).execute().use { it.body?.bytes() }
                val bmp = bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                if (iv.tag == url) {
                    launch(Dispatchers.Main) { if (bmp != null) iv.setImageBitmap(bmp) else iv.setImageDrawable(null) }
                }
            } catch (e: Exception) {
                if (iv.tag == url) launch(Dispatchers.Main) { iv.setImageDrawable(null) }
            }
        }
    }
}
