package com.toolbox.app

import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

/** تشغيل أوامر بصلاحية shell عبر Shizuku (أو root إن وُجد). */
object Sh {
    private var rootOk: Boolean? = null

    fun shizukuUp() = try { Shizuku.pingBinder() } catch (_: Throwable) { false }
    fun shizukuOk() = try { shizukuUp() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED } catch (_: Throwable) { false }
    fun ask() { try { Shizuku.requestPermission(100) } catch (_: Throwable) { toast("Shizuku?") } }

    fun root(): Boolean {
        rootOk?.let { return it }
        val r = try { Runtime.getRuntime().exec(arrayOf("su", "-c", "id")).waitFor() == 0 } catch (_: Throwable) { false }
        rootOk = r
        return r
    }

    fun ready() = shizukuOk() || root()

    fun run(cmd: String): Pair<Int, String> = try {
        val p: Process = if (shizukuOk()) {
            val m = Shizuku::class.java.getDeclaredMethod("newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java)
            m.isAccessible = true
            m.invoke(null, arrayOf("sh", "-c", cmd), null, null) as Process
        } else Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
        val err = StringBuilder()
        val t = Thread { err.append(p.errorStream.bufferedReader().readText()) }
        t.start()
        val o = p.inputStream.bufferedReader().readText()
        val code = p.waitFor()
        t.join()
        code to (o + err)
    } catch (t: Throwable) { -1 to (t.message ?: "error") }

    fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"

    fun ls(dir: String): List<String>? {
        val (c, o) = run("ls -1Ap ${q(dir)}")
        return if (c == 0) o.lines().filter { it.isNotBlank() } else null
    }

    fun trimCaches() = run("pm trim-caches 999999999999 || cmd package trim-caches 999999999999")
}
