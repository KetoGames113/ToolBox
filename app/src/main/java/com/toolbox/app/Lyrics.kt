package com.toolbox.app

import android.net.Uri
import android.os.Build
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import kotlin.math.abs

class LyricLine(val t: Long, val text: String)
class Lyrics(val lines: List<LyricLine>, val synced: Boolean)

/** نتيجة بحث عن كلمات: lrclib أو NetEase. text قد تكون null حتى نجلبها. */
class Cand(val source: String, val title: String, val artist: String, val durSec: Long, var text: String?, val synced: Boolean, val id: Long = 0L)

object LyricsRepo {
    private val TS = Regex("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?\\]")
    private val OFFSET = Regex("\\[offset:\\s*([+-]?\\d+)\\]", RegexOption.IGNORE_CASE)

    fun parseLrc(s: String): List<LyricLine> {
        val shift = OFFSET.find(s)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val out = ArrayList<LyricLine>()
        for (raw in s.lineSequence()) {
            val ms = TS.findAll(raw).toList()
            if (ms.isEmpty()) continue
            val text = raw.substring(ms.last().range.last + 1).trim()
            if (text.isEmpty()) continue
            for (m in ms) {
                val min = m.groupValues[1].toLong(); val sec = m.groupValues[2].toLong(); val fr = m.groupValues[3]
                val msec = if (fr.isEmpty()) 0L else fr.padEnd(3, '0').take(3).toLong()
                out.add(LyricLine(maxOf(0L, min * 60000 + sec * 1000 + msec - shift), text))
            }
        }
        return out.sortedBy { it.t }
    }

    private fun toLyrics(text: String): Lyrics? {
        val synced = parseLrc(text)
        if (synced.size >= 2) return Lyrics(synced, true)
        val plain = text.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("[") }
        return if (plain.isEmpty()) null else Lyrics(plain.map { LyricLine(0, it) }, false)
    }

    private fun key(t: Track) = MessageDigest.getInstance("MD5").digest(t.path.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun cacheFile(t: Track): File { val d = File(App.i.filesDir, "lyrics"); d.mkdirs(); return File(d, key(t) + ".lrc") }

    // ---- إزاحة التزامن لكل أغنية (بالميلي ثانية) ----
    fun getOffset(t: Track): Long = App.i.prefs().getLong("lyr_off_" + key(t), 0L)
    fun setOffset(t: Track, ms: Long) { App.i.prefs().edit().putLong("lyr_off_" + key(t), ms).apply() }

    fun saveText(t: Track, text: String) { try { cacheFile(t).writeText(text) } catch (_: Throwable) {} }

    suspend fun load(t: Track): Lyrics? = withContext(Dispatchers.IO) {
        try {
            val cf = cacheFile(t)
            if (cf.exists()) return@withContext toLyrics(cf.readText())
            if (!t.path.startsWith("content:")) {
                val f = File(t.path)
                for (ext in listOf("lrc", "txt")) {
                    val side = File(f.parentFile, f.nameWithoutExtension + "." + ext)
                    if (side.exists()) { toLyrics(side.readText())?.let { return@withContext it } }
                }
            }
            if (com.toolbox.app.ui.theme.TB.lyricsOnline) {
                val txt = autoFetch(t)
                if (txt != null) { cf.writeText(txt); return@withContext toLyrics(txt) }
            }
            null
        } catch (_: Throwable) { null }
    }

    suspend fun saveManual(t: Track, u: Uri) = withContext(Dispatchers.IO) {
        try {
            val txt = App.i.contentResolver.openInputStream(u)?.use { it.readBytes().decodeToString() } ?: return@withContext
            cacheFile(t).writeText(txt)
        } catch (_: Throwable) {}
    }

    // ---- تنظيف العناوين وبناء استعلامات البحث ----
    fun clean(s: String) = s
        .replace(Regex("[(\\[][^)\\]]*[)\\]]"), "")
        .replace(Regex("(?i)\\s*-\\s*topic\\s*"), " ")
        .replace(Regex("(?i)\\b(official|lyrics?|video|audio|hd|4k|mv|visuali[sz]er|كلمات|فيديو|رسمي)\\b"), " ")
        .replace(Regex("\\s{2,}"), " ").trim()

    private fun titleOf(t: Track) = t.title.ifEmpty { File(t.path).nameWithoutExtension }

    fun defaultQuery(t: Track): String {
        val segs = titleOf(t).split(Regex("\\s*[|\\-–—]\\s*")).map { clean(it) }.filter { it.length >= 2 }
        val latin = segs.firstOrNull { s -> s.any { it in 'A'..'Z' || it in 'a'..'z' } }
        return (latin ?: clean(titleOf(t))).let { if (t.artist.isNotBlank() && !it.contains(clean(t.artist), true)) "$it ${clean(t.artist)}" else it }.trim()
    }

    private fun queries(t: Track): List<String> {
        val raw = titleOf(t); val title = clean(raw); val artist = clean(t.artist)
        val segs = raw.split(Regex("\\s*[|\\-–—]\\s*")).map { clean(it) }.filter { it.length >= 2 }
        val l = ArrayList<String>()
        l.add(defaultQuery(t)); l.add("$title $artist".trim()); l.add(title)
        segs.take(3).forEach { l.add(it); if (artist.isNotEmpty()) l.add("$it $artist") }
        return l.map { it.trim() }.filter { it.length >= 2 }.distinct().take(5)
    }

    private fun words(s: String) = s.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length > 1 }.toSet()
    private fun sim(a: String, b: String): Double {
        val x = words(a); val y = words(b)
        if (x.isEmpty() || y.isEmpty()) return 0.0
        return x.intersect(y).size.toDouble() / x.union(y).size
    }

    private fun score(c: Cand, t: Track): Int {
        var s = if (c.synced) 100 else 0
        val dur = t.dur / 1000
        if (dur > 0 && c.durSec > 0) { val d = abs(c.durSec - dur); s -= (d * 6).toInt(); if (d > 12) s -= 200 }
        val variants = listOf(clean(titleOf(t))) + titleOf(t).split(Regex("\\s*[|\\-–—]\\s*")).map { clean(it) }
        s += (variants.maxOf { sim(c.title, it) } * 60).toInt()
        return s
    }

    // ---- شبكة ----
    private fun http(u: String, referer: String? = null): String? = try {
        val c = URL(u).openConnection() as HttpURLConnection
        c.connectTimeout = 6000; c.readTimeout = 9000
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) ToolBox/1.0")
        if (referer != null) c.setRequestProperty("Referer", referer)
        if (c.responseCode != 200) null else c.inputStream.bufferedReader().use { it.readText() }
    } catch (_: Throwable) { null }

    private fun JSONObject.str(k: String) = if (isNull(k)) "" else optString(k)
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun searchLrclib(q: String): List<Cand> {
        val body = http("https://lrclib.net/api/search?q=${enc(q)}") ?: return emptyList()
        return try {
            val arr = JSONArray(body); val out = ArrayList<Cand>()
            for (i in 0 until minOf(arr.length(), 12)) {
                val j = arr.getJSONObject(i)
                val s = j.str("syncedLyrics"); val p = j.str("plainLyrics")
                if (s.isBlank() && p.isBlank()) continue
                out.add(Cand("lrclib", j.str("trackName"), j.str("artistName"), j.optDouble("duration", 0.0).toLong(), s.ifBlank { p }, s.isNotBlank()))
            }
            out
        } catch (_: Throwable) { emptyList() }
    }

    private fun searchNetease(q: String): List<Cand> {
        val body = http("https://music.163.com/api/search/get?s=${enc(q)}&type=1&limit=6&offset=0", "https://music.163.com/") ?: return emptyList()
        return try {
            val songs = JSONObject(body).getJSONObject("result").getJSONArray("songs"); val out = ArrayList<Cand>()
            for (i in 0 until songs.length()) {
                val j = songs.getJSONObject(i)
                val ar = j.optJSONArray("artists")
                val artist = if (ar == null) "" else (0 until ar.length()).joinToString(", ") { ar.getJSONObject(it).str("name") }
                out.add(Cand("163", j.str("name"), artist, j.optLong("duration", 0L) / 1000, null, true, j.optLong("id", 0L)))
            }
            out
        } catch (_: Throwable) { emptyList() }
    }

    /** نص الكلمات لنتيجة بحث (NetEase يحتاج طلباً ثانياً). */
    suspend fun fetchText(c: Cand): String? = withContext(Dispatchers.IO) {
        c.text?.let { return@withContext it }
        if (c.source != "163") return@withContext null
        val body = http("https://music.163.com/api/song/lyric?id=${c.id}&lv=1&kv=1&tv=-1", "https://music.163.com/") ?: return@withContext null
        try {
            val j = JSONObject(body)
            val l = j.optJSONObject("lrc")?.str("lyric") ?: ""
            if (l.isBlank()) null else { c.text = l; l }
        } catch (_: Throwable) { null }
    }

    /** بحث يدوي بنص يكتبه المستخدم (أو تلقائي إن كان null). */
    suspend fun search(q: String, t: Track?): List<Cand> = withContext(Dispatchers.IO) {
        val all = ArrayList<Cand>()
        all += searchLrclib(q); all += searchNetease(q)
        if (t != null) all.sortedByDescending { score(it, t) } else all
    }

    private suspend fun autoFetch(t: Track): String? {
        for ((n, q) in queries(t).withIndex()) {
            val res = withContext(Dispatchers.IO) { searchLrclib(q) + (if (n >= 1) searchNetease(q) else emptyList()) }
            val best = res.maxByOrNull { score(it, t) } ?: continue
            if (score(best, t) < 60) continue
            val txt = fetchText(best)
            if (!txt.isNullOrBlank()) return txt
        }
        return null
    }
}

private fun Modifier.fadeEdges(): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        drawRect(Brush.verticalGradient(0f to Color.Transparent, 0.16f to Color.Black, 0.84f to Color.Black, 1f to Color.Transparent), blendMode = BlendMode.DstIn)
    }

@Composable
fun LyricsPanel(lyr: Lyrics?, state: Int, time: Long, hi: Color, lo: Color, fontScale: Float, onSeek: (Long) -> Unit, onPick: () -> Unit, modifier: Modifier = Modifier) {
    Crossfade(targetState = Pair(state, lyr), animationSpec = tween(500), modifier = modifier, label = "lyrics") { (st, ly) ->
        when {
            st == 0 -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator(color = hi, modifier = Modifier.size(28.dp), strokeWidth = 3.dp) }
            ly == null -> Column(Modifier.fillMaxSize(), Arrangement.Center, Alignment.CenterHorizontally) {
                Text(tr("لا توجد كلمات لهذه الأغنية", "No lyrics for this song"), color = lo, fontSize = 15.sp)
                TextButton(onClick = onPick, colors = ButtonDefaults.textButtonColors(contentColor = hi)) { Text(tr("بحث عن كلمات", "Search lyrics")) }
            }
            else -> LyricsList(ly, time, hi, lo, fontScale, onSeek)
        }
    }
}

@Composable
private fun LyricsList(lyr: Lyrics, time: Long, hi: Color, lo: Color, fs: Float, onSeek: (Long) -> Unit) {
    val list = rememberLazyListState()
    val cur = if (lyr.synced) lyr.lines.indexOfLast { it.t <= time + 150 } else -1
    // يوسّط السطر الحالي عند ~38% من ارتفاع المنطقة (يعمل في العمودي والأفقي)
    LaunchedEffect(cur) {
        if (cur < 0) return@LaunchedEffect
        repeat(2) {
            val info = list.layoutInfo
            val item = info.visibleItemsInfo.firstOrNull { it.index == cur }
            if (item == null) list.scrollToItem(cur)
            else {
                val delta = item.offset + item.size / 2f - info.viewportSize.height * 0.38f
                list.animateScrollBy(delta, tween(450))
                return@LaunchedEffect
            }
        }
    }
    LazyColumn(Modifier.fillMaxSize().fadeEdges(), state = list, contentPadding = PaddingValues(vertical = 90.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        itemsIndexed(lyr.lines) { i, l ->
            val on = lyr.synced && i == cur
            val d = if (cur < 0) 0 else abs(i - cur)
            val flat = !lyr.synced || on
            val a by animateFloatAsState(if (flat) 1f else when (d) { 1 -> 0.6f; 2 -> 0.4f; else -> 0.25f }, tween(450), label = "a")
            val sc by animateFloatAsState(if (flat) 1f else 0.86f, spring(dampingRatio = 0.62f, stiffness = 170f), label = "s")
            val ty by animateFloatAsState(if (flat) 0f else 12f, tween(420), label = "t")
            val glow by animateFloatAsState(if (on) 1f else 0f, tween(550), label = "g")
            val soft = if (Build.VERSION.SDK_INT >= 31 && d >= 2) Modifier.blur(((d - 1) * 1.3f).coerceAtMost(4f).dp) else Modifier
            Text(
                l.text,
                Modifier.fillMaxWidth().then(soft)
                    .graphicsLayer { alpha = a; scaleX = sc; scaleY = sc; translationY = ty }
                    .clickable(enabled = lyr.synced) { onSeek(l.t) }
                    .padding(vertical = 8.dp, horizontal = 10.dp),
                color = if (flat) hi else lo, textAlign = TextAlign.Center,
                fontSize = (21 * fs).sp, lineHeight = (29 * fs).sp, fontWeight = FontWeight.ExtraBold,
                style = TextStyle(shadow = Shadow(hi.copy(alpha = 0.6f * glow), Offset.Zero, 26f * glow))
            )
        }
    }
}

@Composable
fun LyricsSearchDialog(t: Track, onChosen: () -> Unit, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var q by remember { mutableStateOf(LyricsRepo.defaultQuery(t)) }
    var res by remember { mutableStateOf<List<Cand>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    fun go() { scope.launch { busy = true; msg = ""; res = LyricsRepo.search(q, t); busy = false; if (res.isEmpty()) msg = tr("لا نتائج. جرّب كلمات أبسط أو اسم الفنان.", "No results. Try simpler words or the artist name.") } }
    LaunchedEffect(Unit) { go() }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text(tr("إغلاق", "Close")) } },
        title = { Text(tr("بحث عن كلمات", "Search lyrics")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(q, { q = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    trailingIcon = { IconButton(onClick = { go() }) { Icon(Icons.Rounded.Search, null) } })
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (msg.isNotEmpty()) Text(msg, style = MaterialTheme.typography.bodySmall)
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(res) { c ->
                        Column(Modifier.fillMaxWidth().clickable {
                            scope.launch {
                                busy = true
                                val txt = LyricsRepo.fetchText(c)
                                busy = false
                                if (txt != null) { LyricsRepo.saveText(t, txt); onChosen() } else msg = tr("تعذّر جلب هذه الكلمات", "Couldn't fetch these lyrics")
                            }
                        }.padding(vertical = 8.dp)) {
                            Text(c.title.ifEmpty { "—" }, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                            val dur = if (c.durSec > 0) clock(c.durSec * 1000) + "  •  " else ""
                            Text(dur + c.artist + "  •  " + (if (c.synced) tr("متزامنة", "synced") else tr("نص فقط", "text only")) + "  •  " + c.source,
                                style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    )
}
