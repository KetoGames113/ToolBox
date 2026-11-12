@file:OptIn(ExperimentalMaterial3Api::class)
package com.toolbox.app

import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

class Blk(val type: String, v0: String) { var v by mutableStateOf(v0) }   // text / image / video / audio / file

class Note(val id: String, t: String, var ts: Long, p: Boolean) {
    var title by mutableStateOf(t)
    var pinned by mutableStateOf(p)
    val blocks = mutableStateListOf<Blk>()
}

object NotesDb {
    val notes = mutableStateListOf<Note>()
    private var loaded = false
    private val file get() = File(App.i.filesDir, "notes.json")
    fun dir(id: String) = File(App.i.filesDir, "notes/$id").apply { mkdirs() }

    fun load() {
        if (loaded) return
        loaded = true
        try {
            if (!file.exists()) return
            val a = JSONArray(file.readText())
            for (i in 0 until a.length()) {
                val j = a.getJSONObject(i)
                val n = Note(j.getString("id"), j.optString("title"), j.optLong("ts"), j.optBoolean("pinned"))
                val b = j.getJSONArray("blocks")
                for (k in 0 until b.length()) { val o = b.getJSONObject(k); n.blocks.add(Blk(o.getString("t"), o.getString("v"))) }
                notes.add(n)
            }
        } catch (_: Throwable) {}
    }

    @Synchronized
    fun save() {
        try {
            val a = JSONArray()
            notes.forEach { n ->
                a.put(JSONObject().put("id", n.id).put("title", n.title).put("ts", n.ts).put("pinned", n.pinned)
                    .put("blocks", JSONArray().also { b -> n.blocks.forEach { b.put(JSONObject().put("t", it.type).put("v", it.v)) } }))
            }
            file.writeText(a.toString())
        } catch (_: Throwable) {}
    }

    fun create(): Note {
        val n = Note(UUID.randomUUID().toString(), "", System.currentTimeMillis(), false)
        n.blocks.add(Blk("text", "")); notes.add(0, n); save(); return n
    }
}

@Composable
fun NotesScreen() {
    val nav = LocalNav.current
    var q by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { NotesDb.load() }
    val list = NotesDb.notes.filter { n -> q.isBlank() || n.title.contains(q, true) || n.blocks.any { it.type == "text" && it.v.contains(q, true) } }
        .sortedWith(compareByDescending<Note> { it.pinned }.thenByDescending { it.ts })
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(q, { q = it }, Modifier.weight(1f), singleLine = true, label = { Text(tr("بحث", "Search")) })
            Spacer(Modifier.width(8.dp))
            Button(onClick = { nav.go("note", NotesDb.create().id) }) { Icon(Icons.Default.Add, null); Text(tr("جديدة", "New")) }
        }
        LazyColumn(Modifier.weight(1f)) {
            items(list, key = { it.id }) { n ->
                Row(Modifier.fillMaxWidth().clickable { nav.go("note", n.id) }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (n.pinned) Icon(Icons.Default.PushPin, null, Modifier.size(18.dp))
                    Column(Modifier.weight(1f)) {
                        Text(n.title.ifBlank { tr("بدون عنوان", "Untitled") }, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(n.blocks.firstOrNull { it.type == "text" }?.v?.take(80) ?: "", style = MaterialTheme.typography.bodySmall, maxLines = 1)
                    }
                    Text("${n.blocks.count { it.type != "text" }} 📎", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
fun NoteScreen(id: String) {
    val c = LocalContext.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { NotesDb.load() }
    val n = NotesDb.notes.firstOrNull { it.id == id }
    if (n == null) { Text(tr("غير موجودة", "Not found"), Modifier.padding(16.dp)); return }
    LaunchedEffect(Unit) { while (true) { delay(3000); NotesDb.save() } }
    DisposableEffect(Unit) { onDispose { n.ts = System.currentTimeMillis(); NotesDb.save() } }
    fun attach(type: String): (android.net.Uri?) -> Unit = { u ->
        if (u != null) scope.launch {
            val f = withContext(Dispatchers.IO) {
                val src = copyToCache(c, u); val dst = uniqueFile(NotesDb.dir(id), src.name); src.copyTo(dst); src.delete(); dst
            }
            n.blocks.add(Blk(type, f.path)); NotesDb.save()
        }
    }
    val pImg = rememberLauncherForActivityResult(ActivityResultContracts.GetContent(), attach("image"))
    val pVid = rememberLauncherForActivityResult(ActivityResultContracts.GetContent(), attach("video"))
    val pAud = rememberLauncherForActivityResult(ActivityResultContracts.GetContent(), attach("audio"))
    val pFile = rememberLauncherForActivityResult(ActivityResultContracts.GetContent(), attach("file"))
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 8.dp)) {
            OutlinedTextField(n.title, { n.title = it }, Modifier.weight(1f), singleLine = true, label = { Text(tr("العنوان", "Title")) })
            IconButton(onClick = { n.pinned = !n.pinned; NotesDb.save() }) { Icon(Icons.Default.PushPin, null, tint = if (n.pinned) MaterialTheme.colorScheme.primary else LocalContentColor.current) }
            IconButton(onClick = {
                scope.launch {
                    val f = withContext(Dispatchers.IO) {
                        val tmp = File(c.cacheDir, "export/${n.title.ifBlank { "note" }}").apply { deleteRecursively(); mkdirs() }
                        File(tmp, "note.txt").writeText(n.title + "\n\n" + n.blocks.filter { it.type == "text" }.joinToString("\n\n") { it.v })
                        n.blocks.filter { it.type != "text" }.forEach { b -> File(b.v).takeIf { it.exists() }?.copyTo(File(tmp, File(b.v).name), true) }
                        val z = uniqueFile(tbDir("Notes"), tmp.name + ".zip"); Arch.zip(listOf(tmp), z); z
                    }
                    shareFiles(c, listOf(f))
                }
            }) { Icon(Icons.Default.Share, null) }
            IconButton(onClick = { NotesDb.notes.remove(n); NotesDb.dir(id).deleteRecursively(); NotesDb.save(); nav.back() }) { Icon(Icons.Default.Delete, null) }
        }
        LazyColumn(Modifier.weight(1f).padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(n.blocks.toList()) { b ->
                Column {
                    when (b.type) {
                        "text" -> OutlinedTextField(b.v, { b.v = it }, Modifier.fillMaxWidth(), minLines = 3)
                        "image" -> AsyncImage(File(b.v), null, Modifier.fillMaxWidth().heightIn(max = 360.dp).clickable { nav.go("image", b.v) }, contentScale = ContentScale.Fit)
                        "video" -> {
                            AndroidView(factory = { ctx ->
                                VideoView(ctx).apply {
                                    setVideoPath(b.v)
                                    val mc = MediaController(ctx); mc.setAnchorView(this); setMediaController(mc); seekTo(1)
                                }
                            }, modifier = Modifier.fillMaxWidth().height(220.dp))
                            TextButton(onClick = { nav.go("video", b.v) }) { Text(tr("فتح في المشغل الاحترافي", "Open in pro player")) }
                        }
                        "audio" -> {
                            val mp = remember(b.v) { android.media.MediaPlayer() }
                            var playing by remember { mutableStateOf(false) }
                            DisposableEffect(b.v) { onDispose { try { mp.release() } catch (_: Throwable) {} } }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = {
                                    try {
                                        if (playing) { mp.pause(); playing = false } else {
                                            if (!mp.isPlaying && mp.currentPosition == 0) { mp.reset(); mp.setDataSource(b.v); mp.prepare(); mp.setOnCompletionListener { playing = false } }
                                            mp.start(); playing = true
                                        }
                                    } catch (_: Throwable) { nav.go("audio", b.v) }
                                }) { Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, null) }
                                Text(File(b.v).name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        else -> Row(Modifier.clickable { openPath(nav, c, b.v) }, verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.InsertDriveFile, null); Spacer(Modifier.width(8.dp)); Text("${File(b.v).name} (${fmt(File(b.v).length())})")
                        }
                    }
                    if (n.blocks.size > 1) TextButton(onClick = { n.blocks.remove(b); NotesDb.save() }) { Text(tr("حذف الجزء", "Remove block")) }
                }
            }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            AssistChip(onClick = { n.blocks.add(Blk("text", "")) }, label = { Text(tr("نص", "Text")) }, leadingIcon = { Icon(Icons.Default.TextFields, null) })
            AssistChip(onClick = { pImg.launch("image/*") }, label = { Text(tr("صورة", "Image")) }, leadingIcon = { Icon(Icons.Default.Image, null) })
            AssistChip(onClick = { pVid.launch("video/*") }, label = { Text(tr("فيديو", "Video")) }, leadingIcon = { Icon(Icons.Default.Videocam, null) })
            AssistChip(onClick = { pAud.launch("audio/*") }, label = { Text(tr("صوت", "Audio")) }, leadingIcon = { Icon(Icons.Default.MusicNote, null) })
            AssistChip(onClick = { pFile.launch("*/*") }, label = { Text(tr("ملف", "File")) }, leadingIcon = { Icon(Icons.Default.AttachFile, null) })
        }
    }
}
