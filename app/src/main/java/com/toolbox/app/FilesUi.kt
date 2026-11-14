package com.toolbox.app

import android.content.Context
import android.os.StatFs
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.toolbox.app.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class Pane(start: String) {
    var cwd by mutableStateOf(start)
    var list by mutableStateOf(listOf<FE>())
    var shell by mutableStateOf(false)
    var err by mutableStateOf("")
    val sel = mutableStateListOf<String>()
    var anchor by mutableStateOf<String?>(null)
}

private fun sorter(key: Int, asc: Boolean, ff: Boolean): Comparator<FE> {
    val base: Comparator<FE> = when (key) {
        1 -> compareBy<FE> { it.size }.thenBy { it.name.lowercase() }
        2 -> compareBy<FE> { it.mod }.thenBy { it.name.lowercase() }
        3 -> compareBy<FE> { File(it.path).extension.lowercase() }.thenBy { it.name.lowercase() }
        else -> compareBy<FE> { it.name.lowercase() }
    }
    val dir = if (asc) base else base.reversed()
    return if (ff) compareBy<FE> { !it.dir }.then(dir) else dir
}

private fun visible(l: List<FE>, hidden: Boolean, q: String?, cmp: Comparator<FE>): List<FE> =
    l.filter { (hidden || !it.name.startsWith(".")) && (q.isNullOrEmpty() || it.name.contains(q, true)) }.sortedWith(cmp)

private fun typeTint(x: String): Pair<Color, ImageVector> = when {
    x in AUD -> AccAudio to Icons.Rounded.MusicNote
    x in VID -> AccVideo to Icons.Rounded.PlayArrow
    x in IMG -> AccImages to Icons.Rounded.Image
    x == "apk" || x == "xapk" || x == "apks" -> AccApk to Icons.Rounded.Android
    x in ARC -> AccFiles to Icons.Rounded.Archive
    x == "pdf" -> AccEditor to Icons.Rounded.PictureAsPdf
    x in TXT -> AccNotes to Icons.Rounded.Description
    else -> AccSettings to Icons.Rounded.InsertDriveFile
}

private fun folderBadge(n: String): ImageVector? = when (n.lowercase()) {
    "android" -> Icons.Rounded.Android
    "dcim" -> Icons.Rounded.PhotoCamera
    "download", "downloads" -> Icons.Rounded.Download
    "documents" -> Icons.Rounded.Work
    "movies", "video", "videos" -> Icons.Rounded.Movie
    "music" -> Icons.Rounded.MusicNote
    "pictures", "images" -> Icons.Rounded.Image
    "toolbox" -> Icons.Rounded.Build
    "whatsapp", "telegram" -> Icons.Rounded.Chat
    else -> null
}

@Composable
private fun FolderGlyph() {
    Canvas(Modifier.size(46.dp, 38.dp)) {
        val w = size.width; val h = size.height
        val r = 8.dp.toPx()
        drawRoundRect(Color(0xFFF08A00), Offset(0f, 0f), Size(w * 0.46f, h * 0.34f), CornerRadius(r))
        drawRoundRect(Color(0xFFF59E0B), Offset(0f, h * 0.12f), Size(w, h * 0.88f), CornerRadius(r))
        drawRoundRect(Brush.verticalGradient(listOf(Color(0xFFFFBE45), Color(0xFFFFA726))), Offset(0f, h * 0.30f), Size(w, h * 0.70f), CornerRadius(r))
    }
}

@Composable
private fun FileIcon(e: FE, thumb: Boolean, selected: Boolean, onClick: () -> Unit) {
    val x = File(e.path).extension.lowercase()
    Box(Modifier.size(52.dp).clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        when {
            selected -> Box(Modifier.fillMaxSize().padding(4.dp).clip(CircleShape).background(Lavender), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Check, null, tint = OnLavender)
            }
            e.dir -> {
                FolderGlyph()
                val b = folderBadge(e.name)
                if (b != null) Icon(b, null, tint = Color.White.copy(alpha = 0.95f), modifier = Modifier.size(18.dp).offset(y = 4.dp))
            }
            thumb && (x in IMG || x in VID) -> AsyncImage(File(e.path), null, Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Crop)
            else -> {
                val t = typeTint(x)
                Box(Modifier.fillMaxSize().padding(3.dp).clip(RoundedCornerShape(12.dp))
                    .background(Brush.verticalGradient(listOf(t.first.copy(alpha = 0.32f), t.first.copy(alpha = 0.12f)))), contentAlignment = Alignment.Center) {
                    Icon(t.second, null, tint = t.first)
                }
            }
        }
    }
}

private fun toggleSel(p: Pane, e: FE) { if (e.path in p.sel) p.sel.remove(e.path) else p.sel.add(e.path); p.anchor = e.path }

private fun rangeSel(p: Pane, shown: List<FE>, e: FE) {
    val a = shown.indexOfFirst { it.path == p.anchor }
    val b = shown.indexOfFirst { it.path == e.path }
    if (a < 0 || b < 0) { toggleSel(p, e); return }
    for (i in minOf(a, b)..maxOf(a, b)) if (shown[i].path !in p.sel) p.sel.add(shown[i].path)
    p.anchor = e.path
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PaneView(p: Pane, shown: List<FE>, active: Boolean, onActivate: () -> Unit, onPath: () -> Unit, modifier: Modifier) {
    val nav = LocalNav.current
    val c = LocalContext.current
    Column(modifier.then(if (active) Modifier.border(1.dp, Lavender.copy(alpha = 0.5f), RoundedCornerShape(12.dp)) else Modifier)) {
        Row(Modifier.fillMaxWidth().clickable { onActivate(); onPath() }.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onActivate(); p.cwd = File(p.cwd).parent ?: p.cwd; p.sel.clear() }) { Icon(Icons.Rounded.ArrowUpward, null, tint = TextHi) }
            Text(p.cwd.removePrefix(ROOT.path).ifEmpty { "/" }, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, color = TextMid, style = MaterialTheme.typography.bodyMedium)
            Text("${shown.size}", color = TextLow, style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.width(8.dp))
        }
        if (p.err.isNotEmpty()) Text(p.err, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.error)
        LazyColumn(Modifier.weight(1f)) {
            items(shown, key = { it.path }) { e ->
                val selected = e.path in p.sel
                Row(
                    Modifier.fillMaxWidth()
                        .combinedClickable(
                            onClick = { onActivate(); if (p.sel.isNotEmpty()) toggleSel(p, e) else if (e.dir) { p.cwd = e.path } else openPath(nav, c, e.path) },
                            onLongClick = { onActivate(); if (p.sel.isEmpty()) toggleSel(p, e) else rangeSel(p, shown, e) })
                        .background(if (selected) Lavender.copy(alpha = 0.16f) else Color.Transparent)
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FileIcon(e, !p.shell, selected) { onActivate(); toggleSel(p, e) }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(e.name, color = TextHi, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                        val d = if (e.mod > 0) SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(e.mod)) else ""
                        Text(if (e.dir || e.size == 0L && p.shell) d else "${fmt(e.size)}  $d", color = TextMid, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen(start: String) {
    val c = LocalContext.current
    val scope = rememberCoroutineScope()
    val pr = c.getSharedPreferences("tb", Context.MODE_PRIVATE)
    if (lastDir.isEmpty()) lastDir = ROOT.path
    val p0 = remember { Pane(start.ifEmpty { lastDir }) }
    val p1 = remember { Pane(pr.getString("fm_dir2", ROOT.path) ?: ROOT.path) }
    var dual by remember { mutableStateOf(pr.getBoolean("fm_dual", false)) }
    var act by remember { mutableIntStateOf(0) }
    var sortKey by remember { mutableIntStateOf(pr.getInt("fm_sort", 0)) }
    var asc by remember { mutableStateOf(pr.getBoolean("fm_asc", true)) }
    var ff by remember { mutableStateOf(pr.getBoolean("fm_ff", true)) }
    var hidden by remember { mutableStateOf(pr.getBoolean("fm_hidden", false)) }
    var q by remember { mutableStateOf<String?>(null) }
    var sortMenu by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf(false) }
    var dlg by remember { mutableStateOf("") }
    var input by remember { mutableStateOf("") }
    var infoPath by remember { mutableStateOf("") }
    var fmtZip by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf("") }
    var tick by remember { mutableIntStateOf(0) }
    var favs by remember { mutableStateOf((pr.getStringSet("fm_fav", emptySet()) ?: emptySet()).toList()) }
    val cur = if (act == 0 || !dual) p0 else p1
    val other = if (cur === p0) p1 else p0

    suspend fun load(p: Pane) {
        p.err = ""
        val r = withContext(Dispatchers.IO) { listDir(p.cwd) }
        p.list = r.first; p.shell = r.second; p.err = r.third
    }
    LaunchedEffect(p0.cwd, tick, Tick.n) { lastDir = p0.cwd; load(p0) }
    LaunchedEffect(p1.cwd, tick, Tick.n, dual) { if (dual) { pr.edit().putString("fm_dir2", p1.cwd).apply(); load(p1) } }
    BackHandler(enabled = cur.sel.isNotEmpty() || (cur.cwd != ROOT.path && File(cur.cwd).parent != null)) {
        if (cur.sel.isNotEmpty()) cur.sel.clear() else cur.cwd = File(cur.cwd).parent ?: cur.cwd
    }
    val qq = q
    val cmp = remember(sortKey, asc, ff) { sorter(sortKey, asc, ff) }
    val s0 = remember(p0.list, cmp, hidden, qq) { visible(p0.list, hidden, qq, cmp) }
    val s1 = remember(p1.list, cmp, hidden, qq) { visible(p1.list, hidden, qq, cmp) }
    val curShown = if (cur === p0) s0 else s1
    fun savePrefs() { pr.edit().putInt("fm_sort", sortKey).putBoolean("fm_asc", asc).putBoolean("fm_ff", ff).putBoolean("fm_hidden", hidden).apply() }
    fun run(label: String, work: () -> Unit) {
        scope.launch { busy = label; withContext(Dispatchers.IO) { try { work() } catch (t: Throwable) { toast("Error: ${t.message}") } }; busy = ""; p0.sel.clear(); p1.sel.clear(); tick++ }
    }
    fun addFav(path: String) {
        val n = (favs + path).distinct(); favs = n
        pr.edit().putStringSet("fm_fav", n.toSet()).apply(); toast(tr("أُضيف للمفضلة", "Added to favorites"))
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val roots = mutableListOf(tr("الداخلي", "Internal") to ROOT.path, "Download" to "${ROOT.path}/Download", "DCIM" to "${ROOT.path}/DCIM",
                "Pictures" to "${ROOT.path}/Pictures", "Android/data" to "${ROOT.path}/Android/data", "Android/obb" to "${ROOT.path}/Android/obb", "ToolBox" to "${ROOT.path}/ToolBox")
            File("/storage").listFiles()?.filter { it.isDirectory && it.name != "emulated" && it.name != "self" }?.forEach { roots.add("SD ${it.name}" to it.path) }
            roots.forEach { (n, p) -> AssistChip(onClick = { cur.cwd = p; cur.sel.clear() }, label = { Text(n, color = TextHi) }) }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { sheet = true }) { Icon(Icons.Rounded.Bookmarks, null, tint = TextHi) }
            IconButton(onClick = { q = if (q == null) "" else null }) { Icon(Icons.Rounded.Search, null, tint = TextHi) }
            Box {
                IconButton(onClick = { sortMenu = true }) { Icon(Icons.Rounded.SortByAlpha, null, tint = TextHi) }
                DropdownMenu(sortMenu, { sortMenu = false }) {
                    listOf(tr("الاسم", "Name"), tr("الحجم", "Size"), tr("التاريخ", "Date"), tr("النوع", "Type")).forEachIndexed { i, s ->
                        DropdownMenuItem(text = { Text(s) }, leadingIcon = { if (sortKey == i) Icon(Icons.Rounded.Check, null) }, onClick = { sortKey = i; savePrefs() })
                    }
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text(if (asc) tr("تصاعدي", "Ascending") else tr("تنازلي", "Descending")) },
                        leadingIcon = { Icon(if (asc) Icons.Rounded.ArrowUpward else Icons.Rounded.ArrowDownward, null) }, onClick = { asc = !asc; savePrefs() })
                    DropdownMenuItem(text = { Text(tr("المجلدات أولاً", "Folders first")) }, leadingIcon = { if (ff) Icon(Icons.Rounded.Check, null) }, onClick = { ff = !ff; savePrefs() })
                    DropdownMenuItem(text = { Text(tr("إظهار المخفية", "Show hidden")) }, leadingIcon = { if (hidden) Icon(Icons.Rounded.Check, null) }, onClick = { hidden = !hidden; savePrefs() })
                }
            }
            IconButton(onClick = { input = ""; dlg = "newdir" }) { Icon(Icons.Rounded.CreateNewFolder, null, tint = TextHi) }
            IconButton(onClick = { tick++ }) { Icon(Icons.Rounded.Refresh, null, tint = TextHi) }
            IconButton(onClick = { dual = !dual; act = 0; pr.edit().putBoolean("fm_dual", dual).apply() }) {
                Icon(Icons.Rounded.VerticalSplit, null, tint = if (dual) Lavender else TextHi)
            }
        }
        if (q != null) OutlinedTextField(q ?: "", { q = it }, Modifier.fillMaxWidth().padding(horizontal = 8.dp), singleLine = true)
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val land = maxWidth > maxHeight
            if (dual && land) Row(Modifier.fillMaxSize()) {
                PaneView(p0, s0, act == 0, { act = 0 }, { sheet = true }, Modifier.weight(1f).fillMaxHeight())
                Spacer(Modifier.width(6.dp))
                PaneView(p1, s1, act == 1, { act = 1 }, { sheet = true }, Modifier.weight(1f).fillMaxHeight())
            } else if (dual) Column(Modifier.fillMaxSize()) {
                PaneView(p0, s0, act == 0, { act = 0 }, { sheet = true }, Modifier.weight(1f).fillMaxWidth())
                Spacer(Modifier.height(6.dp))
                PaneView(p1, s1, act == 1, { act = 1 }, { sheet = true }, Modifier.weight(1f).fillMaxWidth())
            } else PaneView(p0, s0, true, { act = 0 }, { sheet = true }, Modifier.fillMaxSize())
        }
        if (cur.sel.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().background(Ink2Solid).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                Text(" ${cur.sel.size} ", color = TextHi, style = MaterialTheme.typography.titleSmall)
                IconButton(onClick = { FmClip.files = cur.sel.toList(); FmClip.move = false; cur.sel.clear() }) { Icon(Icons.Rounded.ContentCopy, null, tint = TextHi) }
                IconButton(onClick = { FmClip.files = cur.sel.toList(); FmClip.move = true; cur.sel.clear() }) { Icon(Icons.Rounded.ContentCut, null, tint = TextHi) }
                if (dual) {
                    IconButton(onClick = { val l = cur.sel.toList(); val o = other; run(tr("جارٍ النسخ…", "Copying…")) { l.forEach { fsPut(File(it), File(o.cwd), false, o.shell) } } }) { Icon(Icons.Rounded.FileCopy, null, tint = Lavender) }
                    IconButton(onClick = { val l = cur.sel.toList(); val o = other; run(tr("جارٍ النقل…", "Moving…")) { l.forEach { fsPut(File(it), File(o.cwd), true, o.shell) } } }) { Icon(Icons.Rounded.DriveFileMove, null, tint = Lavender) }
                }
                IconButton(onClick = { dlg = "delete" }) { Icon(Icons.Rounded.Delete, null, tint = TextHi) }
                if (cur.sel.size == 1) IconButton(onClick = { input = File(cur.sel[0]).name; dlg = "rename" }) { Icon(Icons.Rounded.Edit, null, tint = TextHi) }
                IconButton(onClick = { input = "archive"; dlg = "zip" }) { Icon(Icons.Rounded.Archive, null, tint = TextHi) }
                IconButton(onClick = { shareFiles(c, cur.sel.map { File(it) }) }) { Icon(Icons.Rounded.Share, null, tint = TextHi) }
                if (cur.sel.size == 1) IconButton(onClick = { infoPath = cur.sel[0]; dlg = "info" }) { Icon(Icons.Rounded.Info, null, tint = TextHi) }
                IconButton(onClick = { addFav(cur.sel.firstOrNull() ?: cur.cwd) }) { Icon(Icons.Rounded.BookmarkAdd, null, tint = TextHi) }
                IconButton(onClick = { cur.sel.clear(); cur.sel.addAll(curShown.map { it.path }) }) { Icon(Icons.Rounded.SelectAll, null, tint = TextHi) }
                IconButton(onClick = { cur.sel.clear() }) { Icon(Icons.Rounded.Close, null, tint = TextHi) }
            }
        } else if (FmClip.files.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().background(Ink2Solid).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${FmClip.files.size} ${if (FmClip.move) tr("للنقل", "to move") else tr("للنسخ", "to copy")}", Modifier.weight(1f), color = TextHi)
                TextButton(onClick = { FmClip.files = emptyList(); tick++ }) { Text(tr("إلغاء", "Cancel")) }
                Button(onClick = { val fs = FmClip.files; val mv = FmClip.move; val t = cur; run(tr("جارٍ اللصق…", "Pasting…")) { fs.forEach { fsPut(File(it), File(t.cwd), mv, t.shell) }; if (mv) FmClip.files = emptyList() } }) { Text(tr("لصق هنا", "Paste here")) }
            }
        }
    }

    if (sheet) ModalBottomSheet(onDismissRequest = { sheet = false }) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            val st = remember { try { val s = StatFs(ROOT.path); Pair((s.totalBytes - s.availableBytes) / 1e9f, s.totalBytes / 1e9f) } catch (_: Throwable) { Pair(0f, 1f) } }
            Row(Modifier.fillMaxWidth().clickable { cur.cwd = ROOT.path; cur.sel.clear(); sheet = false }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.PhoneAndroid, null, tint = Lavender)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(tr("ذاكرة الجهاز", "Device storage"), style = MaterialTheme.typography.titleMedium)
                    Text("%.1f / %.1f GB".format(Locale.US, st.first, st.second), style = MaterialTheme.typography.bodySmall)
                }
            }
            val std = listOf("Download", "Music", "Documents", "Pictures", "DCIM", "Movies", "Android/data", "Android/obb", "ToolBox")
            std.forEach { n ->
                val path = "${ROOT.path}/$n"
                Row(Modifier.fillMaxWidth().clickable { cur.cwd = path; cur.sel.clear(); sheet = false }.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(folderBadge(n.substringBefore('/')) ?: Icons.Rounded.Folder, null, tint = AccFiles)
                    Spacer(Modifier.width(12.dp))
                    Column { Text(n); Text(path, style = MaterialTheme.typography.bodySmall) }
                }
            }
            if (favs.isNotEmpty()) Text(tr("المفضلة", "Favorites"), Modifier.padding(16.dp, 12.dp, 16.dp, 4.dp), style = MaterialTheme.typography.labelLarge, color = Lavender)
            favs.forEach { path ->
                Row(Modifier.fillMaxWidth().clickable {
                    cur.cwd = if (File(path).isDirectory) path else (File(path).parent ?: path); cur.sel.clear(); sheet = false
                }.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Bookmark, null, tint = AccNotes)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) { Text(File(path).name.ifEmpty { path }, maxLines = 1, overflow = TextOverflow.Ellipsis); Text(path, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    IconButton(onClick = { val n = favs - path; favs = n; pr.edit().putStringSet("fm_fav", n.toSet()).apply() }) { Icon(Icons.Rounded.Close, null) }
                }
            }
        }
    }

    if (busy.isNotEmpty()) AlertDialog(onDismissRequest = {}, confirmButton = {}, title = { Text(busy) }, text = { LinearProgressIndicator(Modifier.fillMaxWidth()) })
    if (dlg == "info") {
        val f = File(infoPath)
        val sz by produceState(-1L, infoPath) {
            value = withContext(Dispatchers.IO) { if (f.isFile) f.length() else try { f.walkTopDown().filter { it.isFile }.sumOf { it.length() } } catch (_: Throwable) { 0L } }
        }
        AlertDialog(onDismissRequest = { dlg = "" }, confirmButton = { TextButton(onClick = { dlg = "" }) { Text("OK") } },
            title = { Text(f.name) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(f.path, style = MaterialTheme.typography.bodySmall)
                    Text(tr("الحجم: ", "Size: ") + if (sz < 0) "…" else fmt(sz))
                    Text(tr("التعديل: ", "Modified: ") + SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(f.lastModified())))
                    if (f.isDirectory) Text(tr("العناصر: ", "Items: ") + (f.list()?.size ?: 0))
                }
            })
    }
    if (dlg == "delete") AlertDialog(
        onDismissRequest = { dlg = "" }, title = { Text(tr("حذف؟", "Delete?")) }, text = { Text(tr("حذف ${cur.sel.size} عنصر نهائياً.", "Delete ${cur.sel.size} items permanently.")) },
        confirmButton = { TextButton(onClick = { dlg = ""; val l = cur.sel.toList(); run(tr("جارٍ الحذف…", "Deleting…")) { l.forEach { Cln.del(File(it)) } } }) { Text(tr("حذف", "Delete")) } },
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
                val d = dlg; dlg = ""; val name = input.trim(); val l = cur.sel.toList(); val fz = fmtZip; val cwd = cur.cwd
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
