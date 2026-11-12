package toolbox.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.dnd.DnDConstants
import java.awt.dnd.DropTarget
import java.awt.dnd.DropTargetAdapter
import java.awt.dnd.DropTargetDropEvent
import java.io.File
import javax.swing.JFileChooser

val HOME: String = System.getProperty("user.home")
var downDir by mutableStateOf(File(HOME, "Downloads/ToolBox").apply { mkdirs() })

fun chooseFiles(): List<File> {
    val ch = JFileChooser(); ch.isMultiSelectionEnabled = true; ch.fileSelectionMode = JFileChooser.FILES_AND_DIRECTORIES
    return if (ch.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) ch.selectedFiles.toList() else emptyList()
}

fun chooseDir(): File? {
    val ch = JFileChooser(downDir); ch.fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
    return if (ch.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) ch.selectedFile else null
}

fun pcClip(): String = try { Toolkit.getDefaultToolkit().systemClipboard.getData(DataFlavor.stringFlavor) as? String ?: "" } catch (_: Throwable) { "" }
fun setPcClip(t: String) { try { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(t), null) } catch (_: Throwable) {} }

fun main() = application {
    Window(onCloseRequest = { PcServer.stop(); exitApplication() }, title = "ToolBox PC") {
        LaunchedEffect(Unit) {
            window.contentPane.dropTarget = DropTarget(window.contentPane, DnDConstants.ACTION_COPY, object : DropTargetAdapter() {
                override fun drop(e: DropTargetDropEvent) {
                    try {
                        e.acceptDrop(DnDConstants.ACTION_COPY)
                        @Suppress("UNCHECKED_CAST") val fs = e.transferable.getTransferData(DataFlavor.javaFileListFlavor) as List<File>
                        if (Net.connected) fs.forEach { Xfer.sendTree(it, "") }
                        e.dropComplete(true)
                    } catch (_: Throwable) { e.dropComplete(false) }
                }
            })
            PcServer.start()
        }
        MaterialTheme(colorScheme = darkColorScheme()) { Surface(Modifier.fillMaxSize()) { RootUi() } }
    }
}

@Composable
fun RootUi() {
    var tab by remember { mutableIntStateOf(0) }
    val tabs = listOf("🔗" to tr("اتصال", "Connect"), "📤" to tr("إرسال", "Send"), "📂" to tr("ملفات الجوال", "Phone files"),
        "📱" to tr("التطبيقات", "Apps"), "🔄" to tr("مزامنة", "Sync"), "📝" to tr("مفكرة", "Notes"))
    Row(Modifier.fillMaxSize()) {
        NavigationRail {
            tabs.forEachIndexed { i, (ic, t) -> NavigationRailItem(selected = tab == i, onClick = { tab = i }, icon = { Text(ic, fontSize = 22.sp) }, label = { Text(t) }) }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { AR = !AR }) { Text(if (AR) "EN" else "ع") }
        }
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            if (Net.connected) Text("✔ ${Net.name}  (${Net.base})", color = Color(0xFF4CAF50))
            else if (tab != 0) Text(tr("غير متصل — اذهب لتبويب «اتصال»", "Not connected — open the Connect tab"), color = Color(0xFFFF9800))
            Spacer(Modifier.height(8.dp))
            when (tab) { 0 -> ConnectTab(); 1 -> SendTab(); 2 -> FilesTab(); 3 -> AppsTab(); 4 -> SyncTab(); else -> NotesTab() }
        }
    }
}

@Composable
fun ConnectTab() {
    val scope = rememberCoroutineScope()
    var found by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var pin by remember { mutableStateOf("") }
    var ip by remember { mutableStateOf("") }
    var link by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }
    fun search() { scope.launch { searching = true; found = withContext(Dispatchers.IO) { Net.discover() }; searching = false } }
    LaunchedEffect(Unit) { if (!Net.connected) search() }
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(tr("على الجوال: افتح ToolBox ← النقل ← تشغيل، ثم اكتب الـ PIN هنا.", "On the phone: ToolBox → Transfer → Start, then enter the PIN here."))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { search() }, enabled = !searching) { Text(if (searching) tr("جارٍ البحث…", "Searching…") else tr("بحث عن الجوالات", "Find phones")) }
            Spacer(Modifier.width(12.dp)); OutlinedTextField(pin, { pin = it }, label = { Text("PIN") }, singleLine = true, modifier = Modifier.width(140.dp))
        }
        found.forEach { (addr, n) ->
            Card(Modifier.fillMaxWidth().clickable {
                scope.launch { msg = if (withContext(Dispatchers.IO) { Net.connect(addr, 8765, pin) }) "" else tr("فشل الاتصال، تحقق من الـ PIN", "Failed, check the PIN") }
            }) { Row(Modifier.padding(12.dp)) { Text("📱 $n  —  $addr", Modifier.weight(1f)); Text(tr("اضغط للاتصال", "Click to connect")) } }
        }
        HorizontalDivider()
        Text(tr("أو يدوياً:", "Or manually:"))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(ip, { ip = it }, label = { Text("IP") }, singleLine = true, modifier = Modifier.width(200.dp))
            Spacer(Modifier.width(8.dp))
            Button(onClick = { scope.launch { msg = if (withContext(Dispatchers.IO) { Net.connect(ip.trim(), 8765, pin) }) "" else tr("فشل الاتصال", "Failed") } }) { Text(tr("اتصال", "Connect")) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(link, { link = it }, label = { Text(tr("أو الصق الرابط", "Or paste the link")) }, singleLine = true, modifier = Modifier.width(380.dp))
            Spacer(Modifier.width(8.dp))
            Button(onClick = { scope.launch { msg = if (withContext(Dispatchers.IO) { Net.connectLink(link) }) "" else tr("رابط غير صالح", "Invalid link") } }) { Text(tr("اتصال", "Connect")) }
        }
        if (msg.isNotEmpty()) Text(msg, color = Color(0xFFFF5252))
        if (Net.connected) OutlinedButton(onClick = { Net.disconnect() }) { Text(tr("قطع الاتصال", "Disconnect")) }
        Text(tr("لا يعمل البحث؟ شغّل الجوال على نفس الواي فاي أو فعّل نقطة اتصال في الجوال وصل الكمبيوتر بها، وتأكد أن جدار حماية ويندوز يسمح للبرنامج.", "Search not working? Same Wi-Fi, or use the phone hotspot, and allow the app in Windows Firewall."), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun Qr(text: String, px: Int = 220) {
    val m = remember(text) { QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 60, 60) }
    Canvas(Modifier.size(px.dp).background(Color.White)) {
        val s = size.width / m.width
        for (x in 0 until m.width) for (y in 0 until m.height) if (m.get(x, y)) drawRect(Color.Black, Offset(x * s, y * s), Size(s + 0.5f, s + 0.5f))
    }
}

@Composable
fun SendTab() {
    var remote by remember { mutableStateOf("") }
    var ipSel by remember { mutableIntStateOf(0) }
    val ips = remember { PcServer.ips() }
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(tr("إلى الجوال", "To phone"), style = MaterialTheme.typography.titleMedium)
            Text(tr("اسحب الملفات والمجلدات وأفلتها على النافذة، أو اخترها:", "Drag & drop files/folders onto this window, or choose:"))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = Net.connected, onClick = { chooseFiles().forEach { Xfer.sendTree(it, remote) } }) { Text(tr("اختيار ملفات/مجلد", "Choose files/folder")) }
                OutlinedButton(onClick = { try { Runtime.getRuntime().exec("fsquirt") } catch (_: Throwable) {} }) { Text("Bluetooth (Windows)") }
            }
            OutlinedTextField(remote, { remote = it }, label = { Text(tr("مجلد الوجهة بالجوال (اختياري)", "Phone destination folder (optional)")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            LazyColumn(Modifier.weight(1f)) {
                items(Xfer.items) { x ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Text("${x.status}  ${x.name}  ${fmt(x.done)}${if (x.total > 0) " / " + fmt(x.total) else ""}", maxLines = 1)
                        if (x.total > 0) LinearProgressIndicator(progress = { (x.done.toFloat() / x.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(tr("من الجوال (بدون تطبيق)", "From phone (no app)"), style = MaterialTheme.typography.titleMedium)
            val ip = ips.getOrNull(ipSel)
            if (PcServer.running && ip != null) {
                val url = "http://$ip:${PcServer.PORT}/?t=${PcServer.token}"
                Qr(url)
                Text(url, style = MaterialTheme.typography.bodySmall)
                Text(tr("امسح الـ QR بكاميرا الجوال، يفتح صفحة لرفع الصور والملفات إلى الكمبيوتر بسرعة الواي فاي.", "Scan with the phone camera to upload photos/files to this PC at Wi-Fi speed."), style = MaterialTheme.typography.bodySmall)
                if (ips.size > 1) Row { ips.forEachIndexed { i, a -> FilterChip(selected = ipSel == i, onClick = { ipSel = i }, label = { Text(a) }); Spacer(Modifier.width(4.dp)) } }
            } else Text(tr("الخادم غير متاح (المنفذ مشغول أو لا توجد شبكة)", "Server unavailable (port busy or no network)"))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("📁 ${PcServer.dir}", Modifier.weight(1f), maxLines = 1)
                TextButton(onClick = { chooseDir()?.let { PcServer.dir = it; downDir = it } }) { Text(tr("تغيير", "Change")) }
            }
            OutlinedButton(onClick = { chooseFiles().let { PcServer.shared.addAll(it.filter { f -> f.isFile }) } }) { Text(tr("مشاركة ملفات من الكمبيوتر للجوال", "Share PC files to the phone")) }
            Text(tr("وصل: ", "Received: ") + PcServer.received.take(6).joinToString(), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun FilesTab() {
    val scope = rememberCoroutineScope()
    var path by remember { mutableStateOf("") }
    var items by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var err by remember { mutableStateOf("") }
    var tick by remember { mutableIntStateOf(0) }
    var newDir by remember { mutableStateOf("") }
    LaunchedEffect(Net.base, path, tick) {
        if (!Net.connected) return@LaunchedEffect
        try { val r = withContext(Dispatchers.IO) { Net.ls(path) }; path = r.first.also { }; items = r.second; err = "" } catch (e: Throwable) { err = e.message ?: "error" }
    }
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { path = path.substringBeforeLast('/', "").ifEmpty { "/" } }) { Text("⬆") }
            Spacer(Modifier.width(8.dp)); Text(path, Modifier.weight(1f), maxLines = 1)
            OutlinedTextField(newDir, { newDir = it }, singleLine = true, label = { Text(tr("مجلد جديد", "New folder")) }, modifier = Modifier.width(160.dp))
            TextButton(onClick = { if (newDir.isNotBlank()) scope.launch { withContext(Dispatchers.IO) { Net.op("mkdir", "$path/$newDir") }; newDir = ""; tick++ } }) { Text("+") }
            TextButton(onClick = { chooseFiles().forEach { Xfer.sendTree(it, path) } }) { Text(tr("رفع هنا", "Upload here")) }
        }
        if (err.isNotEmpty()) Text(err, color = Color(0xFFFF5252))
        LazyColumn(Modifier.weight(1f)) {
            items(items) { j ->
                val n = j.getString("n"); val d = j.getBoolean("d"); val full = "$path/$n"
                Row(Modifier.fillMaxWidth().clickable { if (d) path = full }.padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (d) "📁" else "📄"); Spacer(Modifier.width(8.dp))
                    Text(n, Modifier.weight(1f), maxLines = 1)
                    if (!d) Text(fmt(j.getLong("s")), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = {
                        if (d) scope.launch { withContext(Dispatchers.IO) { downloadTree(full, File(downDir, n)) } }
                        else Xfer.get(full, File(downDir, n), j.getLong("s"))
                    }) { Text("⬇") }
                    TextButton(onClick = { scope.launch { withContext(Dispatchers.IO) { Net.op("rm", full) }; tick++ } }) { Text("🗑") }
                }
            }
        }
        Text("⬇ → $downDir", style = MaterialTheme.typography.bodySmall)
    }
}

fun downloadTree(remote: String, dest: File) {
    val (_, list) = Net.ls(remote)
    list.forEach { j -> val n = j.getString("n"); if (j.getBoolean("d")) downloadTree("$remote/$n", File(dest, n)) else Xfer.get("$remote/$n", File(dest, n), j.getLong("s")) }
}

@Composable
fun AppsTab() {
    var apps by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var q by remember { mutableStateOf("") }
    var err by remember { mutableStateOf("") }
    LaunchedEffect(Net.base) { if (Net.connected) try { apps = withContext(Dispatchers.IO) { Net.apps() } } catch (e: Throwable) { err = e.message ?: "" } }
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(q, { q = it }, label = { Text(tr("بحث", "Search")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        if (err.isNotEmpty()) Text(err)
        LazyColumn(Modifier.weight(1f)) {
            items(apps.filter { q.isBlank() || it.getString("name").contains(q, true) }) { a ->
                Row(Modifier.fillMaxWidth().padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text(a.getString("name")); Text("${a.getString("pkg")} • ${a.getString("ver")} • ${fmt(a.getLong("size"))}", style = MaterialTheme.typography.bodySmall) }
                    Button(onClick = { Xfer.app(a.getString("pkg"), false, File(downDir, "Apps")) }) { Text(if (a.getBoolean("splits")) "XAPK" else "APK") }
                    if (a.getBoolean("obb")) { Spacer(Modifier.width(6.dp)); OutlinedButton(onClick = { Xfer.app(a.getString("pkg"), true, File(downDir, "Apps")) }) { Text("+OBB") } }
                }
            }
        }
    }
}

@Composable
fun SyncTab() {
    var auto by remember { mutableStateOf(false) }
    var clip by remember { mutableStateOf(false) }
    var dir by remember { mutableStateOf(File(downDir, "Camera")) }
    var lastLog by remember { mutableStateOf("") }
    LaunchedEffect(auto, Net.base) {
        if (!auto || !Net.connected) return@LaunchedEffect
        var since = System.currentTimeMillis() / 1000 - 5
        val seen = HashSet<String>()
        while (true) {
            try {
                val j = JSONObject(withContext(Dispatchers.IO) { Net.text("/api/recent", "since" to since.toString()) }).getJSONArray("items")
                for (i in 0 until j.length()) {
                    val o = j.getJSONObject(i); val p = o.getString("p"); since = maxOf(since, o.getLong("t"))
                    if (o.getLong("s") > 0 && seen.add(p)) { Xfer.get(p, File(dir, p.substringAfterLast('/')), o.getLong("s")); lastLog = p.substringAfterLast('/') }
                }
            } catch (_: Throwable) {}
            delay(5000)
        }
    }
    LaunchedEffect(clip, Net.base) {
        if (!clip || !Net.connected) return@LaunchedEffect
        var lastPhone = ""; var lastPc = pcClip()
        while (true) {
            try {
                withContext(Dispatchers.IO) {
                    val ph = Net.clipGet(); val pc = pcClip()
                    if (ph.isNotEmpty() && ph != lastPhone) { lastPhone = ph; if (ph != pc) setPcClip(ph); lastPc = ph }
                    else if (pc.isNotEmpty() && pc != lastPc) { lastPc = pc; Net.clipPut(pc); lastPhone = pc }
                }
            } catch (_: Throwable) {}
            delay(1500)
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { Switch(auto, { auto = it }); Spacer(Modifier.width(8.dp)); Text(tr("استقبال الصور والفيديوهات الجديدة تلقائياً", "Auto-receive new photos & videos")) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("📁 $dir", Modifier.weight(1f)); TextButton(onClick = { chooseDir()?.let { dir = it } }) { Text(tr("تغيير", "Change")) }
        }
        if (lastLog.isNotEmpty()) Text("⬇ $lastLog")
        Row(verticalAlignment = Alignment.CenterVertically) { Switch(clip, { clip = it }); Spacer(Modifier.width(8.dp)); Text(tr("مزامنة الحافظة (النصوص المنسوخة)", "Clipboard sync (copied text)")) }
        Text(tr("ملاحظة: أندرويد يسمح بقراءة حافظة الجوال فقط والتطبيق ظاهر على الشاشة. الإرسال من الكمبيوتر للجوال يعمل دائماً.", "Android only lets apps read the clipboard while visible. PC → phone always works."), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun NotesTab() {
    val dir = remember { File(HOME, "ToolBox/Notes").apply { mkdirs() } }
    var files by remember { mutableStateOf(dir.listFiles()?.filter { it.extension == "txt" }?.sortedBy { it.name } ?: emptyList()) }
    var cur by remember { mutableStateOf<File?>(null) }
    var text by remember { mutableStateOf("") }
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.width(200.dp)) {
            Button(onClick = { val f = File(dir, "note_${System.currentTimeMillis()}.txt"); f.writeText(""); files = dir.listFiles()!!.filter { it.extension == "txt" }.sortedBy { it.name }; cur = f; text = "" }) { Text(tr("+ جديدة", "+ New")) }
            LazyColumn { items(files) { f -> Text(f.nameWithoutExtension, Modifier.fillMaxWidth().clickable { cur = f; text = f.readText() }.padding(6.dp), maxLines = 1) } }
        }
        Column(Modifier.weight(1f)) {
            val f = cur
            if (f == null) Text(tr("اختر ملاحظة", "Pick a note")) else {
                Row { Text(f.name, Modifier.weight(1f)); TextButton(onClick = { f.delete(); cur = null; files = dir.listFiles()?.filter { it.extension == "txt" }?.sortedBy { it.name } ?: emptyList() }) { Text("🗑") } }
                OutlinedTextField(text, { text = it; f.writeText(it) }, Modifier.fillMaxSize())
            }
        }
    }
}
