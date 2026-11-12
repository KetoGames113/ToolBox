@file:OptIn(ExperimentalMaterial3Api::class)
package com.toolbox.app

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.StatFs
import android.provider.MediaStore
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.net.*
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

object Clip {
    @Volatile var text = ""
    fun read(c: Context) {
        try {
            val p = c.getSystemService(ClipboardManager::class.java).primaryClip
            if (p != null && p.itemCount > 0) { val t = p.getItemAt(0).coerceToText(c).toString(); if (t.isNotEmpty()) text = t }
        } catch (_: Throwable) {}
    }
    fun write(t: String) {
        text = t
        Handler(Looper.getMainLooper()).post {
            try { App.i.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("ToolBox", t)) } catch (_: Throwable) {}
        }
    }
}

object Transfer {
    var server by mutableStateOf<TbServer?>(null)
    fun ips(): List<String> = try {
        NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }.filter { it is Inet4Address && it.isSiteLocalAddress }.map { it.hostAddress ?: "" }.filter { it.isNotEmpty() }
    } catch (_: Throwable) { emptyList() }
}

/** خادم HTTP خفيف يبثّ الملفات مباشرة (بدون وسيط) بأقصى سرعة الشبكة. */
class TbServer(private val ctx: Context) {
    val port = 8765
    val pin = (100000 + SecureRandom().nextInt(900000)).toString()
    var running by mutableStateOf(false)
    val log = mutableStateListOf<String>()
    val sent = AtomicLong()
    val recv = AtomicLong()
    private var ss: ServerSocket? = null
    private var ds: DatagramSocket? = null
    private val pool = Executors.newCachedThreadPool()
    private var fails = 0
    private var lockUntil = 0L
    private var mcl: WifiManager.MulticastLock? = null
    private val JSON = "application/json; charset=utf-8"

    private fun note(s: String) { Handler(Looper.getMainLooper()).post { log.add(0, s); if (log.size > 30) log.removeAt(log.lastIndex) } }

    fun start() {
        if (running) return
        ss = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(port)) }
        running = true
        pool.execute {
            try {
                while (running) {
                    val s = ss!!.accept()
                    pool.execute { try { serve(s) } catch (_: Throwable) {} finally { try { s.close() } catch (_: Throwable) {} } }
                }
            } catch (_: Throwable) {}
        }
        try {
            val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            mcl = wm.createMulticastLock("tb").apply { setReferenceCounted(false); acquire() }
        } catch (_: Throwable) {}
        pool.execute {
            try {
                val d = DatagramSocket(null); d.reuseAddress = true; d.bind(InetSocketAddress(45454)); ds = d
                val buf = ByteArray(256)
                while (running) {
                    val p = DatagramPacket(buf, buf.size); d.receive(p)
                    if (String(p.data, 0, p.length).startsWith("TOOLBOX_DISCOVER")) {
                        val r = "TOOLBOX_HERE|${Build.MODEL}|$port".toByteArray()
                        d.send(DatagramPacket(r, r.size, p.address, p.port))
                    }
                }
            } catch (_: Throwable) {}
        }
    }

    fun stop() {
        running = false
        try { ss?.close() } catch (_: Throwable) {}
        try { ds?.close() } catch (_: Throwable) {}
        try { mcl?.release() } catch (_: Throwable) {}
    }

    private fun readLine(i: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = i.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == 10) break
            if (b != 13) sb.append(b.toChar())
            if (sb.length > 16384) return null
        }
        return sb.toString()
    }

    private fun head(out: OutputStream, code: Int, mime: String, len: Long, extra: String = "") {
        val st = when (code) { 200 -> "OK"; 206 -> "Partial Content"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 404 -> "Not Found"; 429 -> "Too Many Requests"; else -> "Error" }
        out.write("HTTP/1.1 $code $st\r\nContent-Type: $mime\r\nContent-Length: $len\r\nConnection: close\r\nAccept-Ranges: bytes\r\nAccess-Control-Allow-Origin: *\r\n$extra\r\n".toByteArray())
    }

    private fun reply(out: OutputStream, code: Int, mime: String, body: String) {
        val b = body.toByteArray(); head(out, code, mime, b.size.toLong()); out.write(b); out.flush()
    }

    private fun safe(p: String?): File? {
        if (p.isNullOrEmpty()) return null
        val f = try { File(p).canonicalFile } catch (_: Throwable) { return null }
        return if (f.path.startsWith("/storage/") || f.path.startsWith("/sdcard")) f else null
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun streamFile(out: OutputStream, f: File, name: String, from: Long, count: Long, code: Int, total: Long) {
        head(out, code, mimeOf(f.path), count, (if (code == 206) "Content-Range: bytes $from-${from + count - 1}/$total\r\n" else "") + "X-Filename: ${enc(name)}\r\n")
        RandomAccessFile(f, "r").use { r ->
            r.seek(from)
            var left = count
            val buf = ByteArray(1 shl 18)
            while (left > 0) {
                val n = r.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (n < 0) break
                out.write(buf, 0, n); left -= n; sent.addAndGet(n.toLong())
            }
        }
        out.flush()
    }

    private fun serve(s: Socket) {
        s.tcpNoDelay = true; s.soTimeout = 60000
        try { s.sendBufferSize = 1 shl 18; s.receiveBufferSize = 1 shl 18 } catch (_: Throwable) {}
        val inp = BufferedInputStream(s.getInputStream(), 1 shl 16)
        val out = BufferedOutputStream(s.getOutputStream(), 1 shl 16)
        val line = readLine(inp) ?: return
        val parts = line.split(" ")
        if (parts.size < 2) return
        val target = parts[1]
        val h = HashMap<String, String>()
        while (true) {
            val l = readLine(inp) ?: break
            if (l.isEmpty()) break
            val i = l.indexOf(':'); if (i > 0) h[l.substring(0, i).trim().lowercase()] = l.substring(i + 1).trim()
        }
        val u = Uri.parse("http://x$target")
        val path = u.path ?: "/"
        try {
            if (parts[0] == "OPTIONS") { reply(out, 200, "text/plain", ""); return }
            if (path == "/") { reply(out, 200, "text/html; charset=utf-8", ctx.assets.open("web.html").bufferedReader().readText()); return }
            val now = System.currentTimeMillis()
            if (now < lockUntil) { reply(out, 429, "text/plain", "locked"); return }
            val given = u.getQueryParameter("pin") ?: h["x-pin"]
            if (given != pin) {
                fails++; if (fails >= 8) { lockUntil = now + 60000; fails = 0 }
                reply(out, 401, "text/plain", "bad pin"); return
            }
            fails = 0
            when (path) {
                "/api/info" -> {
                    val sf = StatFs(ROOT.path)
                    val b = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                    val lvl = if (b != null) b.getIntExtra("level", 0) * 100 / b.getIntExtra("scale", 100).coerceAtLeast(1) else -1
                    reply(out, 200, JSON, JSONObject().put("name", Build.MODEL).put("android", Build.VERSION.RELEASE).put("battery", lvl)
                        .put("total", sf.totalBytes).put("free", sf.availableBytes).put("ver", 1).toString())
                }
                "/api/ls" -> {
                    val d = safe(u.getQueryParameter("path") ?: ROOT.path)
                    if (d == null || !d.isDirectory) { reply(out, 404, JSON, "{}"); return }
                    val arr = JSONArray()
                    d.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))?.forEach {
                        arr.put(JSONObject().put("n", it.name).put("d", it.isDirectory).put("s", if (it.isFile) it.length() else 0L).put("t", it.lastModified()))
                    }
                    reply(out, 200, JSON, JSONObject().put("path", d.path).put("items", arr).toString())
                }
                "/api/get" -> {
                    val f = safe(u.getQueryParameter("path"))
                    if (f == null || !f.isFile) { reply(out, 404, JSON, "{}"); return }
                    val len = f.length(); var from = 0L; var to = len - 1; var code = 200
                    h["range"]?.let { r ->
                        Regex("bytes=(\\d*)-(\\d*)").find(r)?.let { m ->
                            val a = m.groupValues[1]; val b = m.groupValues[2]
                            if (a.isEmpty() && b.isNotEmpty()) from = (len - b.toLong()).coerceAtLeast(0)
                            else { if (a.isNotEmpty()) from = a.toLong(); if (b.isNotEmpty()) to = b.toLong().coerceAtMost(len - 1) }
                            code = 206
                        }
                    }
                    if (from == 0L) note("⬇ ${f.name}")
                    streamFile(out, f, f.name, from, (to - from + 1).coerceAtLeast(0), code, len)
                }
                "/api/put" -> {
                    val dir = u.getQueryParameter("dir")?.let { safe(it) } ?: File(ROOT, "Download/ToolBox")
                    dir.mkdirs()
                    val name = (u.getQueryParameter("name") ?: h["x-name"]?.let { URLDecoder.decode(it, "UTF-8") } ?: "file").replace(Regex("[\\\\/]"), "_")
                    val len = h["content-length"]?.toLongOrNull() ?: -1L
                    if (len < 0) { reply(out, 400, JSON, "{}"); return }
                    val f = uniqueFile(dir, name)
                    var left = len
                    FileOutputStream(f).use { fo ->
                        val buf = ByteArray(1 shl 18)
                        while (left > 0) {
                            val n = inp.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                            if (n < 0) break
                            fo.write(buf, 0, n); left -= n; recv.addAndGet(n.toLong())
                        }
                    }
                    if (left > 0) { f.delete(); return }
                    scan(f.path); note("⬆ ${f.name}")
                    reply(out, 200, JSON, JSONObject().put("ok", true).put("path", f.path).toString())
                }
                "/api/apps" -> {
                    val arr = JSONArray()
                    ApkTools.apps(ctx).filter { !it.system }.forEach {
                        arr.put(JSONObject().put("pkg", it.pkg).put("name", it.label).put("ver", it.ver).put("splits", it.splits > 0).put("size", it.size)
                            .put("obb", ApkTools.obbDir(it.pkg).listFiles()?.isNotEmpty() == true))
                    }
                    reply(out, 200, JSON, arr.toString())
                }
                "/api/app" -> {
                    val pkg = u.getQueryParameter("pkg") ?: ""
                    val obb = u.getQueryParameter("obb") == "1"
                    val ai = try { ctx.packageManager.getApplicationInfo(pkg, 0) } catch (_: Throwable) { null }
                    if (ai == null) { reply(out, 404, JSON, "{}"); return }
                    val splits = ai.splitSourceDirs?.isNotEmpty() == true
                    note("⬇ $pkg")
                    if (!obb && !splits) {
                        val f = File(ai.sourceDir)
                        streamFile(out, f, ctx.packageManager.getApplicationLabel(ai).toString() + ".apk", 0, f.length(), 200, f.length())
                    } else {
                        val f = ApkTools.export(ctx, pkg, if (obb) 2 else 1) {}
                        try { streamFile(out, f, f.name, 0, f.length(), 200, f.length()) } finally { f.delete() }
                    }
                }
                "/api/recent" -> {
                    val since = u.getQueryParameter("since")?.toLongOrNull() ?: 0L
                    val arr = JSONArray()
                    ctx.contentResolver.query(
                        MediaStore.Files.getContentUri("external"),
                        arrayOf(MediaStore.MediaColumns.DATA, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_ADDED, MediaStore.Files.FileColumns.MEDIA_TYPE),
                        "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (1,3) AND ${MediaStore.MediaColumns.DATE_ADDED} > ?", arrayOf(since.toString()),
                        "${MediaStore.MediaColumns.DATE_ADDED} ASC"
                    )?.use { q ->
                        var n = 0
                        while (q.moveToNext() && n < 300) { n++; arr.put(JSONObject().put("p", q.getString(0)).put("s", q.getLong(1)).put("t", q.getLong(2)).put("m", q.getInt(3))) }
                    }
                    reply(out, 200, JSON, JSONObject().put("items", arr).toString())
                }
                "/api/clip" -> {
                    if (parts[0] == "PUT" || parts[0] == "POST") {
                        val len = (h["content-length"]?.toIntOrNull() ?: 0).coerceIn(0, 1_000_000)
                        val b = ByteArray(len); var o = 0
                        while (o < len) { val n = inp.read(b, o, len - o); if (n < 0) break; o += n }
                        Clip.write(String(b, 0, o)); note("📋 ← PC")
                        reply(out, 200, "text/plain", "ok")
                    } else reply(out, 200, "text/plain; charset=utf-8", Clip.text)
                }
                "/api/op" -> {
                    val f = safe(u.getQueryParameter("path"))
                    if (f == null) { reply(out, 400, JSON, "{}"); return }
                    val ok = when (u.getQueryParameter("cmd")) {
                        "mkdir" -> f.mkdirs()
                        "rm" -> Cln.del(f)
                        "mv" -> safe(u.getQueryParameter("to"))?.let { f.renameTo(it) } ?: false
                        else -> false
                    }
                    reply(out, 200, JSON, JSONObject().put("ok", ok).toString())
                }
                else -> reply(out, 404, JSON, "{}")
            }
        } catch (t: Throwable) {
            try { reply(out, 500, "text/plain", t.message ?: "error") } catch (_: Throwable) {}
        }
    }
}

class TransferService : Service() {
    override fun onBind(i: Intent?): IBinder? = null
    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        if (i?.action == "stop") {
            Transfer.server?.stop(); stopForeground(true); stopSelf(); return START_NOT_STICKY
        }
        val stop = PendingIntent.getService(this, 1, Intent(this, TransferService::class.java).setAction("stop"), PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, "tb").setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("ToolBox").setContentText(tr("خادم النقل يعمل", "Transfer server running")).setContentIntent(open)
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel), tr("إيقاف", "Stop"), stop).build())
            .setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else startForeground(1, n)
        try {
            if (Transfer.server?.running != true) Transfer.server = TbServer(applicationContext).also { it.start() }
        } catch (t: Throwable) { toast("Server: ${t.message}"); Transfer.server = null; stopForeground(true); stopSelf() }
        return START_NOT_STICKY
    }
    override fun onDestroy() { Transfer.server?.stop(); super.onDestroy() }
}

fun qrBitmap(text: String, size: Int = 512): ImageBitmap {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
    val b = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
    for (x in 0 until size) for (y in 0 until size) b.setPixel(x, y, if (m.get(x, y)) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
    return b.asImageBitmap()
}

@Composable
fun TransferScreen() {
    val c = LocalContext.current
    val srv = Transfer.server
    val running = srv?.running == true
    val cm = LocalClipboardManager.current
    val ips = remember(Tick.n, running) { Transfer.ips() }
    var sent by remember { mutableLongStateOf(0L) }
    var recv by remember { mutableLongStateOf(0L) }
    LaunchedEffect(srv) { while (true) { sent = srv?.sent?.get() ?: 0L; recv = srv?.recv?.get() ?: 0L; delay(1000) } }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { us ->
        if (us.isNotEmpty()) Thread {
            try { val fs = us.map { copyToCache(c, it) }; Handler(Looper.getMainLooper()).post { shareFiles(c, fs) } } catch (_: Throwable) {}
        }.start()
    }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (running) tr("الخادم يعمل ✔", "Server running") else tr("الخادم متوقف", "Server stopped"), style = MaterialTheme.typography.titleMedium)
                Button(onClick = {
                    val i = Intent(c, TransferService::class.java)
                    if (running) { i.action = "stop"; c.startService(i) } else ContextCompat.startForegroundService(c, i)
                }) { Text(if (running) tr("إيقاف", "Stop") else tr("تشغيل", "Start")) }
                if (!Perms.files(c)) Text(tr("يلزم منح «الوصول لكل الملفات» من الإعدادات.", "Grant All-files access in Settings."), color = MaterialTheme.colorScheme.error)
            }
        }
        if (running && srv != null) {
            val ip = ips.firstOrNull() ?: "?"
            val url = "http://$ip:${srv.port}/?pin=${srv.pin}"
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("PIN", style = MaterialTheme.typography.labelLarge)
                    Text(srv.pin, fontSize = 40.sp, fontWeight = FontWeight.Bold)
                    Image(remember(url) { qrBitmap(url) }, null, Modifier.size(240.dp))
                    Text(url, style = MaterialTheme.typography.bodySmall)
                    if (ips.size > 1) Text(tr("عناوين أخرى: ", "Other IPs: ") + ips.drop(1).joinToString(), style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = { cm.setText(AnnotatedString(url)); toast("OK") }) { Text(tr("نسخ الرابط", "Copy link")) }
                    Text(tr("↑ ${fmt(recv)}   ↓ ${fmt(sent)}", "↑ ${fmt(recv)}   ↓ ${fmt(sent)}"), style = MaterialTheme.typography.bodySmall)
                }
            }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(tr("كيف أتصل؟", "How to connect"), style = MaterialTheme.typography.titleSmall)
                    Text(tr(
                        "• برنامج ToolBox للكمبيوتر: يبحث عن الجوال تلقائياً، اكتب الـ PIN.\n• أي جوال/كمبيوتر بدون برنامج: امسح الـ QR بالكاميرا أو افتح الرابط في المتصفح (نفس الواي فاي).\n• لا يوجد واي فاي؟ شغّل نقطة اتصال (هوت سبوت) في الجوال واتصل بها من الكمبيوتر.",
                        "• ToolBox PC finds the phone automatically; enter the PIN.\n• Any device without the app: scan the QR or open the link in a browser (same Wi-Fi).\n• No Wi-Fi? Turn on the phone hotspot and connect the PC to it."), style = MaterialTheme.typography.bodySmall)
                }
            }
            if (srv.log.isNotEmpty()) Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) { srv.log.take(10).forEach { Text(it, style = MaterialTheme.typography.bodySmall) } }
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tr("طرق أخرى", "Other ways"), style = MaterialTheme.typography.titleMedium)
                OutlinedButton(onClick = { Perms.open(c, "android.settings.TETHER_SETTINGS", false) }) { Text(tr("فتح إعدادات نقطة الاتصال", "Open hotspot settings")) }
                OutlinedButton(onClick = { pick.launch("*/*") }) { Text(tr("إرسال ملفات عبر بلوتوث / تطبيقات أخرى", "Send files via Bluetooth / other apps")) }
                Text(tr("البلوتوث أبطأ بكثير من الواي فاي، استخدمه للملفات الصغيرة فقط.", "Bluetooth is much slower than Wi-Fi; use it for small files only."), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
