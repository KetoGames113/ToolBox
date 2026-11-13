package com.toolbox.app

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

class LyricLine(val t: Long, val text: String)
class Lyrics(val lines: List<LyricLine>, val synced: Boolean)

object LyricsRepo {
    private val TS = Regex("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?\\]")

    fun parseLrc(s: String): List<LyricLine> {
        val out = ArrayList<LyricLine>()
        for (raw in s.lineSequence()) {
            val ms = TS.findAll(raw).toList()
            if (ms.isEmpty()) continue
            val text = raw.substring(ms.last().range.last + 1).trim()
            if (text.isEmpty()) continue
            for (m in ms) {
                val min = m.groupValues[1].toLong(); val sec = m.groupValues[2].toLong(); val fr = m.groupValues[3]
                val msec = if (fr.isEmpty()) 0L else fr.padEnd(3, '0').take(3).toLong()
                out.add(LyricLine(min * 60000 + sec * 1000 + msec, text))
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

    private fun cacheFile(t: Track): File {
        val d = File(App.i.filesDir, "lyrics"); d.mkdirs()
        val h = MessageDigest.getInstance("MD5").digest(t.path.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(d, "$h.lrc")
    }

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
            if (TB_online()) {
                val txt = fetchOnline(t)
                if (txt != null) { cf.writeText(txt); return@withContext toLyrics(txt) }
            }
            null
        } catch (_: Throwable) { null }
    }

    private fun TB_online() = com.toolbox.app.ui.theme.TB.lyricsOnline

    suspend fun saveManual(t: Track, u: Uri) = withContext(Dispatchers.IO) {
        try {
            val txt = App.i.contentResolver.openInputStream(u)?.use { it.readBytes().decodeToString() } ?: return@withContext
            cacheFile(t).writeText(txt)
        } catch (_: Throwable) {}
    }

    private fun clean(s: String) = s
        .replace(Regex("[(\\[][^)\\]]*[)\\]]"), "")
        .replace(Regex("(?i)\\s*-\\s*topic\\s*"), " ")
        .replace(Regex("\\s{2,}"), " ").trim()

    private fun http(u: String): String? = try {
        val c = URL(u).openConnection() as HttpURLConnection
        c.connectTimeout = 6000; c.readTimeout = 8000
        c.setRequestProperty("User-Agent", "ToolBox/1.0 (Android)")
        if (c.responseCode != 200) null else c.inputStream.bufferedReader().use { it.readText() }
    } catch (_: Throwable) { null }

    private fun JSONObject.str(k: String) = if (isNull(k)) "" else optString(k)

    private fun fetchOnline(t: Track): String? {
        val title = clean(t.title.ifEmpty { File(t.path).nameWithoutExtension })
        val artist = clean(t.artist)
        if (title.isEmpty()) return null
        val enc = { s: String -> URLEncoder.encode(s, "UTF-8") }
        val dur = t.dur / 1000
        if (artist.isNotEmpty() && dur > 0) {
            val body = http("https://lrclib.net/api/get?track_name=${enc(title)}&artist_name=${enc(artist)}&duration=$dur")
            if (body != null) try {
                val j = JSONObject(body)
                val s = j.str("syncedLyrics"); val p = j.str("plainLyrics")
                if (s.isNotBlank()) return s
                if (p.isNotBlank()) return p
            } catch (_: Throwable) {}
        }
        val body = http("https://lrclib.net/api/search?q=${enc("$title $artist".trim())}") ?: return null
        return try {
            val arr = JSONArray(body)
            var plain: String? = null
            for (i in 0 until arr.length()) {
                val j = arr.getJSONObject(i)
                val s = j.str("syncedLyrics")
                if (s.isNotBlank()) return s
                if (plain == null && j.str("plainLyrics").isNotBlank()) plain = j.str("plainLyrics")
            }
            plain
        } catch (_: Throwable) { null }
    }
}

private fun Modifier.fadeEdges(): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        drawRect(Brush.verticalGradient(0f to Color.Transparent, 0.14f to Color.Black, 0.86f to Color.Black, 1f to Color.Transparent), blendMode = BlendMode.DstIn)
    }

@Composable
fun LyricsPanel(lyr: Lyrics?, state: Int, time: Long, hi: Color, lo: Color, onSeek: (Long) -> Unit, onPick: () -> Unit, modifier: Modifier = Modifier) {
    when {
        state == 0 -> Box(modifier, Alignment.Center) { CircularProgressIndicator(color = hi, modifier = Modifier.size(28.dp), strokeWidth = 3.dp) }
        lyr == null -> Column(modifier, Arrangement.Center, Alignment.CenterHorizontally) {
            Text(tr("لا توجد كلمات لهذه الأغنية", "No lyrics for this song"), color = lo, fontSize = 15.sp)
            TextButton(onClick = onPick, colors = ButtonDefaults.textButtonColors(contentColor = hi)) { Text(tr("اختيار ملف .lrc", "Pick an .lrc file")) }
        }
        else -> {
            val list = rememberLazyListState()
            val cur = if (lyr.synced) lyr.lines.indexOfLast { it.t <= time + 250 } else -1
            LaunchedEffect(cur) { if (cur >= 0) list.animateScrollToItem(cur, -(list.layoutInfo.viewportSize.height / 3)) }
            LazyColumn(modifier.fadeEdges(), state = list, contentPadding = PaddingValues(vertical = 70.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                itemsIndexed(lyr.lines) { i, l ->
                    val on = i == cur
                    Text(l.text, Modifier.fillMaxWidth().clickable(enabled = lyr.synced) { onSeek(l.t) }.padding(vertical = 7.dp, horizontal = 8.dp),
                        color = if (on || !lyr.synced) hi else lo.copy(alpha = 0.6f), textAlign = TextAlign.Center,
                        fontSize = if (on) 23.sp else 18.sp, fontWeight = FontWeight.ExtraBold, lineHeight = if (on) 32.sp else 26.sp)
                }
            }
        }
    }
}
