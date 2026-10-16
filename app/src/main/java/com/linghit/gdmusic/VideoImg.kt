package com.linghit.gdmusic

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.widget.ImageView
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * 极简网络图加载（历史页海报用）：LruCache + 固定线程池 + 二次采样解码，
 * 不引第三方库。加载期间显示占位；解码失败就留占位，不重试（列表会反复回收）。
 */
object VideoImg {
    private val pool = Executors.newFixedThreadPool(2)
    private val mem = android.util.LruCache<String, Bitmap>(6 * 1024 * 1024)

    fun into(iv: ImageView, url: String?, placeholder: Int) {
        iv.setImageResource(placeholder)
        val u = url?.trim()
        if (u.isNullOrEmpty()) return
        mem.get(u)?.let { iv.setImageBitmap(it); return }
        iv.tag = u
        pool.execute {
            val bm = decode(u) ?: return@execute
            mem.put(u, bm)
            iv.post { if (iv.tag == u) iv.setImageBitmap(bm) }
        }
    }

    /** 同一连接读两次流不可靠，边界与实图各开一次连接 */
    private fun decode(u: String): Bitmap? {
        val w = try {
            open(u).use { s ->
                BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                    BitmapFactory.decodeStream(s, null, this)
                }.outWidth
            }
        } catch (e: Exception) { return null }
        if (w <= 0) return null
        val opts = BitmapFactory.Options().apply { inSampleSize = sampleOf(w) }
        return try {
            open(u).use { s -> BitmapFactory.decodeStream(s, null, opts) }
        } catch (e: Exception) { null }
    }

    private fun open(u: String): InputStream {
        val cn = (URL(u).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000; readTimeout = 8000; doInput = true
            instanceFollowRedirects = true
        }
        if (cn.responseCode !in 200..299) { cn.disconnect(); throw java.io.IOException("http ${cn.responseCode}") }
        return cn.inputStream
    }

    /** 缩到 ~200px 宽就够列表小图用 */
    private fun sampleOf(w: Int): Int {
        if (w <= 200) return 1
        var s = 1
        while (w / s > 200) s *= 2
        return s
    }
}
