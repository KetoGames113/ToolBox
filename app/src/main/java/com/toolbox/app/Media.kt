@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
package com.toolbox.app

import android.app.Activity
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ServiceInfo
import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material.icons.rounded.MusicNote
import com.toolbox.app.ui.components.ItemRow
import com.toolbox.app.ui.components.MiniPlayer
import com.toolbox.app.ui.components.SearchField
import com.toolbox.app.ui.theme.AccAudio
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IMedia
import org.videolan.libvlc.util.VLCVideoLayout
import java.io.File
import kotlin.random.Random

/** مشغل مبني على libVLC: يدعم تقريباً كل ترميزات الفيديو والصوت والترجمات. */
class Vp(private val c: Context) {
    val lib = LibVLC(c.applicationContext, arrayListOf("--audio-time-stretch"))
    val mp = MediaPlayer(lib)
    var playing by mutableStateOf(false)
    var time by mutableLongStateOf(0L)
    var len by mutableLongStateOf(0L)
    var onEnd: (() -> Unit)? = null
    private var pfd: ParcelFileDescriptor? = null

    init {
        mp.setEventListener { e ->
            when (e.type) {
                MediaPlayer.Event.Playing -> playing = true
                MediaPlayer.Event.Paused, MediaPlayer.Event.Stopped -> playing = false
                MediaPlayer.Event.TimeChanged -> time = e.timeChanged
                MediaPlayer.Event.LengthChanged -> len = e.lengthChanged
                MediaPlayer.Event.EndReached -> { playing = false; onEnd?.invoke() }
            }
        }
    }

    fun open(path: String) {
        val m = when {
            path.startsWith("content:") -> { pfd?.close(); pfd = c.contentResolver.openFileDescriptor(Uri.parse(path), "r"); Media(lib, pfd!!.fileDescriptor) }
            path.contains("://") -> Media(lib, Uri.parse(path))
            else -> Media(lib, Uri.fromFile(File(path)))
        }
        m.setHWDecoderEnabled(true, false)
        mp.media = m
        m.release()
        mp.play()
    }

    fun toggle() { if (mp.isPlaying) mp.pause() else mp.play() }
    fun seekBy(ms: Long) { if (mp.isSeekable) mp.time = (mp.time + ms).coerceIn(0L, maxOf(mp.length, 0L)) }
    fun release() {
        try { mp.setEventListener(null); mp.stop(); mp.detachViews(); mp.release(); lib.release(); pfd?.close() } catch (_: Throwable) {}
    }
}

fun clock(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0); val h = s / 3600; val m = (s % 3600) / 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s % 60) else "%02d:%02d".format(m, s % 60)
}

class Track(val path: String, val title: String, val artist: String, val dur: Long)

fun queryAudio(c: Context): List<Track> {
    val l = mutableListOf<Track>()
    try {
        c.contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Audio.Media.DATA, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.DURATION),
            "${MediaStore.Audio.Media.DURATION} > 10000", null, "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC")?.use { q ->
            while (q.moveToNext()) l.add(Track(q.getString(0) ?: continue, q.getString(1) ?: "", q.getString(2) ?: "", q.getLong(3)))
        }
    } catch (_: Throwable) {}
    return l
}

fun queryVideos(c: Context): List<Track> {
    val l = mutableListOf<Track>()
    try {
        c.contentResolver.query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Video.Media.DATA, MediaStore.Video.Media.TITLE, MediaStore.Video.Media.SIZE, MediaStore.Video.Media.DURATION),
            null, null, "${MediaStore.Video.Media.DATE_ADDED} DESC")?.use { q ->
            while (q.moveToNext()) l.add(Track(q.getString(0) ?: continue, q.getString(1) ?: "", fmt(q.getLong(2)), q.getLong(3)))
        }
    } catch (_: Throwable) {}
    return l
}

fun queryImages(c: Context): List<String> {
    val l = mutableListOf<String>()
    try {
        c.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Images.Media.DATA), null, null,
            "${MediaStore.Images.Media.DATE_ADDED} DESC")?.use { q -> while (q.moveToNext()) l.add(q.getString(0) ?: continue) }
    } catch (_: Throwable) {}
    return l
}

// ───────────────────────── الصوت ─────────────────────────

object Au {
    val vp by lazy { Vp(App.i).also { it.onEnd = { Au.onEnd() } } }
    var list by mutableStateOf(listOf<Track>())
    var idx by mutableIntStateOf(-1)
    var shuffle by mutableStateOf(false)
    var rep by mutableIntStateOf(0)           // 0 بدون، 1 الكل، 2 الأغنية
    var sleepAt by mutableLongStateOf(0L)
    var full by mutableStateOf(false)
    val cur get() = list.getOrNull(idx)

    fun play(l: List<Track>, i: Int) { list = l; go(i) }
    fun go(i: Int) {
        if (list.isEmpty()) return
        idx = ((i % list.size) + list.size) % list.size
        vp.open(list[idx].path)
        ContextCompat.startForegroundService(App.i, Intent(App.i, AudioService::class.java))
    }
    fun next() { if (shuffle) go(Random.nextInt(list.size)) else go(idx + 1) }
    fun prev() { if (vp.time > 3000) vp.mp.time = 0 else go(idx - 1) }
    fun onEnd() { if (rep == 2) go(idx) else if (idx < list.lastIndex || rep == 1 || shuffle) next() }
    fun sleep(min: Int) {
        if (min == 0) { sleepAt = 0; return }
        sleepAt = System.currentTimeMillis() + min * 60_000L
        Handler(Looper.getMainLooper()).postDelayed({
            if (sleepAt != 0L && System.currentTimeMillis() >= sleepAt - 1000) { vp.mp.pause(); sleepAt = 0 }
        }, min * 60_000L)
    }
}

class AudioService : Service() {
    override fun onBind(i: Intent?): IBinder? = null
    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        when (i?.action) {
            "pp" -> Au.vp.toggle()
            "next" -> Au.next()
            "prev" -> Au.prev()
            "stop" -> { Au.vp.mp.pause(); stopForeground(true); stopSelf(); return START_NOT_STICKY }
        }
        val n = build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(2, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK) else startForeground(2, n)
        return START_NOT_STICKY
    }
    private fun act(a: String, ic: Int, t: String) = Notification.Action.Builder(
        Icon.createWithResource(this, ic), t,
        PendingIntent.getService(this, a.hashCode(), Intent(this, AudioService::class.java).setAction(a), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)).build()
    private fun build(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, "tb").setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(Au.cur?.title ?: "ToolBox").setContentText(Au.cur?.artist ?: "").setContentIntent(open).setOngoing(true)
            .addAction(act("prev", android.R.drawable.ic_media_previous, "Prev"))
            .addAction(act("pp", if (Au.vp.mp.isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play, "Play/Pause"))
            .addAction(act("next", android.R.drawable.ic_media_next, "Next"))
            .addAction(act("stop", android.R.drawable.ic_menu_close_clear_cancel, "Stop"))
            .setStyle(Notification.MediaStyle().setShowActionsInCompactView(0, 1, 2)).build()
    }
}

@Composable
fun AudioScreen(path: String) {
    val c = LocalContext.current
    var tracks by remember { mutableStateOf(listOf<Track>()) }
    var q by remember { mutableStateOf("") }
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
    BackHandler(enabled = Au.full) { Au.full = false }
    if (Au.full && Au.cur != null) { NowPlaying { Au.full = false }; return }
    val vp = Au.vp
    Column(Modifier.fillMaxSize()) {
        SearchField(q, { q = it }, tr("بحث", "Search"), Modifier.padding(12.dp))
        LazyColumn(Modifier.weight(1f)) {
            items(tracks.filter { q.isBlank() || it.title.contains(q, true) || it.artist.contains(q, true) }, key = { it.path }) { t ->
                val on = Au.cur?.path == t.path
                Box(Modifier.background(if (on) AccAudio.copy(alpha = 0.10f) else Color.Transparent)) {
                    ItemRow(
                        t.title.ifEmpty { File(t.path).name },
                        t.artist.ifEmpty { if (t.dur > 0) clock(t.dur) else "" },
                        Icons.Rounded.MusicNote, AccAudio,
                        if (t.dur > 0 && t.artist.isNotEmpty()) clock(t.dur) else null
                    ) { Au.play(tracks, tracks.indexOf(t)); Au.full = true }
                }
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

// ───────────────────────── الفيديو ─────────────────────────

@Composable
fun VideoListScreen() {
    val c = LocalContext.current
    val nav = LocalNav.current
    var vids by remember { mutableStateOf(listOf<Track>()) }
    var q by remember { mutableStateOf("") }
    var stream by remember { mutableStateOf("") }
    LaunchedEffect(Tick.n) { vids = withContext(Dispatchers.IO) { queryVideos(c) } }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u -> if (u != null) nav.go("video", u.toString()) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(stream, { stream = it }, Modifier.weight(1f), singleLine = true, label = { Text(tr("رابط بث (http / rtsp / m3u8)", "Stream URL (http / rtsp / m3u8)")) })
            IconButton(enabled = stream.contains("://"), onClick = { nav.go("video", stream.trim()) }) { Icon(Icons.Default.PlayArrow, null) }
            IconButton(onClick = { pick.launch(arrayOf("video/*", "audio/*")) }) { Icon(Icons.Default.FolderOpen, null) }
        }
        OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth().padding(horizontal = 8.dp), singleLine = true, label = { Text(tr("بحث", "Search")) })
        LazyColumn(Modifier.weight(1f)) {
            items(vids.filter { q.isBlank() || it.title.contains(q, true) }, key = { it.path }) { v ->
                Row(Modifier.fillMaxWidth().clickable { nav.go("video", v.path) }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    AsyncImage(File(v.path), null, Modifier.size(96.dp, 60.dp), contentScale = ContentScale.Crop)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(v.title.ifEmpty { File(v.path).name }, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text("${clock(v.dur)} • ${v.artist}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
fun VideoScreen(path: String) {
    val c = LocalContext.current
    val act = c as Activity
    val vp = remember { Vp(c) }
    val playlist = remember(path) {
        if (path.contains("://")) listOf(path)
        else File(path).parentFile?.listFiles()?.filter { it.extension.lowercase() in VID }?.sortedBy { it.name.lowercase() }?.map { it.path }?.ifEmpty { null } ?: listOf(path)
    }
    var idx by remember { mutableIntStateOf(playlist.indexOf(path).coerceAtLeast(0)) }
    var show by remember { mutableStateOf(true) }
    var hud by remember { mutableStateOf("") }
    var menu by remember { mutableStateOf("") }
    var landscape by remember { mutableStateOf(false) }
    var scale by remember { mutableIntStateOf(0) }
    val scales = listOf(MediaPlayer.ScaleType.SURFACE_BEST_FIT, MediaPlayer.ScaleType.SURFACE_FIT_SCREEN, MediaPlayer.ScaleType.SURFACE_FILL, MediaPlayer.ScaleType.SURFACE_16_9, MediaPlayer.ScaleType.SURFACE_4_3, MediaPlayer.ScaleType.SURFACE_ORIGINAL)
    DisposableEffect(Unit) {
        act.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            vp.release()
            act.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            val lp = act.window.attributes; lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE; act.window.attributes = lp
        }
    }
    vp.onEnd = { if (idx < playlist.lastIndex) idx++ }
    LaunchedEffect(idx) { vp.open(playlist[idx]) }
    LaunchedEffect(show, vp.playing, menu) { if (show && vp.playing && menu.isEmpty()) { delay(4000); show = false } }
    LaunchedEffect(hud) { if (hud.isNotEmpty()) { delay(900); hud = "" } }
    val subPick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        if (u != null) Thread { try { val f = copyToCache(c, u); act.runOnUiThread { vp.mp.addSlave(IMedia.Slave.Type.Subtitle, Uri.fromFile(f), true) } } catch (_: Throwable) {} }.start()
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { ctx -> VLCVideoLayout(ctx).also { vp.mp.attachViews(it, null, true, false) } }, modifier = Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(onTap = { show = !show }, onDoubleTap = { o ->
                    if (o.x < size.width / 2) { vp.seekBy(-10_000); hud = "−10s" } else { vp.seekBy(10_000); hud = "+10s" }
                })
            }
            .pointerInput(Unit) {
                detectVerticalDragGestures { change, dy ->
                    if (change.position.x < size.width / 2) {
                        val lp = act.window.attributes
                        val cur = if (lp.screenBrightness < 0) 0.5f else lp.screenBrightness
                        lp.screenBrightness = (cur - dy / 1200f).coerceIn(0.02f, 1f); act.window.attributes = lp
                        hud = "☀ ${(lp.screenBrightness * 100).toInt()}%"
                    } else {
                        vp.mp.volume = (vp.mp.volume - dy / 4f).toInt().coerceIn(0, 150)
                        hud = "🔊 ${vp.mp.volume}%"
                    }
                }
            })
        if (hud.isNotEmpty()) Text(hud, Modifier.align(Alignment.Center).background(Color(0x99000000)).padding(12.dp), color = Color.White, style = MaterialTheme.typography.titleLarge)
        if (show) {
            Row(Modifier.align(Alignment.TopCenter).fillMaxWidth().background(Color(0x88000000)).padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(playlist[idx].substringAfterLast('/'), Modifier.weight(1f).padding(start = 8.dp), color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Box {
                    IconButton(onClick = { menu = "speed" }) { Icon(Icons.Default.Speed, null, tint = Color.White) }
                    DropdownMenu(menu == "speed", { menu = "" }) { listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 3f).forEach { s -> DropdownMenuItem(text = { Text("${s}x") }, onClick = { vp.mp.rate = s; menu = "" }) } }
                }
                Box {
                    IconButton(onClick = { menu = "audio" }) { Icon(Icons.Default.Audiotrack, null, tint = Color.White) }
                    DropdownMenu(menu == "audio", { menu = "" }) {
                        (vp.mp.audioTracks?.toList() ?: emptyList()).forEach { t -> DropdownMenuItem(text = { Text(t.name) }, onClick = { vp.mp.audioTrack = t.id; menu = "" }) }
                    }
                }
                Box {
                    IconButton(onClick = { menu = "sub" }) { Icon(Icons.Default.Subtitles, null, tint = Color.White) }
                    DropdownMenu(menu == "sub", { menu = "" }) {
                        DropdownMenuItem(text = { Text(tr("تحميل ترجمة…", "Load subtitle…")) }, onClick = { menu = ""; subPick.launch(arrayOf("*/*")) })
                        (vp.mp.spuTracks?.toList() ?: emptyList()).forEach { t -> DropdownMenuItem(text = { Text(t.name) }, onClick = { vp.mp.spuTrack = t.id; menu = "" }) }
                    }
                }
                IconButton(onClick = { scale = (scale + 1) % scales.size; vp.mp.videoScale = scales[scale]; hud = "▭ ${scale + 1}/${scales.size}" }) { Icon(Icons.Default.AspectRatio, null, tint = Color.White) }
                IconButton(onClick = { landscape = !landscape; act.requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }) { Icon(Icons.Default.ScreenRotation, null, tint = Color.White) }
            }
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color(0x88000000)).padding(8.dp)) {
                Slider(value = if (vp.len > 0) vp.time.toFloat() / vp.len else 0f, onValueChange = { if (vp.mp.isSeekable) vp.mp.time = (it * vp.len).toLong() })
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(clock(vp.time), color = Color.White)
                    Row {
                        IconButton(onClick = { if (idx > 0) idx-- }) { Icon(Icons.Default.SkipPrevious, null, tint = Color.White) }
                        IconButton(onClick = { vp.seekBy(-10_000) }) { Icon(Icons.Default.Replay10, null, tint = Color.White) }
                        FilledIconButton(onClick = { vp.toggle() }) { Icon(if (vp.playing) Icons.Default.Pause else Icons.Default.PlayArrow, null) }
                        IconButton(onClick = { vp.seekBy(10_000) }) { Icon(Icons.Default.Forward10, null, tint = Color.White) }
                        IconButton(onClick = { if (idx < playlist.lastIndex) idx++ }) { Icon(Icons.Default.SkipNext, null, tint = Color.White) }
                    }
                    Text(clock(vp.len), color = Color.White)
                }
            }
        }
    }
}

// ───────────────────────── الصور ─────────────────────────

@Composable
fun PhotosScreen() {
    val c = LocalContext.current
    val nav = LocalNav.current
    var imgs by remember { mutableStateOf(listOf<String>()) }
    LaunchedEffect(Tick.n) { imgs = withContext(Dispatchers.IO) { queryImages(c) } }
    LazyVerticalGrid(GridCells.Adaptive(96.dp), contentPadding = PaddingValues(2.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        items(imgs, key = { it }) { p ->
            AsyncImage(File(p), null, Modifier.aspectRatio(1f).clickable { nav.go("image", p) }, contentScale = ContentScale.Crop)
        }
    }
}

@Composable
fun Zoomable(path: String, onZoom: (Boolean) -> Unit) {
    var s by remember { mutableFloatStateOf(1f) }
    var off by remember { mutableStateOf(Offset.Zero) }
    Box(Modifier.fillMaxSize().clipToBounds()
        .pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                do {
                    val e = awaitPointerEvent()
                    val zoom = e.calculateZoom(); val pan = e.calculatePan()
                    if (e.changes.size >= 2 || s > 1f) {
                        s = (s * zoom).coerceIn(1f, 6f)
                        off = if (s <= 1f) Offset.Zero else off + pan
                        onZoom(s > 1f)
                        e.changes.forEach { if (it.positionChanged()) it.consume() }
                    }
                } while (e.changes.any { it.pressed })
            }
        }
        .pointerInput(Unit) { detectTapGestures(onDoubleTap = { if (s > 1f) { s = 1f; off = Offset.Zero; onZoom(false) } else { s = 2.5f; onZoom(true) } }) }
    ) {
        AsyncImage(File(path), null, Modifier.fillMaxSize().graphicsLayer(scaleX = s, scaleY = s, translationX = off.x, translationY = off.y), contentScale = ContentScale.Fit)
    }
}

@Composable
fun ImageScreen(path: String) {
    val c = LocalContext.current
    val nav = LocalNav.current
    val files = remember(path) {
        File(path).parentFile?.listFiles()?.filter { it.extension.lowercase() in IMG }?.sortedBy { it.name.lowercase() }?.map { it.path }?.ifEmpty { null } ?: listOf(path)
    }
    val pager = rememberPagerState(initialPage = files.indexOf(path).coerceAtLeast(0), pageCount = { files.size })
    var zoomed by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(pager, Modifier.fillMaxSize(), userScrollEnabled = !zoomed) { p -> Zoomable(files[p]) { zoomed = it } }
        Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color(0x88000000)), horizontalArrangement = Arrangement.SpaceEvenly) {
            IconButton(onClick = { nav.go("editor", files[pager.currentPage]) }) { Icon(Icons.Default.Edit, null, tint = Color.White) }
            IconButton(onClick = { shareFiles(c, listOf(File(files[pager.currentPage]))) }) { Icon(Icons.Default.Share, null, tint = Color.White) }
            Text("${pager.currentPage + 1}/${files.size}", Modifier.align(Alignment.CenterVertically), color = Color.White)
        }
    }
}
