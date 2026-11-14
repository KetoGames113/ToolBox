@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
package com.toolbox.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.github.junrar.Junrar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.ArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

data class FE(val name: String, val path: String, val dir: Boolean, val size: Long, val mod: Long)

object FmClip { var files = listOf<String>(); var move = false }
var lastDir: String = ""

object Arch {
    fun zip(files: List<File>, out: File) {
        ZipOutputStream(BufferedOutputStream(FileOutputStream(out), 1 shl 16)).use { z ->
            fun add(f: File, base: String) {
                if (f.isDirectory) {
                    val kids = f.listFiles() ?: return
                    if (kids.isEmpty()) { z.putNextEntry(ZipEntry("$base${f.name}/")); z.closeEntry() }
                    kids.forEach { add(it, "$base${f.name}/") }
                } else {
                    z.putNextEntry(ZipEntry(base + f.name)); f.inputStream().use { it.copyTo(z, 1 shl 16) }; z.closeEntry()
                }
            }
            files.forEach { add(it, "") }
        }
    }

    fun sevenZ(files: List<File>, out: File) {
        SevenZOutputFile(out).use { s ->
            fun add(f: File, base: String) {
                val name = base + f.name
                s.putArchiveEntry(s.createArchiveEntry(f, name))
                if (f.isFile) f.inputStream().use { i -> val b = ByteArray(65536); while (true) { val n = i.read(b); if (n < 0) break; s.write(b, 0, n) } }
                s.closeArchiveEntry()
                if (f.isDirectory) f.listFiles()?.forEach { add(it, "$name/") }
            }
            files.forEach { add(it, "") }
        }
    }

    fun extract(a: File, dest: File) {
        dest.mkdirs()
        val n = a.name.lowercase()
        val base = dest.canonicalPath + File.separator
        fun target(name: String): File? { val t = File(dest, name).canonicalFile; return if (t.path.startsWith(base)) t else null }
        fun write(t: File, i: InputStream) { t.parentFile?.mkdirs(); t.outputStream().use { o -> i.copyTo(o, 1 shl 16) } }
        when {
            n.endsWith(".zip") || n.endsWith(".jar") || n.endsWith(".cbz") -> ZipFile(a).use { z ->
                for (e in z.entries()) {
                    val t = target(e.name) ?: continue
                    if (e.isDirectory) t.mkdirs() else z.getInputStream(e).use { write(t, it) }
                }
            }
            n.endsWith(".7z") -> SevenZFile(a).use { sz ->
                var e = sz.nextEntry
                while (e != null) {
                    val t = target(e.name)
                    if (t != null) {
                        if (e.isDirectory) t.mkdirs() else {
                            t.parentFile?.mkdirs()
                            t.outputStream().use { o -> val b = ByteArray(65536); while (true) { val r = sz.read(b); if (r < 0) break; o.write(b, 0, r) } }
                        }
                    }
                    e = sz.nextEntry
                }
            }
            n.endsWith(".rar") -> Junrar.extract(a, dest)
            n.endsWith(".tar") || n.endsWith(".tar.gz") || n.endsWith(".tgz") || n.endsWith(".tar.bz2") || n.endsWith(".tar.xz") -> {
                val base0 = BufferedInputStream(FileInputStream(a))
                val ins: InputStream = when {
                    n.endsWith(".gz") || n.endsWith(".tgz") -> GzipCompressorInputStream(base0)
                    n.endsWith(".bz2") -> BZip2CompressorInputStream(base0)
                    n.endsWith(".xz") -> XZCompressorInputStream(base0)
                    else -> base0
                }
                TarArchiveInputStream(ins).use { tar ->
                    var e: ArchiveEntry? = tar.nextEntry
                    while (e != null) {
                        val t = target(e.name)
                        if (t != null) { if (e.isDirectory) t.mkdirs() else write(t, tar) }
                        e = tar.nextEntry
                    }
                }
            }
            n.endsWith(".gz") -> GzipCompressorInputStream(BufferedInputStream(FileInputStream(a))).use { write(File(dest, a.nameWithoutExtension), it) }
            else -> throw IllegalArgumentException("unsupported")
        }
    }
}

fun listDir(p: String): Triple<List<FE>, Boolean, String> {
    val ks = File(p).listFiles()
    if (ks != null) return Triple(ks.map { FE(it.name, it.path, it.isDirectory, if (it.isFile) it.length() else 0L, it.lastModified()) }, false, "")
    if (Sh.ready()) {
        val l = Sh.ls(p)
        if (l != null) return Triple(l.map { val d = it.endsWith("/"); val n = it.removeSuffix("/"); FE(n, "$p/$n", d, 0L, 0L) }, true, "")
    }
    return Triple(emptyList(), false, tr("لا يمكن قراءة هذا المجلد. فعّل Shizuku من الإعدادات للوصول إلى Android/data وobb.", "Cannot read this folder. Enable Shizuku in Settings for Android/data and obb."))
}

fun fsPut(src: File, dir: File, move: Boolean, shell: Boolean) {
    val dst = uniqueFile(dir, src.name)
    var ok = false
    if (!shell) ok = try { if (move && src.renameTo(dst)) true else src.copyRecursively(dst, false) && (!move || src.deleteRecursively()) } catch (_: Throwable) { false }
    if (!ok && Sh.ready()) Sh.run("${if (move) "mv" else "cp -r"} ${Sh.q(src.path)} ${Sh.q(dst.path)}")
}

@Composable
fun ArchiveScreen(path: String) {
    val nav = LocalNav.current
    val f = File(path)
    val scope = rememberCoroutineScope()
    var msg by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var out by remember { mutableStateOf<File?>(null) }
    fun go(dest: File) {
        scope.launch {
            busy = true; msg = ""
            try { withContext(Dispatchers.IO) { Arch.extract(f, dest) }; out = dest; msg = tr("تم الاستخراج ✔", "Extracted") }
            catch (e: Throwable) { msg = "Error: ${e.message}" }
            busy = false
        }
    }
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(f.name, style = MaterialTheme.typography.titleMedium)
        Text(fmt(f.length()))
        Button(enabled = !busy, onClick = { go(File(f.parentFile, f.nameWithoutExtension)) }) { Text(tr("استخراج هنا (في مجلد)", "Extract here (new folder)")) }
        OutlinedButton(enabled = !busy, onClick = { go(File(tbDir("Extracted"), f.nameWithoutExtension)) }) { Text(tr("استخراج إلى ToolBox/Extracted", "Extract to ToolBox/Extracted")) }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (msg.isNotEmpty()) Text(msg)
        out?.let { o -> Button(onClick = { nav.go("files", o.path) }) { Text(tr("فتح المجلد", "Open folder")) } }
    }
}
