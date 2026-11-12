package toolbox.desktop

import androidx.compose.runtime.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.*
import java.util.concurrent.Executors

var AR by mutableStateOf(true)
fun tr(ar: String, en: String) = if (AR) ar else en

fun fmt(b: Long): String {
    if (b < 0) return "?"
    val u = arrayOf("B", "KB", "MB", "GB", "TB"); var v = b.toDouble(); var i = 0
    while (v >= 1024 && i < 4) { v /= 1024; i++ }
    return if (i == 0) "$b B" else String.format("%.1f %s", v, u[i])
}

object Net {
    var base by mutableStateOf("")
    var pin = ""
    var name by mutableStateOf("")
    val connected get() = base.isNotEmpty()

    fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    fun url(path: String, vararg q: Pair<String, String>) = "$base$path?pin=$pin" + q.joinToString("") { "&${it.first}=${enc(it.second)}" }

    private fun conn(u: String, method: String = "GET"): HttpURLConnection {
        val c = URL(u).openConnection() as HttpURLConnection
        c.requestMethod = method; c.connectTimeout = 4000; c.readTimeout = 30000
        return c
    }

    fun text(path: String, vararg q: Pair<String, String>): String {
        val c = conn(url(path, *q))
        if (c.responseCode != 200) throw RuntimeException("HTTP ${c.responseCode}")
        return c.inputStream.bufferedReader().readText()
    }

    fun connect(ip: String, port: Int, p: String): Boolean = try {
        base = "http://$ip:$port"; pin = p
        val j = JSONObject(text("/api/info")); name = j.optString("name"); true
    } catch (_: Throwable) { base = ""; false }

    fun connectLink(link: String): Boolean = try {
        val u = URI(link.trim())
        val p = u.query?.split("&")?.firstOrNull { it.startsWith("pin=") }?.substringAfter("=") ?: ""
        connect(u.host, if (u.port > 0) u.port else 8765, p)
    } catch (_: Throwable) { false }

    fun disconnect() { base = ""; name = "" }

    /** بحث عن الجوالات على الشبكة (بث UDP) */
    fun discover(ms: Int = 1500): List<Pair<String, String>> {
        val found = LinkedHashMap<String, String>()
        try {
            DatagramSocket().use { s ->
                s.broadcast = true; s.soTimeout = 300
                val msg = "TOOLBOX_DISCOVER".toByteArray()
                val targets = mutableSetOf("255.255.255.255")
                NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
                    .forEach { n -> n.interfaceAddresses.forEach { a -> a.broadcast?.let { targets.add(it.hostAddress) } } }
                targets.forEach { try { s.send(DatagramPacket(msg, msg.size, InetAddress.getByName(it), 45454)) } catch (_: Throwable) {} }
                val end = System.currentTimeMillis() + ms
                val buf = ByteArray(256)
                while (System.currentTimeMillis() < end) {
                    try {
                        val p = DatagramPacket(buf, buf.size); s.receive(p)
                        val t = String(p.data, 0, p.length)
                        if (t.startsWith("TOOLBOX_HERE")) found[p.address.hostAddress] = t.split("|").getOrNull(1) ?: "Phone"
                    } catch (_: SocketTimeoutException) {}
                }
            }
        } catch (_: Throwable) {}
        return found.map { it.key to it.value }
    }

    fun ls(path: String): Pair<String, List<JSONObject>> {
        val j = JSONObject(text("/api/ls", "path" to path))
        val a = j.getJSONArray("items")
        return j.getString("path") to (0 until a.length()).map { a.getJSONObject(it) }
    }

    fun download(remote: String, dest: File, prog: (Long) -> Unit) {
        val c = conn(url("/api/get", "path" to remote)); c.readTimeout = 60000
        if (c.responseCode != 200) throw RuntimeException("HTTP ${c.responseCode}")
        dest.parentFile?.mkdirs()
        c.inputStream.use { i -> dest.outputStream().use { o ->
            val b = ByteArray(1 shl 18); var d = 0L
            while (true) { val n = i.read(b); if (n < 0) break; o.write(b, 0, n); d += n; prog(d) }
        } }
    }

    fun downloadApp(pkg: String, obb: Boolean, destDir: File, prog: (Long) -> Unit): File {
        val c = conn(url("/api/app", "pkg" to pkg, "obb" to if (obb) "1" else "0")); c.readTimeout = 120000
        if (c.responseCode != 200) throw RuntimeException("HTTP ${c.responseCode}")
        val name = URLDecoder.decode(c.getHeaderField("X-Filename") ?: "$pkg.apk", "UTF-8")
        val f = File(destDir.apply { mkdirs() }, name)
        c.inputStream.use { i -> f.outputStream().use { o ->
            val b = ByteArray(1 shl 18); var d = 0L
            while (true) { val n = i.read(b); if (n < 0) break; o.write(b, 0, n); d += n; prog(d) }
        } }
        return f
    }

    fun upload(f: File, remoteDir: String?, prog: (Long) -> Unit) {
        val qs = mutableListOf("name" to f.name); if (!remoteDir.isNullOrBlank()) qs.add("dir" to remoteDir)
        val c = conn(url("/api/put", *qs.toTypedArray()), "PUT")
        c.doOutput = true; c.setFixedLengthStreamingMode(f.length()); c.readTimeout = 120000
        c.outputStream.use { o -> f.inputStream().use { i ->
            val b = ByteArray(1 shl 18); var d = 0L
            while (true) { val n = i.read(b); if (n < 0) break; o.write(b, 0, n); d += n; prog(d) }
        } }
        if (c.responseCode != 200) throw RuntimeException("HTTP ${c.responseCode}")
    }

    fun op(cmd: String, path: String, to: String? = null) {
        val q = mutableListOf("cmd" to cmd, "path" to path); if (to != null) q.add("to" to to)
        text("/api/op", *q.toTypedArray())
    }

    fun clipGet(): String = text("/api/clip")
    fun clipPut(t: String) {
        val c = conn(url("/api/clip"), "PUT"); c.doOutput = true
        val b = t.toByteArray(); c.setFixedLengthStreamingMode(b.size); c.outputStream.use { it.write(b) }; c.responseCode
    }

    fun apps(): List<JSONObject> { val a = JSONArray(text("/api/apps")); return (0 until a.length()).map { a.getJSONObject(it) } }
}

class XItem(val name: String) {
    var total by mutableLongStateOf(0L)
    var done by mutableLongStateOf(0L)
    var status by mutableStateOf("…")
}

object Xfer {
    val items = mutableStateListOf<XItem>()
    private val pool = Executors.newFixedThreadPool(3)

    private fun job(name: String, total: Long, work: (XItem) -> Unit) {
        val it = XItem(name); it.total = total; items.add(0, it)
        pool.execute { try { work(it); it.done = it.total; it.status = "✔" } catch (e: Throwable) { it.status = "⚠ ${e.message}" } }
    }

    fun send(f: File, remoteDir: String?) = job(f.name, f.length()) { x -> Net.upload(f, remoteDir) { x.done = it } }

    fun sendTree(f: File, remoteDir: String) {
        val base = remoteDir.ifBlank { "/storage/emulated/0/Download/ToolBox" }
        if (f.isFile) { send(f, base); return }
        f.walkTopDown().filter { it.isFile }.forEach { k ->
            val rel = k.parentFile.relativeTo(f.parentFile).path.replace('\\', '/')
            send(k, "$base/$rel")
        }
    }

    fun get(remote: String, dest: File, size: Long) = job(dest.name, size) { x -> Net.download(remote, dest) { x.done = it } }
    fun app(pkg: String, obb: Boolean, dir: File) = job(pkg, 0) { x -> Net.downloadApp(pkg, obb, dir) { x.done = it } }
}
