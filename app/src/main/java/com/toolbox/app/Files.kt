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
fun FilesScreen(start: String) {
    val c = LocalContext.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    if (lastDir.isEmpty()) lastDir = ROOT.path
    var cwd by remember { mutableStateOf(start.ifEmpty { lastDir }) }
    var list by remember { mutableStateOf(listOf<FE>()) }
    var shell by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf("") }
    var sort by remember { mutableIntStateOf(0) }
    var hidden by remember { mutableStateOf(false) }
    var q by remember { mutableStateOf<String?>(null) }
    val sel = remember { mutableStateListOf<String>() }
    var dlg by remember { mutableStateOf("") }
    var input by remember { mutableStateOf("") }
    var fmtZip by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf("") }
    var tick by remember { mutableIntStateOf(0) }

    LaunchedEffect(cwd, tick, Tick.n) {
        lastDir = cwd; err = ""
        val r = withContext(Dispatchers.IO) { listDir(cwd) }
        list = r.first; shell = r.second; err = r.third
    }
    BackHandler(enabled = sel.isNotEmpty() || (cwd != ROOT.path && File(cwd).parent != null)) {
        if (sel.isNotEmpty()) sel.clear() else cwd = File(cwd).parent ?: cwd
    }
    val qq = q
    val shown = remember(list, sort, hidden, qq) {
        list.filter { (hidden || !it.name.startsWith(".")) && (qq.isNullOrEmpty() || it.name.contains(qq, true)) }
            .sortedWith(compareBy<FE>({ !it.dir }).thenBy { when (sort) { 1 -> -it.size; 2 -> -it.mod; else -> 0L } }.thenBy { it.name.lowercase() })
    }
    fun toggle(p: String) { if (p in sel) sel.remove(p) else sel.add(p) }
    fun run(label: String, work: () -> Unit) {
        scope.launch { busy = label; withContext(Dispatchers.IO) { try { work() } catch (t: Throwable) { toast("Error: ${t.message}") } }; busy = ""; sel.clear(); tick++ }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val roots = mutableListOf(tr("الداخلي", "Internal") to ROOT.path, "Download" to "${ROOT.path}/Download", "DCIM" to "${ROOT.path}/DCIM",
                "Pictures" to "${ROOT.path}/Pictures", "Android/data" to "${ROOT.path}/Android/data", "Android/obb" to "${ROOT.path}/Android/obb", "ToolBox" to "${ROOT.path}/ToolBox")
            File("/storage").listFiles()?.filter { it.isDirectory && it.name != "emulated" && it.name != "self" }?.forEach { roots.add("SD ${it.name}" to it.path) }
            roots.forEach { (n, p) -> AssistChip(onClick = { cwd = p; sel.clear() }, label = { Text(n) }) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { cwd = File(cwd).parent ?: cwd; sel.clear() }) { Icon(Icons.Default.ArrowUpward, null) }
            Text(cwd.removePrefix(ROOT.path).ifEmpty { "/" }, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            IconButton(onClick = { q = if (q == null) "" else null }) { Icon(Icons.Default.Search, null) }
            IconButton(onClick = { sort = (sort + 1) % 3 }) { Icon(Icons.Default.SortByAlpha, null) }
            IconButton(onClick = { hidden = !hidden }) { Icon(if (hidden) Icons.Default.Visibility else Icons.Default.VisibilityOff, null) }
            IconButton(onClick = { input = ""; dlg = "newdir" }) { Icon(Icons.Default.CreateNewFolder, null) }
            IconButton(onClick = { tick++ }) { Icon(Icons.Default.Refresh, null) }
        }
        if (q != null) OutlinedTextField(q ?: "", { q = it }, Modifier.fillMaxWidth().padding(horizontal = 8.dp), singleLine = true)
        if (err.isNotEmpty()) Text(err, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.error)
        LazyColumn(Modifier.weight(1f)) {
            items(shown, key = { it.path }) { e ->
                val x = File(e.path).extension.lowercase()
                Row(
                    Modifier.fillMaxWidth()
                        .combinedClickable(
                            onClick = { if (sel.isNotEmpty()) toggle(e.path) else if (e.dir) { cwd = e.path } else openPath(nav, c, e.path) },
                            onLongClick = { toggle(e.path) })
                        .background(if (e.path in sel) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                        if (!e.dir && !shell && (x in IMG || x in VID)) AsyncImage(File(e.path), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        else Icon(
                            when { e.dir -> Icons.Default.Folder; x in AUD -> Icons.Default.MusicNote; x == "apk" || x == "xapk" -> Icons.Default.Android
                                x in ARC -> Icons.Default.Archive; x == "pdf" -> Icons.Default.PictureAsPdf; x in TXT -> Icons.Default.Description; else -> Icons.Default.InsertDriveFile },
                            null, tint = if (e.dir) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(e.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val d = if (e.mod > 0) SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(e.mod)) else ""
                        Text(if (e.dir || e.size == 0L && shell) d else "${fmt(e.size)}  $d", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        if (sel.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                Text(" ${sel.size} ", style = MaterialTheme.typography.titleSmall)
                IconButton(onClick = { FmClip.files = sel.toList(); FmClip.move = false; sel.clear() }) { Icon(Icons.Default.ContentCopy, null) }
                IconButton(onClick = { FmClip.files = sel.toList(); FmClip.move = true; sel.clear() }) { Icon(Icons.Default.ContentCut, null) }
                IconButton(onClick = { dlg = "delete" }) { Icon(Icons.Default.Delete, null) }
                if (sel.size == 1) IconButton(onClick = { input = File(sel[0]).name; dlg = "rename" }) { Icon(Icons.Default.Edit, null) }
                IconButton(onClick = { input = "archive"; dlg = "zip" }) { Icon(Icons.Default.Archive, null) }
                IconButton(onClick = { shareFiles(c, sel.map { File(it) }) }) { Icon(Icons.Default.Share, null) }
                IconButton(onClick = { sel.clear(); sel.addAll(shown.map { it.path }) }) { Icon(Icons.Default.SelectAll, null) }
                IconButton(onClick = { sel.clear() }) { Icon(Icons.Default.Close, null) }
            }
        } else if (FmClip.files.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.secondaryContainer).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${FmClip.files.size} ${if (FmClip.move) tr("للنقل", "to move") else tr("للنسخ", "to copy")}", Modifier.weight(1f))
                TextButton(onClick = { FmClip.files = emptyList(); tick++ }) { Text(tr("إلغاء", "Cancel")) }
                Button(onClick = { val fs = FmClip.files; val mv = FmClip.move; run(tr("جارٍ اللصق…", "Pasting…")) { fs.forEach { fsPut(File(it), File(cwd), mv, shell) }; if (mv) FmClip.files = emptyList() } }) { Text(tr("لصق هنا", "Paste here")) }
            }
        }
    }

    if (busy.isNotEmpty()) AlertDialog(onDismissRequest = {}, confirmButton = {}, title = { Text(busy) }, text = { LinearProgressIndicator(Modifier.fillMaxWidth()) })
    if (dlg == "delete") AlertDialog(
        onDismissRequest = { dlg = "" }, title = { Text(tr("حذف؟", "Delete?")) }, text = { Text(tr("حذف ${sel.size} عنصر نهائياً.", "Delete ${sel.size} items permanently.")) },
        confirmButton = { TextButton(onClick = { dlg = ""; val l = sel.toList(); run(tr("جارٍ الحذف…", "Deleting…")) { l.forEach { Cln.del(File(it)) } } }) { Text(tr("حذف", "Delete")) } },
        dismissButton = { TextButton(onClick = { dlg = "" }) { Text(tr("إلغاء", "Cancel")) } })
    if (dlg == "newdir" || dlg == "rename" || dlg == "zip") AlertDialog(
        onDismissRequest = { dlg = "" },
        title = { Text(when (dlg) { "newdir" -> tr("مجلد جديد", "New folder"); "rename" -> tr("إعادة تسمية", "Rename"); else -> tr("ضغط", "Compress") }) },
        text = {
            Column {
                OutlinedTextField(input, { input = it }, singleLine = true)
                if (dlg == "zip") Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(fmtZip == 0, { fmtZip = 0 }); Text("ZIP"); Spacer(Modifier.width(12.dp)); RadioButton(fmtZip == 1, { fmtZip = 1 }); Text("7z")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val d = dlg; dlg = ""; val name = input.trim(); val l = sel.toList(); val fz = fmtZip
                if (name.isNotEmpty()) run("…") {
                    when (d) {
                        "newdir" -> if (!File(cwd, name).mkdirs() && Sh.ready()) Sh.run("mkdir -p ${Sh.q("$cwd/$name")}")
                        "rename" -> { val o = File(l[0]); if (!o.renameTo(File(o.parentFile, name)) && Sh.ready()) Sh.run("mv ${Sh.q(o.path)} ${Sh.q(o.parent + "/" + name)}") }
                        else -> { val fs = l.map { File(it) }; if (fz == 0) Arch.zip(fs, uniqueFile(File(cwd), "$name.zip")) else Arch.sevenZ(fs, uniqueFile(File(cwd), "$name.7z")) }
                    }
                }
            }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = { dlg = "" }) { Text(tr("إلغاء", "Cancel")) } })
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
