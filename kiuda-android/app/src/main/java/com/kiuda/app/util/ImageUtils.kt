package com.kiuda.app.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max

object ImageUtils {
    /**
     * Gemini 전송용으로 긴 변 maxSide 이하, JPEG quality 압축.
     * 원본은 유지하고 캐시 디렉터리에 새 파일을 만듭니다.
     */
    fun compressForAi(
        source: File,
        cacheDir: File,
        maxSide: Int = 1280,
        quality: Int = 78
    ): File {
        if (!source.exists() || source.length() == 0L) return source

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.absolutePath, bounds)
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w <= 0 || h <= 0) return source

        var sample = 1
        val longSide = max(w, h)
        while (longSide / sample > maxSide * 2) {
            sample *= 2
        }

        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        var bitmap = BitmapFactory.decodeFile(source.absolutePath, opts) ?: return source

        val bw = bitmap.width
        val bh = bitmap.height
        val scale = if (max(bw, bh) > maxSide) maxSide.toFloat() / max(bw, bh) else 1f
        if (scale < 1f) {
            val nw = (bw * scale).toInt().coerceAtLeast(1)
            val nh = (bh * scale).toInt().coerceAtLeast(1)
            val scaled = Bitmap.createScaledBitmap(bitmap, nw, nh, true)
            if (scaled != bitmap) {
                bitmap.recycle()
                bitmap = scaled
            }
        }

        val out = File(cacheDir, "ai_${System.currentTimeMillis()}.jpg")
        FileOutputStream(out).use { fos ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, fos)
            fos.flush()
        }
        bitmap.recycle()

        // 압축본이 이상하면 원본 사용
        return if (out.exists() && out.length() > 0L) out else source
    }
}
