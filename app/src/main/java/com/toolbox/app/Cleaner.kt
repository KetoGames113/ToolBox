@file:OptIn(ExperimentalMaterial3Api::class)
package com.toolbox.app

import android.app.usage.StorageStatsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.Process
import android.os.storage.StorageManager
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

data class AppStat(val pkg: String, val label: String, val app: Long, val data: Long, val cache: Long) {
    val total get() = app + data   // الكاش جزء من data
}

class JItem(val file: File, val size: Long, val cat: Int)

object Cln {
    /** total, free, images, videos, audio */
    fun volume(c: Context): LongArray {
        val ssm = c.getSystemService(StorageStatsManager::class.java)
        val total = try { ssm.getTotalBytes(StorageManager.UUID_DEFAULT) } catch (_: Throwable) { Environment.getDataDirectory().totalSpace }
        val free = try { ssm.getFreeBytes(StorageManager.UUID_DEFAULT) } catch (_: Throwable) { Environment.getDataDirectory().usableSpace }
        var img = 0L; var vid = 0L; var aud = 0L
        try {
            val e = ssm.queryExternalStatsForUser(StorageManager.UUID_DEFAULT, Process.myUserHandle())
            img = e.imageBytes; vid = e.videoBytes; aud = e.audioBytes
        } catch (_: Throwable) {}
        return longArrayOf(total, free, img, vid, aud)
    }

    fun apps(c: Context): List<AppStat> {
        val pm = c.packageManager
        val ssm = c.getSystemService(StorageStatsManager::class.java)
        val me = Process.myUserHandle()
        return pm.getInstalledApplications(0).mapNotNull { ai ->
            try {
                val s = ssm.queryStatsForPackage(StorageManager.UUID_DEFAULT, ai.packageName, me)
                AppStat(ai.packageName, pm.getApplicationLabel(ai).toString(), s.appBytes, s.dataBytes, s.cacheBytes)
            } catch (_: Throwable) { null }
        }
    }

    fun sizeOf(f: File): Long = if (f.isFile) f.length() else f.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    fun del(f: File): Boolean = f.deleteRecursively() || (Sh.ready() && Sh.run("rm -rf ${Sh.q(f.path)}").first == 0)

    private fun qhash(f: File): String {
        val md = MessageDigest.getInstance("MD5")
        val len = f.length()
        RandomAccessFile(f, "r").use { r ->
            val buf = ByteArray(65536)
            if (len <= 50_000_000L) {
                while (true) { val n = r.read(buf); if (n < 0) break; md.update(buf, 0, n) }
            } else {
                var n = r.read(buf); if (n > 0) md.update(buf, 0, n)
                r.seek(len / 2); n = r.read(buf); if (n > 0) md.update(buf, 0, n)
                r.seek(len - 65536); n = r.read(buf); if (n > 0) md.update(buf, 0, n)
            }
        }
        md.update(len.toString().toByteArray())
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** cat: 0 مؤقتة، 1 مجلدات فارغة، 2 APK، 3 ملفات كبيرة، 4 مكررة */
    fun scan(root: File, prog: (String) -> Unit): List<JItem> {
        val temp = mutableListOf<File>(); val empty = mutableListOf<File>(); val apks = mutableListOf<File>(); val big = mutableListOf<File>()
        val bySize = HashMap<Long, MutableList<File>>()
        val androidDir = File(root, "Android")
        var n = 0
        val tmpExt = setOf("tmp", "temp", "log", "bak", "old", "chk", "dmp", "cache")
        root.walkTopDown().onEnter { d ->
            when {
                d == androidDir -> false
                d != root && (d.name == ".thumbnails" || d.name == ".cache") -> { temp.add(d); false }
                else -> true
            }
        }.forEach { f ->
            if (++n % 400 == 0) prog(f.parent ?: "")
            if (f.isDirectory) { if (f != root && f.list()?.isEmpty() == true) empty.add(f); return@forEach }
            val ext = f.extension.lowercase(); val len = f.length()
            if (ext in tmpExt || f.name.startsWith(".thumbdata")) temp.add(f)
            if (ext == "apk") apks.add(f)
            if (len > 200_000_000L) big.add(f)
            if (len >= 1_000_000L && !f.name.startsWith(".")) bySize.getOrPut(len) { mutableListOf() }.add(f)
        }
        val out = mutableListOf<JItem>()
        temp.forEach { out.add(JItem(it, sizeOf(it), 0)) }
        empty.forEach { out.add(JItem(it, 0, 1)) }
        apks.forEach { out.add(JItem(it, it.length(), 2)) }
        big.forEach { out.add(JItem(it, it.length(), 3)) }
        bySize.values.filter { it.size > 1 }.forEach { g ->
            prog(g[0].name)
            g.groupBy { try { qhash(it) } catch (_: Throwable) { it.path } }.values.filter { it.size > 1 }.forEach { gg ->
                gg.sortedBy { it.lastModified() }.drop(1).forEach { out.add(JItem(it, it.length(), 4)) }
            }
        }
        return out
    }

    fun cleanOwn(c: Context) {
        c.cacheDir.deleteRecursively(); c.externalCacheDir?.deleteRecursively()
    }
}

@Composable
fun CleanerScreen() {
    val c = LocalContext.current
    var tab by remember { mutableIntStateOf(0) }
    var apps by remember { mutableStateOf<List<AppStat>>(emptyList()) }
    var vol by remember { mutableStateOf<LongArray?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(Tick.n, reload) { withContext(Dispatchers.IO) { vol = Cln.volume(c); apps = Cln.apps(c) } }
    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tab) {
            listOf(tr("نظرة عامة", "Overview"), tr("ملفات غير مرغوبة", "Junk"), tr("التطبيقات", "Apps")).forEachIndexed { i, t ->
                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) })
            }
        }
        when (tab) {
            0 -> OverviewTab(vol, apps) { reload++ }
            1 -> JunkTab { reload++ }
            else -> AppsTab(apps)
        }
    }
}

@Composable
fun OverviewTab(vol: LongArray?, apps: List<AppStat>, onChanged: () -> Unit) {
    val c = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (vol == null) { LinearProgressIndicator(Modifier.fillMaxWidth()); return@Column }
        val total = vol[0]; val free = vol[1]; val used = total - free
        val appsT = apps.sumOf { it.total }
        val other = (used - appsT - vol[2] - vol[3] - vol[4]).coerceAtLeast(0)
        val parts = listOf(
            Triple(tr("التطبيقات", "Apps"), appsT, Color(0xFF3F7DE8)), Triple(tr("الصور", "Images"), vol[2], Color(0xFF2EB67D)),
            Triple(tr("الفيديو", "Video"), vol[3], Color(0xFFE8A33F)), Triple(tr("الصوت", "Audio"), vol[4], Color(0xFFB45EDB)),
            Triple(tr("أخرى", "Other"), other, Color(0xFF8A8A8A)), Triple(tr("فارغ", "Free"), free, Color(0xFF3A3A3A)))
        Text("${fmt(used)} / ${fmt(total)}", style = MaterialTheme.typography.headlineSmall)
        Text(tr("السعة الحقيقية للجهاز (كما تعرضها تطبيقات التنظيف)", "Real device capacity"), style = MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth().height(20.dp)) {
            parts.forEach { (_, v, col) -> if (v > 0) Box(Modifier.weight(v.toFloat()).fillMaxHeight().background(col)) }
        }
        parts.forEach { (n, v, col) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(12.dp).background(col)); Spacer(Modifier.width(8.dp))
                Text(n, Modifier.weight(1f)); Text(fmt(v))
            }
        }
        if (!Perms.usage(c)) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(tr("لعرض أحجام التطبيقات والصور والفيديو بدقة فعّل «الوصول للاستخدام».", "Enable Usage access to see accurate app and media sizes."))
                    Button(onClick = { Perms.open(c, Settings.ACTION_USAGE_ACCESS_SETTINGS, false) }) { Text(tr("تفعيل", "Enable")) }
                }
            }
        }
        val cache = apps.sumOf { it.cache }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tr("كاش التطبيقات: ${fmt(cache)}", "Apps cache: ${fmt(cache)}"), style = MaterialTheme.typography.titleMedium)
                if (!Sh.ready()) Text(tr("تنظيف كاش كل التطبيقات يحتاج Shizuku (فعّله من الإعدادات). بدونه ننظف كاش ToolBox فقط.", "Cleaning other apps' cache needs Shizuku. Without it only ToolBox cache is cleaned."), style = MaterialTheme.typography.bodySmall)
                Button(enabled = !busy, onClick = {
                    scope.launch {
                        busy = true
                        val before = withContext(Dispatchers.IO) { Cln.volume(c)[1] }
                        val r = withContext(Dispatchers.IO) { Cln.cleanOwn(c); if (Sh.ready()) Sh.trimCaches() else null }
                        val after = withContext(Dispatchers.IO) { Cln.volume(c)[1] }
                        msg = tr("تم. المساحة المحررة تقريباً: ", "Done. Freed about: ") + fmt((after - before).coerceAtLeast(0)) + (if (r != null && r.first != 0) "\n" + r.second.take(200) else "")
                        busy = false; onChanged()
                    }
                }) { Text(tr("تنظيف الكاش", "Clean cache")) }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (msg.isNotEmpty()) Text(msg)
            }
        }
    }
}

@Composable
fun JunkTab(onChanged: () -> Unit) {
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<JItem>?>(null) }
    var prog by remember { mutableStateOf("") }
    var scanning by remember { mutableStateOf(false) }
    val sel = remember { mutableStateListOf<String>() }
    var confirm by remember { mutableStateOf(false) }
    val names = listOf(tr("ملفات مؤقتة", "Temp files"), tr("مجلدات فارغة", "Empty folders"), tr("ملفات APK", "APK files"), tr("ملفات كبيرة", "Large files"), tr("ملفات مكررة", "Duplicates"))
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(enabled = !scanning, onClick = {
                scope.launch {
                    scanning = true; sel.clear(); items = null
                    val r = withContext(Dispatchers.IO) { Cln.scan(ROOT) { prog = it } }
                    items = r; r.filter { it.cat != 3 }.forEach { sel.add(it.file.path) }
                    scanning = false
                }
            }) { Text(tr("فحص", "Scan")) }
            Spacer(Modifier.width(12.dp))
            if (scanning) Text(prog, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
        }
        if (scanning) LinearProgressIndicator(Modifier.fillMaxWidth())
        val list = items
        if (list != null) {
            LazyColumn(Modifier.weight(1f)) {
                for (cat in 0..4) {
                    val g = list.filter { it.cat == cat }
                    if (g.isEmpty()) continue
                    item(key = "h$cat") {
                        val all = g.all { it.file.path in sel }
                        Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = all, onCheckedChange = { on -> g.forEach { sel.remove(it.file.path); if (on) sel.add(it.file.path) } })
                            Text("${names[cat]} (${g.size})", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                            Text(fmt(g.sumOf { it.size }))
                        }
                    }
                    items(g.take(300), key = { it.file.path }) { it ->
                        Row(Modifier.fillMaxWidth().clickable { if (it.file.path in sel) sel.remove(it.file.path) else sel.add(it.file.path) }.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = it.file.path in sel, onCheckedChange = { on -> if (on) sel.add(it.file.path) else sel.remove(it.file.path) })
                            Column(Modifier.weight(1f)) {
                                Text(it.file.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(it.file.parent?.removePrefix(ROOT.path) ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                            }
                            Text(fmt(it.size), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            val total = list.filter { it.file.path in sel }.sumOf { it.size }
            Button(modifier = Modifier.fillMaxWidth().padding(12.dp), enabled = sel.isNotEmpty(), onClick = { confirm = true }) {
                Icon(Icons.Default.Delete, null); Spacer(Modifier.width(8.dp)); Text(tr("حذف المحدد (${fmt(total)})", "Delete selected (${fmt(total)})"))
            }
        }
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text(tr("تأكيد الحذف", "Confirm delete")) },
        text = { Text(tr("سيتم حذف ${sel.size} عنصر نهائياً.", "${sel.size} items will be deleted permanently.")) },
        confirmButton = {
            TextButton(onClick = {
                confirm = false
                scope.launch {
                    withContext(Dispatchers.IO) { sel.toList().forEach { Cln.del(File(it)) } }
                    items = items?.filter { it.file.path !in sel }; sel.clear(); onChanged()
                }
            }) { Text(tr("حذف", "Delete")) }
        },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text(tr("إلغاء", "Cancel")) } }
    )
}

@Composable
fun AppsTab(apps: List<AppStat>) {
    val c = LocalContext.current
    var sort by remember { mutableIntStateOf(0) }
    val list = remember(apps, sort) { apps.sortedByDescending { when (sort) { 1 -> it.cache; 2 -> it.data; else -> it.total } } }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(tr("الحجم", "Size"), tr("الكاش", "Cache"), tr("البيانات", "Data")).forEachIndexed { i, t ->
                FilterChip(selected = sort == i, onClick = { sort = i }, label = { Text(t) })
            }
        }
        if (apps.isEmpty()) Text(tr("لا توجد بيانات. فعّل «الوصول للاستخدام» من الإعدادات.", "No data. Enable Usage access in Settings."), Modifier.padding(16.dp))
        LazyColumn(Modifier.weight(1f)) {
            items(list, key = { it.pkg }) { a ->
                Row(
                    Modifier.fillMaxWidth().clickable {
                        c.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${a.pkg}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(a.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(a.pkg, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(fmt(a.total), style = MaterialTheme.typography.titleSmall)
                        Text("${tr("كاش", "cache")} ${fmt(a.cache)}", style = MaterialTheme.typography.bodySmall)
                    }
                    IconButton(onClick = { c.startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:${a.pkg}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }) { Icon(Icons.Default.Delete, null) }
                }
            }
        }
    }
}
