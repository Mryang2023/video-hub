package com.videohub.tv.util

import android.graphics.Bitmap
import android.graphics.Color
import com.bumptech.glide.load.engine.bitmap_recycle.BitmapPool
import com.bumptech.glide.load.resource.bitmap.BitmapTransformation
import java.security.MessageDigest

/**
 * 3x3 十字锐化：多数采集站的封面只有 200~400px 宽，在电视大卡上被放大 1.3~4 倍后
 * 插值发软。锐化卷积对这种"放大软"的观感补偿明显。
 * 注意与圆角的顺序：先 Sharpen 再 RoundedCorners，否则会把圆角透明区烧黑。
 */
class SharpenTransformation(private val amount: Float = 0.4f) : BitmapTransformation() {

    override fun transform(
        pool: BitmapPool,
        toTransform: Bitmap,
        outWidth: Int,
        outHeight: Int,
    ): Bitmap {
        if (amount <= 0f) return toTransform
        val w = toTransform.width
        val h = toTransform.height
        if (w < 8 || h < 8) return toTransform

        val src = IntArray(w * h)
        toTransform.getPixels(src, 0, w, 0, 0, w, h)
        val out = IntArray(w * h)

        for (y in 0 until h) {
            val rowTop = y > 0
            val rowBottom = y < h - 1
            for (x in 0 until w) {
                val i = y * w + x
                val p0 = src[i]
                var r = Color.red(p0).toFloat()
                var g = Color.green(p0).toFloat()
                var b = Color.blue(p0).toFloat()

                var n = 0
                var sr = 0f; var sg = 0f; var sb = 0f
                if (rowTop) { val q = src[i - w]; sr += Color.red(q); sg += Color.green(q); sb += Color.blue(q); n++ }
                if (rowBottom) { val q = src[i + w]; sr += Color.red(q); sg += Color.green(q); sb += Color.blue(q); n++ }
                if (x > 0) { val q = src[i - 1]; sr += Color.red(q); sg += Color.green(q); sb += Color.blue(q); n++ }
                if (x < w - 1) { val q = src[i + 1]; sr += Color.red(q); sg += Color.green(q); sb += Color.blue(q); n++ }

                // 边缘像素邻居不足时相应降低中心权重，避免过曝
                val k = 1f + amount * n
                r = k * r - amount * sr
                g = k * g - amount * sg
                b = k * b - amount * sb

                out[i] = Color.rgb(
                    r.toInt().coerceIn(0, 255),
                    g.toInt().coerceIn(0, 255),
                    b.toInt().coerceIn(0, 255),
                )
            }
        }
        val result = pool.get(w, h, Bitmap.Config.ARGB_8888)
        result.setPixels(out, 0, w, 0, 0, w, h)
        return result
    }

    override fun equals(other: Any?): Boolean =
        other is SharpenTransformation && other.amount == amount

    override fun hashCode(): Int = ("sharpen$amount").hashCode()

    override fun updateDiskCacheKey(messageDigest: MessageDigest) {
        messageDigest.update("sharpen$amount".toByteArray())
    }
}
