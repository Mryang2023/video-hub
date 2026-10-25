package com.videohub.tv.util

import android.graphics.Bitmap
import android.graphics.Color
import com.bumptech.glide.load.engine.bitmap_recycle.BitmapPool
import com.bumptech.glide.load.resource.bitmap.BitmapTransformation
import java.security.MessageDigest

/**
 * 环境光背景用的模糊：先把图逐级减半压到 ≤160px 宽，再做可分离盒模糊。
 * 只给首页氛围层用（一次一张图），小图上算法开销可忽略；
 * 拉伸回全屏后本身就是糊的，无所谓画质。
 */
class BlurTransformation(private val radius: Int = 8) : BitmapTransformation() {

    override fun transform(
        pool: BitmapPool,
        toTransform: Bitmap,
        outWidth: Int,
        outHeight: Int,
    ): Bitmap {
        var src = toTransform
        while (src.width > 160 && src.height > 160) {
            val scaled = Bitmap.createScaledBitmap(
                src, (src.width / 2).coerceAtLeast(1), (src.height / 2).coerceAtLeast(1), true
            )
            if (scaled !== src) src.recycle()
            src = scaled
        }
        val r = radius.coerceIn(1, 16)
        val w = src.width
        val h = src.height
        val pix = IntArray(w * h)
        src.getPixels(pix, 0, w, 0, 0, w, h)
        val out = boxBlur(pix, w, h, r)
        val result = pool.get(w, h, Bitmap.Config.ARGB_8888)
        result.setPixels(out, 0, w, 0, 0, w, h)
        if (src !== toTransform) src.recycle()
        return result
    }

    /** 可分离盒模糊：水平、垂直各一趟滑动窗口平均，O(w*h) */
    private fun boxBlur(pix: IntArray, w: Int, h: Int, r: Int): IntArray {
        val div = 2 * r + 1

        // 水平
        val mid = IntArray(w * h)
        for (y in 0 until h) {
            val row = y * w
            var rs = 0; var gs = 0; var bs = 0
            for (i in -r..r) {
                val p = pix[row + i.coerceIn(0, w - 1)]
                rs += Color.red(p); gs += Color.green(p); bs += Color.blue(p)
            }
            for (x in 0 until w) {
                mid[row + x] = Color.rgb(rs / div, gs / div, bs / div)
                val outP = pix[row + (x - r).coerceIn(0, w - 1)]
                val inP = pix[row + (x + r + 1).coerceIn(0, w - 1)]
                rs += Color.red(inP) - Color.red(outP)
                gs += Color.green(inP) - Color.green(outP)
                bs += Color.blue(inP) - Color.blue(outP)
            }
        }

        // 垂直（作用在水平结果上）
        val out = IntArray(w * h)
        for (x in 0 until w) {
            var rs = 0; var gs = 0; var bs = 0
            for (i in -r..r) {
                val p = mid[i.coerceIn(0, h - 1) * w + x]
                rs += Color.red(p); gs += Color.green(p); bs += Color.blue(p)
            }
            for (y in 0 until h) {
                out[y * w + x] = Color.rgb(rs / div, gs / div, bs / div)
                val outP = mid[(y - r).coerceIn(0, h - 1) * w + x]
                val inP = mid[(y + r + 1).coerceIn(0, h - 1) * w + x]
                rs += Color.red(inP) - Color.red(outP)
                gs += Color.green(inP) - Color.green(outP)
                bs += Color.blue(inP) - Color.blue(outP)
            }
        }
        return out
    }

    override fun equals(other: Any?): Boolean =
        other is BlurTransformation && other.radius == radius

    override fun hashCode(): Int = ("blur$radius").hashCode()

    override fun updateDiskCacheKey(messageDigest: MessageDigest) {
        messageDigest.update("blur$radius".toByteArray())
    }
}
