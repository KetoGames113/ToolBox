package com.toolbox.app

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.toolbox.app.ui.components.MiniPlayer
import com.toolbox.app.ui.components.SearchField
import com.toolbox.app.ui.theme.AccAudio
import com.toolbox.app.ui.theme.Ink2
import com.toolbox.app.ui.theme.TextHi
import com.toolbox.app.ui.theme.TextLow
import com.toolbox.app.ui.theme.TextMid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun TrackRow(t: Track, on: Boolean, onClick: () -> Unit) {
    val art by produceState<Bitmap?>(null, t.path) { value = withContext(Dispatchers.IO) { Art.load(t.path, 160) } }
    Row(
        Modifier.fillMaxWidth().background(if (on) AccAudio.copy(alpha = 0.10f) else Color.Transparent)
            .clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(54.dp).clip(RoundedCornerShape(14.dp)).background(Ink2), contentAlignment = Alignment.Center) {
            val a = art
            if (a != null) Image(a.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Icon(Icons.Rounded.MusicNote, null, tint = AccAudio)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(t.title.ifEmpty { File(t.path).nameWithoutExtension }, color = if (on) AccAudio else TextHi,
                style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (t.artist.isNotEmpty()) Text(t.artist, color = TextMid, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (t.dur > 0) Text(clock(t.dur), color = TextLow, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun GroupCard(name: String, list: List<Track>, artist: Boolean, onClick: () -> Unit) {
    val art by produceState<Bitmap?>(null, name) {
        value = withContext(Dispatchers.IO) { list.take(4).firstNotNullOfOrNull { Art.load(it.path, 300) } }
    }
    Column(Modifier.clip(RoundedCornerShape(20.dp)).clickable(onClick = onClick).padding(4.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(20.dp)).background(Ink2), contentAlignment = Alignment.Center) {
            val a = art
            if (a != null) Image(a.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Icon(if (artist) Icons.Rounded.Person else Icons.Rounded.Album, null, tint = AccAudio, modifier = Modifier.size(44.dp))
        }
        Text(name, Modifier.padding(top = 6.dp), color = TextHi, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("${list.size} " + tr("أغنية", "songs"), color = TextMid, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun AudioScreen(path: String) {
    val c = LocalContext.current
    var tracks by remember { mutableStateOf(listOf<Track>()) }
    var q by remember { mutableStateOf("") }
    var tab by remember { mutableIntStateOf(0) }
    var group by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(path, Tick.n) {
        tracks = withContext(Dispatchers.IO) {
            val all = queryAudio(c)
            if (path.isNotEmpty() && !path.startsWith("content:")) {
                val sib = File(path).parentFile?.listFiles()?.filter { it.extension.lowercase() in AUD }?.sortedBy { it.name.lowercase() }
                    ?.map { Track(it.path, it.nameWithoutExtension, "", 0) } ?: emptyList()
                if (sib.isNotEmpty()) sib else all
            } else all
        }
    }
    LaunchedEffect(path, tracks) {
        if (path.isNotEmpty() && tracks.isNotEmpty() && Au.cur?.path != path) {
            if (path.startsWith("content:")) Au.play(listOf(Track(path, "Audio", "", 0)), 0)
            else Au.play(tracks, tracks.indexOfFirst { it.path == path }.coerceAtLeast(0))
            Au.full = true
        }
    }
    DisposableEffect(Unit) { onDispose { Au.full = false } }
    BackHandler(enabled = Au.full || group != null) { if (Au.full) Au.full = false else group = null }
    if (Au.full && Au.cur != null) { NowPlaying { Au.full = false }; return }

    val unknown = tr("غير معروف", "Unknown")
    fun gk(t: Track) = (if (tab == 1) t.album else t.artist).ifBlank { unknown }
    val vp = Au.vp
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(tr("الأغاني", "Songs"), tr("الألبومات", "Albums"), tr("الفنانون", "Artists")).forEachIndexed { i, s ->
                FilterChip(selected = tab == i, onClick = { tab = i; group = null }, label = { Text(s) })
            }
        }
        SearchField(q, { q = it }, tr("بحث", "Search"), Modifier.padding(12.dp))
        val matches = tracks.filter { q.isBlank() || it.title.contains(q, true) || it.artist.contains(q, true) || it.album.contains(q, true) }
        val g = group
        if (tab == 0 || g != null) {
            val shown = if (g != null) matches.filter { gk(it) == g } else matches
            if (g != null) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(g, Modifier.weight(1f), color = TextHi, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                IconButton(onClick = { Au.shuffle = false; Au.play(shown, 0); Au.full = true }) { Icon(Icons.Rounded.PlayArrow, null, tint = AccAudio) }
                IconButton(onClick = { Au.shuffle = true; Au.play(shown, kotlin.random.Random.nextInt(shown.size.coerceAtLeast(1))); Au.full = true }) { Icon(Icons.Rounded.Shuffle, null, tint = AccAudio) }
            }
            LazyColumn(Modifier.weight(1f)) {
                items(shown, key = { it.path }) { t ->
                    TrackRow(t, Au.cur?.path == t.path) { Au.play(shown, shown.indexOf(t)); Au.full = true }
                }
            }
        } else {
            val groups = matches.groupBy { gk(it) }.entries.sortedBy { it.key.lowercase() }
            LazyVerticalGrid(GridCells.Adaptive(140.dp), Modifier.weight(1f), contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                gridItems(groups, key = { it.key }) { e -> GroupCard(e.key, e.value, tab == 2) { group = e.key } }
            }
        }
        val cur = Au.cur
        if (cur != null) Box(Modifier.clickable { Au.full = true }) {
            MiniPlayer(cur.title.ifEmpty { File(cur.path).name }, cur.artist,
                if (vp.len > 0) vp.time.toFloat() / vp.len else 0f, vp.playing,
                { vp.toggle() }, { Au.next() }, { Au.prev() })
        }
    }
}
