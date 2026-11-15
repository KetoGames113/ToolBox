package com.toolbox.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.toolbox.app.ui.theme.TextMid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class Target(val name: String, val ip: String, val port: Int, val pc: Boolean, var secret: String = "")

/** إرسال سريع: اكتشاف الأجهزة على الشبكة (جوال أو كمبيوتر ToolBox) ثم رفع الملفات مباشرة بعدة اتصالات متوازية. */
object QuickSend {
    var sending by mutableStateOf(false); private set
    var total by mutableLongStateOf(0L); private set
    var done by mutableLongStateOf(0L); private set
    var speed by mutableLongStateOf(0L); private set
    var status by mutableStateOf(""); private set
    private val cancel = AtomicBoolean(false)
    fun cancel() { cancel.set(true) }
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    fun parseQr(text: String): Target? = try {
        val u = Uri.parse(text)
        val host = u.host
        val port = if (u.port > 0) u.port else 80
        val pin = u.getQueryParameter("pin"); val t = u.getQueryParameter("t")
        if (host == null) null
        else if (pin != null) Target("Phone", host, port, false, pin)
        else if (t != null) Target("PC", host, port, true, t)
        else null
    } catch (_: Throwable) { null }

    private fun broadcastAddresses(): List<InetAddress> {
        val l = ArrayList<InetAddress>()
        try {
            for (n in NetworkInterface.getNetworkInterfaces()) {
                if (!n.isUp || n.isLoopback) continue
                for (a in n.interfaceAddresses) a.broadcast?.let { l.add(it) }
            }
        } catch (_: Throwable) {}
        try { l.add(InetAddress.getByName("255.255.255.255")) } catch (_: Throwable) {}
        return l.distinct()
    }

    suspend fun scan(): List<Target> = withContext(Dispatchers.IO) {
        val found = LinkedHashMap<String, Target>()
        val mine = Transfer.ips().toSet()
        try {
            val s = DatagramSocket(); s.broadcast = true; s.soTimeout = 400
            val msg = "TOOLBOX_DISCOVER".toByteArray()
            repeat(2) { for (a in broadcastAddresses()) try { s.send(DatagramPacket(msg, msg.size, a, 45454)) } catch (_: Throwable) {} }
            val end = System.currentTimeMillis() + 1800
            val buf = ByteArray(512)
            while (System.currentTimeMillis() < end) {
                try {
                    val p = DatagramPacket(buf, buf.size); s.receive(p)
                    val t = String(p.data, 0, p.length).split("|")
                    val ip = p.address.hostAddress
                    if (t.size >= 3 && t[0] == "TOOLBOX_HERE" && ip != null && ip !in mine) found[ip] = Target(t[1], ip, t[2].toIntOrNull() ?: 8765, t.getOrNull(3) == "pc")
                } catch (_: SocketTimeoutException) {}
            }
            s.close()
        } catch (_: Throwable) {}
        found.values.toList()
    }

    private fun nameSize(c: Context, u: Uri): Pair<String, Long> {
        var n = "file"; var s = -1L
        try {
            c.contentResolver.query(u, null, null, null, null)?.use { q ->
                if (q.moveToFirst()) {
                    val i = q.getColumnIndex(OpenableColumns.DISPLAY_NAME); if (i >= 0) n = q.getString(i) ?: n
                    val j = q.getColumnIndex(OpenableColumns.SIZE); if (j >= 0 && !q.isNull(j)) s = q.getLong(j)
                }
            }
        } catch (_: Throwable) {}
        return Pair(n, s)
    }

    private fun upload(c: Context, t: Target, uri: Uri, name: String, size0: Long, onBytes: (Int) -> Unit): Int {
        var size = size0
        var tmp: File? = null
        var open: () -> java.io.InputStream? = { c.contentResolver.openInputStream(uri) }
        if (size < 0) { val f = copyToCache(c, uri); tmp = f; size = f.length(); open = { f.inputStream() } }
        val url = if (t.pc) "http://${t.ip}:${t.port}/put?t=${enc(t.secret)}&name=${enc(name)}"
        else "http://${t.ip}:${t.port}/api/put?pin=${enc(t.secret)}&name=${enc(name)}"
        val con = URL(url).openConnection() as HttpURLConnection
        try {
            con.requestMethod = "PUT"; con.doOutput = true; con.connectTimeout = 5000; con.readTimeout = 120000
            con.setFixedLengthStreamingMode(size)
            open()?.use { inp ->
                con.outputStream.use { out ->
                    val buf = ByteArray(1 shl 18)
                    while (true) {
                        if (cancel.get()) throw java.io.InterruptedIOException("cancelled")
                        val n = inp.read(buf); if (n < 0) break
                        out.write(buf, 0, n); onBytes(n)
                    }
                }
            }
            return con.responseCode
        } finally { try { con.disconnect() } catch (_: Throwable) {}; tmp?.delete() }
    }

    suspend fun send(c: Context, t: Target, uris: List<Uri>) {
        if (sending) return
        cancel.set(false); sending = true; done = 0; speed = 0; status = ""
        withContext(Dispatchers.IO) {
            val items = uris.map { u -> val ns = nameSize(c, u); Triple(u, ns.first, ns.second) }
            total = items.sumOf { it.third.coerceAtLeast(0L) }
            val got = AtomicLong(0); val ok = AtomicInteger(0); val badPin = AtomicBoolean(false)
            val ticker = launch {
                var last = 0L; var lt = System.nanoTime()
                while (isActive) {
                    delay(500)
                    val now = got.get(); val t2 = System.nanoTime()
                    speed = ((now - last) * 1e9 / (t2 - lt)).toLong(); last = now; lt = t2; done = now
                }
            }
            val pool = Executors.newFixedThreadPool(minOf(3, items.size.coerceAtLeast(1)))
            try {
                pool.invokeAll(items.map { (u, n, s) ->
                    Callable<Unit> {
                        try {
                            val code = upload(c, t, u, n, s) { b -> got.addAndGet(b.toLong()) }
                            if (code in 200..299) ok.incrementAndGet() else if (code == 401) badPin.set(true)
                        } catch (_: Throwable) {}
                    }
                })
            } finally { pool.shutdown() }
            ticker.cancel(); done = got.get(); speed = 0
            status = when {
                cancel.get() -> tr("أُلغي الإرسال", "Cancelled")
                badPin.get() -> tr("رمز PIN غير صحيح", "Wrong PIN")
                ok.get() == items.size -> tr("✔ تم إرسال ${ok.get()} ملف", "✔ Sent ${ok.get()} file(s)")
                else -> tr("أُرسل ${ok.get()} من ${items.size} (تحقق من الاتصال)", "Sent ${ok.get()} of ${items.size} (check connection)")
            }
        }
        sending = false
    }
}

@Composable
fun QuickSendCard() {
    val c = LocalContext.current
    val scope = rememberCoroutineScope()
    var peers by remember { mutableStateOf(listOf<Target>()) }
    var scanning by remember { mutableStateOf(false) }
    var target by remember { mutableStateOf<Target?>(null) }
    var askCode by remember { mutableStateOf<Target?>(null) }
    var manual by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf("") }
    var ip by remember { mutableStateOf("") }
    var isPc by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (Transfer.server?.running != true && Perms.files(c)) ContextCompat.startForegroundService(c, Intent(c, TransferService::class.java))
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { us ->
        val t = target
        if (t != null && us.isNotEmpty()) scope.launch { QuickSend.send(c, t, us) }
    }
    fun go(t: Target) { target = t; picker.launch("*/*") }
    val scanQr = rememberLauncherForActivityResult(ScanContract()) { r ->
        val txt = r.contents
        if (txt != null) { val t = QuickSend.parseQr(txt); if (t != null) go(t) else toast(tr("رمز QR غير مدعوم", "Unsupported QR")) }
    }
    fun doScan() { scope.launch { scanning = true; peers = QuickSend.scan(); scanning = false; if (peers.isEmpty()) toast(tr("لم أجد أجهزة. تأكد أنها على نفس الشبكة وأن ToolBox يعمل عليها.", "No devices found. Make sure they are on the same network with ToolBox running.")) } }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(tr("إرسال سريع", "Quick send"), style = MaterialTheme.typography.titleMedium)
            Text(tr("إلى جوال أو كمبيوتر عليه ToolBox، عبر نفس الواي فاي أو نقطة اتصال.", "To a phone or PC running ToolBox, over the same Wi-Fi or hotspot."), style = MaterialTheme.typography.bodySmall, color = TextMid)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { scanQr.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setPrompt(tr("امسح QR من الجهاز المستقبل", "Scan the receiver's QR")).setBeepEnabled(false).setOrientationLocked(false)) }) { Text(tr("مسح QR", "Scan QR")) }
                OutlinedButton(enabled = !scanning, onClick = { doScan() }) { Text(if (scanning) tr("جارٍ البحث…", "Searching…") else tr("بحث عن أجهزة", "Find devices")) }
                OutlinedButton(onClick = { manual = true }) { Text(tr("إدخال يدوي", "Manual")) }
            }
            peers.forEach { p ->
                Row(Modifier.fillMaxWidth().clickable { code = ""; askCode = p }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(p.name, style = MaterialTheme.typography.titleSmall)
                        Text((if (p.pc) tr("كمبيوتر", "PC") else tr("جوال", "Phone")) + " • " + p.ip, style = MaterialTheme.typography.bodySmall, color = TextMid)
                    }
                    Text(tr("إرسال ←", "Send →"), color = MaterialTheme.colorScheme.primary)
                }
            }
            if (QuickSend.sending || QuickSend.status.isNotEmpty()) {
                if (QuickSend.sending) {
                    LinearProgressIndicator(progress = { if (QuickSend.total > 0) (QuickSend.done.toFloat() / QuickSend.total).coerceIn(0f, 1f) else 0f }, Modifier.fillMaxWidth())
                    val pct = if (QuickSend.total > 0) (QuickSend.done * 100 / QuickSend.total).toInt() else 0
                    val eta = if (QuickSend.speed > 0) (QuickSend.total - QuickSend.done) / QuickSend.speed else -1
                    Text("$pct%  •  ${fmt(QuickSend.done)} / ${fmt(QuickSend.total)}  •  ${fmt(QuickSend.speed)}/s" + (if (eta >= 0) "  •  ${eta}s" else ""), style = MaterialTheme.typography.titleSmall)
                    OutlinedButton(onClick = { QuickSend.cancel() }) { Text(tr("إلغاء", "Cancel")) }
                } else Text(QuickSend.status, style = MaterialTheme.typography.titleSmall)
            }
        }
    }

    askCode?.let { p ->
        AlertDialog(onDismissRequest = { askCode = null }, title = { Text(p.name) },
            text = { OutlinedTextField(code, { code = it }, singleLine = true, label = { Text(if (p.pc) tr("رمز الكمبيوتر (من QR أو الشاشة)", "PC code (from QR/screen)") else "PIN") }) },
            confirmButton = { TextButton(onClick = { p.secret = code.trim(); askCode = null; go(p) }) { Text(tr("اختيار الملفات", "Choose files")) } },
            dismissButton = { TextButton(onClick = { askCode = null }) { Text(tr("إلغاء", "Cancel")) } })
    }
    if (manual) AlertDialog(onDismissRequest = { manual = false }, title = { Text(tr("إدخال يدوي", "Manual entry")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(ip, { ip = it }, singleLine = true, label = { Text("IP") })
                OutlinedTextField(code, { code = it }, singleLine = true, label = { Text(tr("PIN / الرمز", "PIN / code")) })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !isPc, onClick = { isPc = false }, label = { Text(tr("جوال", "Phone")) })
                    FilterChip(selected = isPc, onClick = { isPc = true }, label = { Text(tr("كمبيوتر", "PC")) })
                }
            }
        },
        confirmButton = { TextButton(onClick = {
            if (ip.isNotBlank()) { manual = false; go(Target(if (isPc) "PC" else "Phone", ip.trim(), if (isPc) 8766 else 8765, isPc, code.trim())) }
        }) { Text(tr("اختيار الملفات", "Choose files")) } },
        dismissButton = { TextButton(onClick = { manual = false }) { Text(tr("إلغاء", "Cancel")) } })
}
