package com.toolbox.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.SweepGradient
import android.media.MediaMetadataRetriever
import android.util.LruCache
import java.io.File

/** غلاف الأغنية: من الوسوم المدمجة أو من cover.jpg بجانب الملف، مع كاش. */
object Art {
    private val cache = LruCache<String, Bitmap>(64)
    private val none = HashSet<String>()

    fun load(path: String, max: Int = 512): Bitmap? {
        val key = "$path@$max"
        cache.get(key)?.let { return it }
        if (none.contains(key)) return null
        var bmp: Bitmap? = null
        try {
            if (!path.startsWith("content:")) {
                val r = MediaMetadataRetriever()
                try {
                    r.setDataSource(path)
                    val pic = r.embeddedPicture
                    if (pic != null) bmp = decode(pic, max)
                } finally { r.release() }
                if (bmp == null) {
                    val dir = File(path).parentFile
                    for (n in listOf("cover.jpg", "folder.jpg", "cover.png", "folder.png", "albumart.jpg", "front.jpg")) {
                        val f = File(dir, n)
                        if (f.exists()) { bmp = decodeFile(f.path, max); break }
                    }
                }
            }
        } catch (_: Throwable) {}
        val out = bmp
        if (out != null) cache.put(key, out) else none.add(key)
        return out
    }

    private fun sample(w: Int, h: Int, max: Int): Int {
        var s = 1
        while (w / s > max * 2 || h / s > max * 2) s *= 2
        return s
    }
    private fun decode(b: ByteArray, max: Int): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(b, 0, b.size, o)
        return BitmapFactory.decodeByteArray(b, 0, b.size, BitmapFactory.Options().apply { inSampleSize = sample(o.outWidth, o.outHeight, max) })
    }
    private fun decodeFile(p: String, max: Int): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(p, o)
        return BitmapFactory.decodeFile(p, BitmapFactory.Options().apply { inSampleSize = sample(o.outWidth, o.outHeight, max) })
    }
}

fun avgColor(bm: Bitmap): Int {
    val s = Bitmap.createScaledBitmap(bm, 8, 8, true)
    var r = 0; var g = 0; var b = 0
    for (x in 0 until 8) for (y in 0 until 8) {
        val p = s.getPixel(x, y)
        r += android.graphics.Color.red(p); g += android.graphics.Color.green(p); b += android.graphics.Color.blue(p)
    }
    return android.graphics.Color.rgb(r / 64, g / 64, b / 64)
}

fun darken(c: Int, maxV: Float = 0.42f): Int {
    val hsv = FloatArray(3); android.graphics.Color.colorToHSV(c, hsv)
    hsv[1] = hsv[1].coerceAtLeast(0.35f); hsv[2] = hsv[2].coerceAtMost(maxV)
    return android.graphics.Color.HSVToColor(hsv)
}

/** أسطوانة مرسومة كصورة (للإشعار): أخاديد + لمعة + غلاف الأغنية في المنتصف، تدور بزاوية angle. */
fun vinylBitmap(art: Bitmap?, angle: Float, size: Int, tint: Int): Bitmap {
    val bm = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val cv = android.graphics.Canvas(bm)
    val r = size / 2f
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    p.color = 0xFF2A2250.toInt(); cv.drawCircle(r, r, r, p)
    p.color = 0xFF3A2F70.toInt(); cv.drawCircle(r, r, r * 0.94f, p)
    p.style = Paint.Style.STROKE; p.strokeWidth = 1f; p.color = 0x22FFFFFF
    var rad = r * 0.42f
    while (rad < r * 0.93f) { cv.drawCircle(r, r, rad, p); rad += r * 0.04f }
    p.style = Paint.Style.FILL
    val sweep = SweepGradient(r, r, intArrayOf(0x00FFFFFF, 0x40FFFFFF, 0x00FFFFFF, 0x2EFFFFFF, 0x00FFFFFF), null)
    val m = Matrix(); m.setRotate(angle, r, r); sweep.setLocalMatrix(m)
    p.shader = sweep; cv.drawCircle(r, r, r * 0.94f, p); p.shader = null
    val lr = r * 0.40f
    cv.save(); cv.rotate(angle, r, r)
    val clip = Path().apply { addCircle(r, r, lr, Path.Direction.CW) }
    cv.clipPath(clip)
    if (art != null) cv.drawBitmap(art, null, RectF(r - lr, r - lr, r + lr, r + lr), Paint(Paint.FILTER_BITMAP_FLAG))
    else { p.color = tint; cv.drawCircle(r, r, lr, p) }
    cv.restore()
    p.color = 0xFF14121F.toInt(); cv.drawCircle(r, r, r * 0.045f, p)
    return bm
}
