package toolbox.desktop

import androidx.compose.runtime.*
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.URLDecoder
import java.security.SecureRandom
import java.util.concurrent.Executors

/** خادم على الكمبيوتر: الجوال يمسح QR ويفتح صفحة ويب لرفع/تنزيل الملفات بدون أي تطبيق. */
object PcServer {
    const val PORT = 8766
    val token = (1..8).map { "0123456789abcdef"[SecureRandom().nextInt(16)] }.joinToString("")
    var dir = File(System.getProperty("user.home"), "Downloads/ToolBox").apply { mkdirs() }
    val received = mutableStateListOf<String>()
    val shared = mutableStateListOf<File>()
    var running by mutableStateOf(false)
    private var srv: HttpServer? = null

    fun ips(): List<String> = try {
        NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback && !it.isVirtual }
            .filter { n -> listOf("virtual", "vmware", "vbox", "hyper-v", "vethernet", "wsl", "tap", "tunnel").none { n.displayName.lowercase().contains(it) } }
            .flatMap { it.inetAddresses.toList() }.filter { it is java.net.Inet4Address && it.isSiteLocalAddress }.map { it.hostAddress }
    } catch (_: Throwable) { emptyList() }

    private fun q(e: HttpExchange, k: String): String? =
        e.requestURI.rawQuery?.split("&")?.map { it.split("=", limit = 2) }?.firstOrNull { it[0] == k }?.getOrNull(1)?.let { URLDecoder.decode(it, "UTF-8") }

    private fun send(e: HttpExchange, code: Int, mime: String, body: ByteArray) {
        e.responseHeaders.add("Content-Type", mime); e.responseHeaders.add("Access-Control-Allow-Origin", "*")
        e.sendResponseHeaders(code, body.size.toLong()); e.responseBody.use { it.write(body) }
    }

    fun start() {
        if (running) return
        try {
            val s = HttpServer.create(InetSocketAddress(PORT), 0)
            s.executor = Executors.newCachedThreadPool()
            s.createContext("/") { e -> send(e, 200, "text/html; charset=utf-8", PAGE.toByteArray()) }
            s.createContext("/put") { e ->
                if (q(e, "t") != token) { send(e, 401, "text/plain", "no".toByteArray()); return@createContext }
                val name = (q(e, "name") ?: "file").replace(Regex("[\\\\/:*?\"<>|]"), "_")
                var f = File(dir, name); var i = 1
                while (f.exists()) { f = File(dir, name.substringBeforeLast('.') + " ($i)" + (if (name.contains('.')) "." + name.substringAfterLast('.') else "")); i++ }
                e.requestBody.use { inp -> f.outputStream().use { o -> inp.copyTo(o, 1 shl 18) } }
                received.add(0, f.name)
                send(e, 200, "application/json", "{\"ok\":true}".toByteArray())
            }
            s.createContext("/list") { e ->
                if (q(e, "t") != token) { send(e, 401, "text/plain", "no".toByteArray()); return@createContext }
                val a = JSONArray(); shared.forEachIndexed { i, f -> a.put(JSONObject().put("i", i).put("n", f.name).put("s", f.length())) }
                send(e, 200, "application/json", a.toString().toByteArray())
            }
            s.createContext("/get") { e ->
                val f = shared.getOrNull(q(e, "i")?.toIntOrNull() ?: -1)
                if (q(e, "t") != token || f == null || !f.isFile) { send(e, 404, "text/plain", "no".toByteArray()); return@createContext }
                e.responseHeaders.add("Content-Disposition", "attachment; filename*=UTF-8''" + java.net.URLEncoder.encode(f.name, "UTF-8").replace("+", "%20"))
                e.sendResponseHeaders(200, f.length())
                e.responseBody.use { o -> f.inputStream().use { it.copyTo(o, 1 shl 18) } }
            }
            s.start(); srv = s; running = true
        } catch (_: Throwable) { running = false }
    }

    fun stop() { srv?.stop(0); running = false }

    private const val PAGE = """<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>ToolBox PC</title>
<style>body{font-family:system-ui;background:#111;color:#eee;margin:0;padding:16px}.b{background:#2d6cdf;color:#fff;border:0;border-radius:8px;padding:12px 16px;font-size:17px;margin:4px 0}a{color:#7cc4ff}progress{width:100%}.r{padding:8px 0;border-bottom:1px solid #333}</style></head><body dir="auto">
<h2>ToolBox PC</h2><p>Send files to the PC:</p>
<input type="file" id="fi" multiple style="display:none" onchange="up(this.files)"><button class="b" onclick="document.getElementById('fi').click()">Choose photos / files</button><div id="prog"></div>
<h3>Files shared by the PC</h3><div id="l"></div>
<script>
var T=new URLSearchParams(location.search).get('t')||'';
function up(files){var box=document.getElementById('prog');Array.prototype.forEach.call(files,function(f){var p=document.createElement('div');p.textContent=f.name;var pr=document.createElement('progress');pr.max=1;pr.value=0;box.appendChild(p);box.appendChild(pr);var x=new XMLHttpRequest();x.open('PUT','/put?t='+T+'&name='+encodeURIComponent(f.name));x.upload.onprogress=function(e){pr.value=e.loaded/e.total};x.onload=function(){p.textContent='OK '+f.name};x.send(f)})}
fetch('/list?t='+T).then(function(r){return r.json()}).then(function(j){var l=document.getElementById('l');j.forEach(function(i){var d=document.createElement('div');d.className='r';var a=document.createElement('a');a.href='/get?t='+T+'&i='+i.i;a.textContent=i.n+' ('+Math.round(i.s/1024)+' KB)';d.appendChild(a);l.appendChild(d)})});
</script></body></html>"""
}
