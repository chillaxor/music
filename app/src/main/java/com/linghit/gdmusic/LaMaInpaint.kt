package com.linghit.gdmusic

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import kotlin.math.max
import kotlin.math.min
import org.tensorflow.lite.Interpreter
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 路径二：LaMa-Dilated INT8 端侧修复（Google TensorFlow Lite）。
 *
 * 模型需置于 filesDir/models/lama_dilated_int8.tflite（44MB，Apache-2.0，从 HF / Qualcomm AI Hub 取）。
 * 缺失时 isAvailable=false，调用方自动降级到路径一（InpaintEngine），保证不崩。
 *
 * 推理范围：取水印包围盒外扩成正方形 → 缩到 512（LaMa 固定输入）→ 跑图+mask 双输入 →
 * 结果缩回贴回原图（mask 外保留原图）。水印通常较小，单块即可；超大水印的切块多频融合为后续增强。
 */
object LaMaInpaint {

    private const val MODEL_NAME = "lama_dilated_int8.tflite"
    private const val SIZE = 512

    fun modelFile(ctx: Context) = File(ctx.filesDir, "models/$MODEL_NAME")
    fun isAvailable(ctx: Context) = modelFile(ctx).exists()

    fun run(ctx: Context, src: Bitmap, mask: Bitmap): Bitmap? {
        val model = modelFile(ctx)
        if (!model.exists()) return null
        val interpreter = try { Interpreter(model) } catch (e: Throwable) { return null }

        return try {
            val w = src.width; val h = src.height
            if (mask.width != w || mask.height != h) return null

            // 1) 掩码包围盒
            val mp = IntArray(w * h)
            mask.getPixels(mp, 0, w, 0, 0, w, h)
            var minX = w; var minY = h; var maxX = -1; var maxY = -1; var any = false
            for (i in mp.indices) {
                if (((mp[i] shr 16) and 0xFF) > 127) {
                    any = true
                    val x = i % w; val y = i / w
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
            if (!any) return src.copy(Bitmap.Config.ARGB_8888, false)

            // 2) 外扩成正方形
            val bw = maxX - minX + 1; val bh = maxY - minY + 1
            var side = max(bw, bh) + max(bw, bh) / 5 + 16
            side = side.coerceAtMost(min(w, h))
            val cx = (minX + maxX) / 2f; val cy = (minY + maxY) / 2f
            var sx = (cx - side / 2f).toInt().coerceIn(0, w - side)
            var sy = (cy - side / 2f).toInt().coerceIn(0, h - side)
            val ex = sx + side; val ey = sy + side

            // 3) 裁正方形区域 + 缩到 512
            val region = Bitmap.createBitmap(src, sx, sy, side, side)
            val maskRegion = Bitmap.createBitmap(mask, sx, sy, side, side)
            val inImg = Bitmap.createScaledBitmap(region, SIZE, SIZE, true)
            val inMsk = Bitmap.createScaledBitmap(maskRegion, SIZE, SIZE, true)
            region.recycle(); maskRegion.recycle()

            val imgPx = IntArray(SIZE * SIZE); inImg.getPixels(imgPx, 0, SIZE, 0,0, SIZE, SIZE)
            val mskPx = IntArray(SIZE * SIZE); inMsk.getPixels(mskPx, 0, SIZE, 0,0, SIZE, SIZE)

            val imgBuf = ByteBuffer.allocateDirect(4 * SIZE * SIZE * 3).order(ByteOrder.nativeOrder())
            val mskBuf = ByteBuffer.allocateDirect(4 * SIZE * SIZE * 1).order(ByteOrder.nativeOrder())
            for (i in 0 until SIZE * SIZE) {
                val p = imgPx[i]
                imgBuf.putFloat(((p shr 16) and 0xFF) / 255f)
                imgBuf.putFloat(((p shr 8) and 0xFF) / 255f)
                imgBuf.putFloat((p and 0xFF) / 255f)
                mskBuf.putFloat(if (((mskPx[i] shr 16) and 0xFF) > 127) 1f else 0f)
            }
            imgBuf.rewind(); mskBuf.rewind()

            val outBuf = ByteBuffer.allocateDirect(4 * SIZE * SIZE * 3).order(ByteOrder.nativeOrder())
            interpreter.runForMultipleInputsOutputs(arrayOf(imgBuf, mskBuf), mapOf(0 to outBuf))
            outBuf.rewind()

            // 4) 输出 → 位图 → 缩回 side
            val out512 = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
            val op = IntArray(SIZE * SIZE)
            for (i in 0 until SIZE * SIZE) {
                val r = (outBuf.getFloat() * 255f).coerceIn(0f, 255f).toInt()
                val g = (outBuf.getFloat() * 255f).coerceIn(0f, 255f).toInt()
                val b = (outBuf.getFloat() * 255f).coerceIn(0f, 255f).toInt()
                op[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
            out512.setPixels(op, 0, SIZE, 0, 0, SIZE, SIZE)
            val outRegion = Bitmap.createScaledBitmap(out512, side, side, true)
            out512.recycle()

            // 5) 贴回原图（mask 内用 LaMa 结果，mask 外保留原图）
            val result = src.copy(Bitmap.Config.ARGB_8888, false)
            val cv = Canvas(result)
            cv.drawBitmap(outRegion, Rect(0, 0, side, side), Rect(sx, sy, ex, ey), null)
            outRegion.recycle()

            // 6) 仅 mask 内覆盖（mask 外已在上面被原图保持，这里用 maskRegion512 决定覆盖与否）
            //    上面整块贴回会覆盖 margin 环，用 mask 重新约束：mask 外还原为原图
            val restored = result.copy(Bitmap.Config.ARGB_8888, false)
            val rp = IntArray(w * h); restored.getPixels(rp, 0, w, 0, 0, w, h)
            val sp = IntArray(w * h); src.getPixels(sp, 0, w, 0, 0, w, h)
            for (y in sy until ey) for (x in sx until ex) {
                val rx = ((x - sx) * SIZE) / side
                val ry = ((y - sy) * SIZE) / side
                val mi = ry * SIZE + rx
                if (((mskPx[mi] shr 16) and 0xFF) <= 127) {
                    rp[y * w + x] = sp[y * w + x] // mask 外还原原图
                }
            }
            restored.setPixels(rp, 0, w, 0, 0, w, h)
            result.recycle()
            restored
        } catch (e: Throwable) {
            null
        } finally {
            runCatching { interpreter.close() }
        }
    }
}
