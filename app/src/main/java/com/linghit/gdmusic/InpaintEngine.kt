package com.linghit.gdmusic

import android.graphics.Bitmap
import kotlin.collections.ArrayDeque
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 本地图像修复引擎（纯 Kotlin，无 OpenCV / 无 native 依赖）。
 *
 * 思路（回答"涂完跟原图不协调"）：
 *   1) 扩散填充：Jacobi 松弛，从边界向内传播已知色——纯色背景(如全红)完美补回原色。
 *   2) 局部颜色/光照匹配：把填充区的均值方差对齐到外环，消除色偏。
 *   3) 软边羽化合成：距离变换做 alpha 渐变，原图↔修复图按 alpha 混合，消硬边缝。
 *   4) 泊松梯度域融合(可选)：让修复区梯度跟随填充、边界钉死原图，进一步消纹理缝。
 *
 * [mask] 语义：红色通道 > 127 的像素 = 需修复区域（与 BrushView 的白色笔触对应）。
 */
object InpaintEngine {

    private fun r(p: Int) = (p shr 16) and 0xFF
    private fun g(p: Int) = (p shr 8) and 0xFF
    private fun b(p: Int) = p and 0xFF
    private fun rgb(rr: Int, gg: Int, bb: Int) =
        (0xFF shl 24) or (rr shl 16) or (gg shl 8) or bb

    fun remove(src: Bitmap, mask: Bitmap, featherPx: Int = 5, usePoisson: Boolean = true): Bitmap {
        val w = src.width
        val h = src.height
        if (w <= 0 || h <= 0) return src
        if (mask.width != w || mask.height != h) return src.copy(Bitmap.Config.ARGB_8888, false)

        val orig = IntArray(w * h)
        src.getPixels(orig, 0, w, 0, 0, w, h)

        val mp = IntArray(w * h)
        mask.getPixels(mp, 0, w, 0, 0, w, h)
        val bin = BooleanArray(w * h)
        var minX = w; var minY = h; var maxX = -1; var maxY = -1; var any = false
        for (i in mp.indices) {
            val marked = ((mp[i] shr 16) and 0xFF) > 127
            bin[i] = marked
            if (marked) {
                any = true
                val x = i % w; val y = i / w
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
            }
        }
        if (!any) return src.copy(Bitmap.Config.ARGB_8888, false)

        val feather = max(1, featherPx)
        // 填充区 R = 二值区外扩 feather；dist = 到二值区距离(用于羽化 alpha)
        val R = BooleanArray(w * h)
        val dist = IntArray(w * h) { -1 }
        val q = ArrayDeque<Int>()
        for (i in bin.indices) if (bin[i]) { R[i] = true; dist[i] = 0; q.add(i) }
        fun enq(nb: Int, d: Int) {
            if (!R[nb] && dist[nb] < 0) { R[nb] = true; dist[nb] = d + 1; q.add(nb) }
        }
        while (q.isNotEmpty()) {
            val cur = q.removeFirst()
            val d = dist[cur]
            if (d >= feather) continue
            val x = cur % w; val y = cur / w
            if (x > 0) enq(cur - 1, d)
            if (x < w - 1) enq(cur + 1, d)
            if (y > 0) enq(cur - w, d)
            if (y < h - 1) enq(cur + w, d)
        }

        // 1) 扩散填充
        val out = orig.copyOf()
        val filled = BooleanArray(w * h) { !R[it] }
        val bb = max(0, minX - feather); val eb = min(w - 1, maxX + feather)
        val bt = max(0, minY - feather); val et = min(h - 1, maxY + feather)
        val passes = (eb - bb).coerceAtLeast(et - bt) + feather + 4
        repeat(passes) {
            for (y in bt..et) for (x in bb..eb) {
                val i = y * w + x
                if (filled[i]) continue
                var sr = 0; var sg = 0; var sb = 0; var cnt = 0
                if (x > 0 && filled[i - 1]) { sr += r(out[i - 1]); sg += g(out[i - 1]); sb += b(out[i - 1]); cnt++ }
                if (x < w - 1 && filled[i + 1]) { sr += r(out[i + 1]); sg += g(out[i + 1]); sb += b(out[i + 1]); cnt++ }
                if (y > 0 && filled[i - w]) { sr += r(out[i - w]); sg += g(out[i - w]); sb += b(out[i - w]); cnt++ }
                if (y < h - 1 && filled[i + w]) { sr += r(out[i + w]); sg += g(out[i + w]); sb += b(out[i + w]); cnt++ }
                if (cnt > 0) {
                    out[i] = rgb(sr / cnt, sg / cnt, sb / cnt)
                    filled[i] = true
                }
            }
        }

        // 2) 局部颜色/光照匹配：填充区均值方差 → 对齐外环(紧贴 R 外圈)
        var fr = 0f; var fg = 0f; var fb = 0f; var rc = 0
        var mr = 0f; var mg = 0f; var mb = 0f; var mc = 0
        for (i in R.indices) {
            val x = i % w; val y = i / w
            val ring = !R[i] && ((x > 0 && R[i - 1]) || (x < w - 1 && R[i + 1]) ||
                    (y > 0 && R[i - w]) || (y < h - 1 && R[i + w]))
            if (R[i]) { mr += r(out[i]); mg += g(out[i]); mb += b(out[i]); mc++ }
            else if (ring) { fr += r(orig[i]); fg += g(orig[i]); fb += b(orig[i]); rc++ }
        }
        if (mc > 0 && rc > 0) {
            mr /= mc; mg /= mc; mb /= mc; fr /= rc; fg /= rc; fb /= rc
            var vr = 0f; var vg = 0f; var vb = 0f
            var wr = 0f; var wg = 0f; var wb = 0f
            for (i in R.indices) {
                val x = i % w; val y = i / w
                val ring = !R[i] && ((x > 0 && R[i - 1]) || (x < w - 1 && R[i + 1]) ||
                        (y > 0 && R[i - w]) || (y < h - 1 && R[i + w]))
                if (R[i]) {
                    vr += (r(out[i]) - mr) * (r(out[i]) - mr)
                    vg += (g(out[i]) - mg) * (g(out[i]) - mg)
                    vb += (b(out[i]) - mb) * (b(out[i]) - mb)
                } else if (ring) {
                    wr += (r(orig[i]) - fr) * (r(orig[i]) - fr)
                    wg += (g(orig[i]) - fg) * (g(orig[i]) - fg)
                    wb += (b(orig[i]) - fb) * (b(orig[i]) - fb)
                }
            }
            vr = sqrt(vr / mc); vg = sqrt(vg / mc); vb = sqrt(vb / mc)
            wr = sqrt(wr / rc); wg = sqrt(wg / rc); wb = sqrt(wb / rc)
            val srr = if (vr > 1f) wr / vr else 1f
            val sgg = if (vg > 1f) wg / vg else 1f
            val sbb = if (vb > 1f) wb / vb else 1f
            for (i in R.indices) {
                if (!R[i]) continue
                val nr = ((r(out[i]) - mr) * srr + fr).coerceIn(0f, 255f).toInt()
                val ng = ((g(out[i]) - mg) * sgg + fg).coerceIn(0f, 255f).toInt()
                val nb = ((b(out[i]) - mb) * sbb + fb).coerceIn(0f, 255f).toInt()
                out[i] = rgb(nr, ng, nb)
            }
        }

        // 4) 泊松梯度域融合（可选）：让修复区梯度跟随 out、边界钉死原图
        if (usePoisson) poisson(out, orig, R, w, h, bb, eb, bt, et)

        // 3) 软边羽化合成：alpha=1(二值区) → 0(羽化带外)，原图↔修复图按 alpha 混合
        val res = orig.copyOf()
        for (i in res.indices) {
            val alpha = when {
                bin[i] -> 1f
                R[i] -> (1f - dist[i].toFloat() / feather).coerceIn(0f, 1f)
                else -> 0f
            }
            if (alpha <= 0f) continue
            if (alpha >= 1f) { res[i] = out[i]; continue }
            val or = r(orig[i]); val og = g(orig[i]); val ob = b(orig[i])
            val rr = r(out[i]); val rg = g(out[i]); val rb = b(out[i])
            res[i] = rgb(
                (or + (rr - or) * alpha).toInt(),
                (og + (rg - og) * alpha).toInt(),
                (ob + (rb - ob) * alpha).toInt()
            )
        }

        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.setPixels(res, 0, w, 0, 0, w, h)
        return bmp
    }

    private fun poisson(
        out: IntArray, orig: IntArray, R: BooleanArray,
        w: Int, h: Int, bb: Int, eb: Int, bt: Int, et: Int
    ) {
        val area = R.count { it }
        if (area > 200_000) return
        val iter = 30
        for (c in 0..2) {
            repeat(iter) {
                for (y in bt..et) for (x in bb..eb) {
                    val i = y * w + x
                    if (!R[i]) continue
                    val edge = (x > 0 && !R[i - 1]) || (x < w - 1 && !R[i + 1]) ||
                            (y > 0 && !R[i - w]) || (y < h - 1 && !R[i + w])
                    if (edge) { out[i] = orig[i]; continue }
                    val gl = if (x > 0) ch(out, i - 1, c) - ch(out, i, c) else 0f
                    val gr = if (x < w - 1) ch(out, i, c) - ch(out, i + 1, c) else 0f
                    val gu = if (y > 0) ch(out, i - w, c) - ch(out, i, c) else 0f
                    val gd = if (y < h - 1) ch(out, i, c) - ch(out, i + w, c) else 0f
                    val div = (gr - gl) + (gd - gu)
                    val sum = ch(out, i - 1, c) + ch(out, i + 1, c) + ch(out, i - w, c) + ch(out, i + w, c)
                    val v = (sum / 4f) + 0.25f * div
                    out[i] = setCh(out[i], c, v.coerceIn(0f, 255f).toInt())
                }
            }
        }
    }

    private fun ch(a: IntArray, i: Int, c: Int): Float = when (c) {
        0 -> r(a[i]); 1 -> g(a[i]); else -> b(a[i])
    }.toFloat()

    private fun setCh(p: Int, c: Int, v: Int): Int = when (c) {
        0 -> rgb(v, g(p), b(p))
        1 -> rgb(r(p), v, b(p))
        else -> rgb(r(p), g(p), v)
    }
}
