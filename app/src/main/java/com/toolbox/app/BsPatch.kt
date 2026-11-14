package com.toolbox.app

import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/** تطبيق رقعة bsdiff (BSDIFF40) على ملف قديم لإنتاج الملف الجديد. */
object BsPatch {
    fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { i -> val b = ByteArray(1 shl 20); while (true) { val n = i.read(b); if (n < 0) break; md.update(b, 0, n) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun offtin(b: ByteArray, o: Int): Long {
        var y = (b[o + 7].toLong() and 0x7F)
        for (i in 6 downTo 0) y = y * 256 + (b[o + i].toLong() and 0xFF)
        return if ((b[o + 7].toInt() and 0x80) != 0) -y else y
    }

    private fun readFully(s: InputStream, b: ByteArray, n: Int) {
        var d = 0
        while (d < n) { val r = s.read(b, d, n - d); if (r < 0) throw java.io.EOFException("patch truncated"); d += r }
    }

    fun apply(old: ByteArray, patchFile: File, out: File) {
        val p = patchFile.readBytes()
        if (p.size < 32 || String(p, 0, 8, Charsets.US_ASCII) != "BSDIFF40") throw IllegalStateException("bad patch")
        val ctrlLen = offtin(p, 8).toInt(); val diffLen = offtin(p, 16).toInt(); val newSize = offtin(p, 24)
        val ctrl = BZip2CompressorInputStream(ByteArrayInputStream(p, 32, ctrlLen))
        val diff = BZip2CompressorInputStream(ByteArrayInputStream(p, 32 + ctrlLen, diffLen))
        val extra = BZip2CompressorInputStream(ByteArrayInputStream(p, 32 + ctrlLen + diffLen, p.size - 32 - ctrlLen - diffLen))
        val c = ByteArray(24); val buf = ByteArray(1 shl 16)
        var oldpos = 0L; var newpos = 0L
        BufferedOutputStream(out.outputStream(), 1 shl 16).use { os ->
            while (newpos < newSize) {
                readFully(ctrl, c, 24)
                val x = offtin(c, 0); val y = offtin(c, 8); val z = offtin(c, 16)
                var done = 0L
                while (done < x) {
                    val n = minOf(buf.size.toLong(), x - done).toInt()
                    readFully(diff, buf, n)
                    for (i in 0 until n) {
                        val q = oldpos + done + i
                        if (q >= 0 && q < old.size) buf[i] = (buf[i] + old[q.toInt()]).toByte()
                    }
                    os.write(buf, 0, n); done += n
                }
                newpos += x; oldpos += x
                done = 0L
                while (done < y) {
                    val n = minOf(buf.size.toLong(), y - done).toInt()
                    readFully(extra, buf, n)
                    os.write(buf, 0, n); done += n
                }
                newpos += y; oldpos += z
            }
        }
    }
}
