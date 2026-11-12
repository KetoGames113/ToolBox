@file:OptIn(ExperimentalMaterial3Api::class)
package com.toolbox.app

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

class Seg(var start: Long, var end: Long, var pos: Long)

class DlTask(val id: String, val url: String, var name: String, val dir: String, val conns: Int) {
    var total by mutableLongStateOf(-1L)
    var done by mutableLongStateOf(0L)
    var speed by mutableLongStateOf(0L)
    var status by mutableStateOf("queued")   // queued, running, paused, done, error
    var err by mutableStateOf("")
    var segs = mutableListOf<Seg>()
    var ranges = true
    var path = ""
    val doneA = AtomicLong()
}

object Dl {
    val tasks = mutableStateListOf<DlTask>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = HashMap<String, Job>()
    private val sem = Semaphore(3)
    private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124.0 Mobile Safari/537.36"
    private val file get() = File(App.i.filesDir, "downloads.json")

    fun load() {
        try {
            if (!file.exists()) return
            val a = JSONArray(file.readText())
            for (i in 0 until a.length()) {
                val j = a.getJSONObject(i)
                val t = DlTask(j.getString("id"), j.getString("url"), j.getString("name"), j.getString("dir"), j.optInt("conns", 4))
                t.total = j.optLong("total", -1); t.ranges = j.optBoolean("ranges", true); t.path = j.optString("path")
                val st = j.optString("status", "paused")
                t.status = if (st == "done" || st == "error") st else "paused"
                val sg = j.optJSONArray("segs")
                if (sg != null) for (k in 0 until sg.length()) { val s = sg.getJSONArray(k); t.segs.add(Seg(s.getLong(0), s.getLong(1), s.getLong(2))) }
                t.doneA.set(if (t.status == "done") maxOf(t.total, 0) else t.segs.sumOf { it.pos - it.start })
                t.done = t.doneA.get()
                tasks.add(t)
            }
        } catch (_: Throwable) {}
    }

    @Synchronized
    fun save() {
        try {
            val a = JSONArray()
            tasks.forEach { t ->
                a.put(JSONObject().put("id", t.id).put("url", t.url).put("name", t.name).put("dir", t.dir).put("conns", t.conns)
                    .put("total", t.total).put("ranges", t.ranges).put("path", t.path).put("status", t.status)
                    .put("segs", JSONArray().also { s -> t.segs.forEach { g -> s.put(JSONArray().put(g.start).put(g.end).put(g.pos)) } }))
            }
            file.writeText(a.toString())
        } catch (_: Throwable) {}
    }

    fun add(c: Context, url: String, conns: Int) {
        val nm = try { URLDecoder.decode(url.substringBefore('?').substringAfterLast('/'), "UTF-8") } catch (_: Throwable) { "" }
        val t = DlTask(UUID.randomUUID().toString(), url, nm.ifBlank { "download" }.replace(Regex("[\\\\/:*?\"<>|]"), "_"), "Download/ToolBox", conns)
        tasks.add(0, t); start(c, t)
    }

    fun start(c: Context, t: DlTask) {
        if (jobs[t.id]?.isActive == true) return
        t.status = "queued"
        jobs[t.id] = scope.launch { sem.withPermit { run(t) } }
        ContextCompat.startForegroundService(c, Intent(c, DownloadService::class.java))
    }

    fun pause(t: DlTask) { jobs[t.id]?.cancel(); if (t.status != "done") t.status = "paused"; save() }

    fun remove(t: DlTask, delFile: Boolean) {
        jobs[t.id]?.cancel(); tasks.remove(t)
        if (delFile && t.path.isNotEmpty()) File(t.path).delete()
        save()
    }

    fun active() = tasks.count { it.status == "running" || it.status == "queued" }

    private fun open(url: String, from: Long, to: Long): HttpURLConnection {
        var u = url
        repeat(6) {
            val c = URL(u).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false; c.connectTimeout = 15000; c.readTimeout = 20000
            c.setRequestProperty("User-Agent", UA); c.setRequestProperty("Accept-Encoding", "identity")
            if (from >= 0) c.setRequestProperty("Range", "bytes=$from-" + (if (to >= 0) to.toString() else ""))
            val code = c.responseCode
            if (code in 300..399) {
                val loc = c.getHeaderField("Location") ?: throw IOException("redirect")
                u = URL(URL(u), loc).toString(); c.disconnect(); return@repeat
            }
            if (code >= 400) throw IOException("HTTP $code")
            return c
        }
        throw IOException("too many redirects")
    }

    private fun probe(t: DlTask) {
        val c = open(t.url, 0, 0)
        val cr = c.getHeaderField("Content-Range")
        t.ranges = c.responseCode == 206 && cr != null
        t.total = if (t.ranges) (cr!!.substringAfter('/').toLongOrNull() ?: -1L) else c.contentLengthLong
        val cd = c.getHeaderField("Content-Disposition") ?: ""
        val m = Regex("filename\\*?=(?:UTF-8'')?\"?([^\";]+)\"?", RegexOption.IGNORE_CASE).find(cd)
        if (m != null) t.name = try { URLDecoder.decode(m.groupValues[1], "UTF-8") } catch (_: Throwable) { m.groupValues[1] }.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        c.disconnect()
        val n = t.conns.coerceIn(1, 8)
        t.segs = if (t.ranges && t.total > 2_000_000) {
            val part = t.total / n
            MutableList(n) { i -> Seg(i * part, if (i == n - 1) t.total - 1 else (i + 1) * part - 1, i * part) }
        } else mutableListOf(Seg(0, if (t.total > 0) t.total - 1 else -1, 0))
        t.doneA.set(0); t.done = 0
    }

    private suspend fun segment(t: DlTask, sg: Seg, ch: FileChannel) {
        var tries = 0
        while (sg.end < 0 || sg.pos <= sg.end) {
            currentCoroutineContext().ensureActive()
            try {
                val conn = open(t.url, if (t.ranges) sg.pos else -1, if (t.ranges) sg.end else -1)
                conn.inputStream.use { ins ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val want = if (sg.end >= 0) minOf(buf.size.toLong(), sg.end - sg.pos + 1).toInt() else buf.size
                        if (want <= 0) break
                        val n = ins.read(buf, 0, want)
                        if (n < 0) break
                        ch.write(ByteBuffer.wrap(buf, 0, n), sg.pos)
                        sg.pos += n; t.done = t.doneA.addAndGet(n.toLong())
                    }
                }
                if (sg.end < 0) break
                if (sg.pos <= sg.end) throw IOException("short read")
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (!t.ranges) { sg.pos = sg.start; t.doneA.set(0); t.done = 0 }
                if (++tries > 6) throw e
                delay(1500L * tries)
            }
        }
    }

    private suspend fun run(t: DlTask) = coroutineScope {
        t.status = "running"; t.err = ""
        try {
            if (t.segs.isEmpty()) probe(t)
            val dirF = File(ROOT, t.dir).apply { mkdirs() }
            val part = File(dirF, t.name + ".tbpart"); t.path = part.path
            val raf = RandomAccessFile(part, "rw")
            if (t.total > 0 && raf.length() != t.total) raf.setLength(t.total)
            val ch = raf.channel
            val ticker = launch { var last = t.done; while (isActive) { delay(1000); t.speed = (t.done - last).coerceAtLeast(0); last = t.done; save() } }
            try {
                t.segs.filter { it.end < 0 || it.pos <= it.end }.map { sg -> async { segment(t, sg, ch) } }.awaitAll()
            } finally { ticker.cancel(); ch.close(); raf.close() }
            val fin = uniqueFile(dirF, t.name)
            part.renameTo(fin); t.path = fin.path; t.status = "done"; t.speed = 0
            scan(fin.path)
        } catch (e: CancellationException) { t.status = "paused"; throw e }
        catch (e: Throwable) { t.status = "error"; t.err = e.message ?: "error" }
        save()
    }
}

class DownloadService : Service() {
    private var looping = false
    override fun onBind(i: Intent?): IBinder? = null
    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        startFg(build())
        if (!looping) {
            looping = true
            CoroutineScope(Dispatchers.Default).launch {
                while (Dl.active() > 0) {
                    getSystemService(android.app.NotificationManager::class.java).notify(3, build())
                    delay(1000)
                }
                looping = false
                stopForeground(true); stopSelf()
            }
        }
        return START_NOT_STICKY
    }
    private fun startFg(n: Notification) {
        if (Build.VERSION.SDK_INT >= 29) startForeground(3, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else startForeground(3, n)
    }
    private fun build(): Notification {
        val run = Dl.tasks.filter { it.status == "running" }
        val tot = run.sumOf { maxOf(it.total, 0L) }; val done = run.sumOf { it.done }
        val b = Notification.Builder(this, "tb").setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(tr("ToolBox — تحميل", "ToolBox — downloading")).setOngoing(true)
            .setContentText("${run.size} • ${fmt(run.sumOf { it.speed })}/s")
        if (tot > 0) b.setProgress(100, (done * 100 / tot).toInt(), false)
        return b.build()
    }
}

@Composable
fun DlScreen(initial: String) {
    val c = LocalContext.current
    val nav = LocalNav.current
    val clip = LocalClipboardManager.current
    var url by remember { mutableStateOf(initial) }
    var conns by remember { mutableIntStateOf(4) }
    val clipUrl = remember(Tick.n) { clip.getText()?.text?.trim()?.takeIf { it.startsWith("http") && !it.contains(' ') && !it.contains('\n') } }
    fun addAll() {
        url.lines().map { it.trim() }.filter { it.startsWith("http") }.forEach { Dl.add(c, it, conns) }
        url = ""
    }
    Column(Modifier.fillMaxSize().padding(8.dp)) {
        OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth(), maxLines = 4, label = { Text(tr("الرابط (أو عدة روابط، كل رابط بسطر)", "Link (or several, one per line)")) })
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { addAll() }, enabled = url.isNotBlank()) { Text(tr("تحميل", "Download")) }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { clip.getText()?.text?.let { url = it } }) { Text(tr("لصق", "Paste")) }
            Spacer(Modifier.weight(1f))
            Text(tr("أجزاء: ", "Parts: "))
            IconButton(onClick = { conns = (conns - 1).coerceAtLeast(1) }) { Icon(Icons.Default.Remove, null) }
            Text("$conns")
            IconButton(onClick = { conns = (conns + 1).coerceAtMost(8) }) { Icon(Icons.Default.Add, null) }
        }
        if (clipUrl != null && clipUrl != url) AssistChip(onClick = { url = clipUrl }, label = { Text(tr("رابط منسوخ: ", "Copied link: ") + clipUrl.take(40), maxLines = 1) })
        LazyColumn(Modifier.weight(1f)) {
            items(Dl.tasks, key = { it.id }) { t ->
                Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Column(Modifier.padding(10.dp)) {
                        Text(t.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                        if (t.status != "done") LinearProgressIndicator(progress = { if (t.total > 0) (t.done.toFloat() / t.total).coerceIn(0f, 1f) else 0f }, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
                        val eta = if (t.speed > 0 && t.total > 0) " • ${((t.total - t.done) / t.speed)}s" else ""
                        Text(
                            when (t.status) {
                                "done" -> "✔ ${fmt(t.total.coerceAtLeast(t.done))}"
                                "error" -> "⚠ ${t.err}"
                                "running" -> "${fmt(t.done)} / ${if (t.total > 0) fmt(t.total) else "?"} • ${fmt(t.speed)}/s$eta"
                                else -> "${t.status} • ${fmt(t.done)} / ${if (t.total > 0) fmt(t.total) else "?"}"
                            }, style = MaterialTheme.typography.bodySmall)
                        Row {
                            if (t.status == "running" || t.status == "queued") IconButton(onClick = { Dl.pause(t) }) { Icon(Icons.Default.Pause, null) }
                            else if (t.status != "done") IconButton(onClick = { Dl.start(c, t) }) { Icon(Icons.Default.PlayArrow, null) }
                            if (t.status == "done") IconButton(onClick = { openPath(nav, c, t.path) }) { Icon(Icons.Default.OpenInNew, null) }
                            IconButton(onClick = { Dl.remove(t, t.status != "done") }) { Icon(Icons.Default.Delete, null) }
                        }
                    }
                }
            }
        }
    }
}
