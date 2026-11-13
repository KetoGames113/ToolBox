package com.toolbox.app

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.videolan.libvlc.MediaPlayer
import java.io.File

private val PBg1 = Color(0xFF4A3896)
private val PBg2 = Color(0xFF352876)
private val PDisc = Color(0xFF6853C4)
private val PRim = Color(0xFF8272D2)
private val PInk = Color(0xFFE9B9C9)
private val PLilac = Color(0xFFB9AEE3)

private fun mix(base: Color, tint: Color?, k: Float): Color =
    if (tint == null) base else lerp(base, lerp(tint, Color.Black, 0.35f), k)

@Composable
fun NowPlaying(onClose: () -> Unit) {
    val cur = Au.cur ?: return
    val vp = Au.vp
    val scope = rememberCoroutineScope()
    var fav by remember(cur.path) { mutableStateOf(App.i.prefs().getStringSet("fav", emptySet())!!.contains(cur.path)) }
    var showLyrics by remember { mutableStateOf(false) }

    val artBmp by produceState<Bitmap?>(null, cur.path) { value = withContext(Dispatchers.IO) { Art.load(cur.path, 600) } }
    val art = remember(artBmp) { artBmp?.asImageBitmap() }
    val tint = remember(artBmp) { artBmp?.let { Color(avgColor(it)) } }
    val top by animateColorAsState(mix(PBg1, tint, 0.45f), tween(700), label = "top")
    val bottom by animateColorAsState(mix(PBg2, tint, 0.30f), tween(700), label = "bottom")
    val disc by animateColorAsState(mix(PDisc, tint, 0.30f), tween(700), label = "disc")

    var reload by remember { mutableIntStateOf(0) }
    var lyr by remember(cur.path) { mutableStateOf<Lyrics?>(null) }
    var lstate by remember(cur.path) { mutableIntStateOf(0) }
    LaunchedEffect(cur.path, reload) {
        lstate = 0
        lyr = LyricsRepo.load(cur)
        lstate = if (lyr == null) 2 else 1
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        if (u != null) scope.launch { LyricsRepo.saveManual(cur, u); reload++ }
    }

    var angle by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(vp.playing) {
        if (vp.playing) {
            var last = withFrameNanos { it }
            while (true) {
                val now = withFrameNanos { it }
                angle = (angle + (now - last) / 1_000_000_000f * 40f) % 360f
                last = now
            }
        }
    }
    val armDeg by animateFloatAsState(if (vp.playing) 0f else -26f, tween(650), label = "arm")
    val appDir = if (L.ar) LayoutDirection.Rtl else LayoutDirection.Ltr

    val onFav = {
        val s = App.i.prefs().getStringSet("fav", emptySet())!!.toMutableSet()
        if (!s.add(cur.path)) s.remove(cur.path)
        App.i.prefs().edit().putStringSet("fav", s).apply()
        fav = s.contains(cur.path)
    }
    val title = cur.title.ifEmpty { File(cur.path).nameWithoutExtension }

    BoxWithConstraints(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(top, bottom)))) {
        val land = maxWidth > maxHeight
        if (land) {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Row(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                    BoxWithConstraints(Modifier.weight(0.44f).fillMaxHeight().padding(12.dp), contentAlignment = Alignment.Center) {
                        val d = minOf(maxWidth, maxHeight)
                        Vinyl(art, angle, armDeg, disc, Modifier.size(d))
                    }
                    CompositionLocalProvider(LocalLayoutDirection provides appDir) {
                        Column(Modifier.weight(0.56f).fillMaxHeight().padding(end = 20.dp, top = 4.dp, bottom = 6.dp)) {
                            Header(onClose, fav, onFav)
                            TitleBlock(title, cur.artist)
                            LyricsPanel(lyr, lstate, vp.time, PInk, PLilac, { vp.mp.time = it }, { pick.launch(arrayOf("*/*")) }, Modifier.weight(1f).fillMaxWidth())
                            Transport(vp)
                            Extras(vp, null, { pick.launch(arrayOf("*/*")) })
                        }
                    }
                }
            }
        } else {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
                Header(onClose, fav, onFav)
                BoxWithConstraints(Modifier.fillMaxWidth().aspectRatio(1f)) {
                    if (showLyrics) LyricsPanel(lyr, lstate, vp.time, PInk, PLilac, { vp.mp.time = it }, { pick.launch(arrayOf("*/*")) }, Modifier.fillMaxSize())
                    else Vinyl(art, angle, armDeg, disc, Modifier.fillMaxSize())
                }
                Spacer(Modifier.height(8.dp))
                TitleBlock(title, cur.artist)
                Transport(vp)
                Extras(vp, { showLyrics = !showLyrics }, { pick.launch(arrayOf("*/*")) })
            }
        }
    }
}

@Composable
private fun Header(onClose: () -> Unit, fav: Boolean, onFav: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onClose) { Icon(Icons.Rounded.KeyboardArrowDown, null, tint = PLilac, modifier = Modifier.size(32.dp)) }
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onFav) { Icon(if (fav) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, null, tint = if (fav) PInk else PLilac, modifier = Modifier.size(28.dp)) }
    }
}

@Composable
private fun TitleBlock(title: String, artist: String) {
    Text(title, color = PInk, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
    if (artist.isNotEmpty()) Text(artist, color = PLilac, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
}

@Composable
private fun Vinyl(art: ImageBitmap?, angle: Float, armDeg: Float, disc: Color, modifier: Modifier) {
    BoxWithConstraints(modifier.aspectRatio(1f)) {
        val s = maxWidth
        val artR = s * 0.136f
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val ctr = Offset(w * 0.5f, w * 0.55f)
            val r = w * 0.40f
            drawCircle(Color(0x33000000), r * 1.03f, ctr + Offset(0f, w * 0.012f))
            drawCircle(PRim, r, ctr)
            drawCircle(disc, r * 0.94f, ctr)
            for (i in 0..14) drawCircle(Color.White.copy(alpha = 0.05f), r * (0.46f + i * 0.032f), ctr, style = Stroke(1.2f))
            rotate(angle, ctr) {
                drawCircle(
                    brush = Brush.sweepGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.12f), Color.Transparent, Color.White.copy(alpha = 0.09f), Color.Transparent), ctr),
                    radius = r * 0.94f, center = ctr
                )
            }
            drawCircle(PRim.copy(alpha = 0.9f), r * 0.42f, ctr)
            val pivot = Offset(w * 0.90f, w * 0.04f)
            val head = Offset(w * 0.70f, w * 0.40f)
            rotate(armDeg, pivot) {
                val p = Path().apply {
                    moveTo(pivot.x, pivot.y)
                    cubicTo(pivot.x - w * 0.02f, pivot.y + w * 0.36f, pivot.x - w * 0.10f, pivot.y + w * 0.30f, head.x, head.y - w * 0.04f)
                }
                drawPath(p, Color(0x33000000), style = Stroke(width = w * 0.022f, cap = StrokeCap.Round))
                drawPath(p, Color.White, style = Stroke(width = w * 0.018f, cap = StrokeCap.Round))
                drawRoundRect(Color(0xFFF2F0FA), topLeft = Offset(head.x - w * 0.022f, head.y - w * 0.05f),
                    size = Size(w * 0.044f, w * 0.09f), cornerRadius = CornerRadius(w * 0.022f))
                drawCircle(Color.White, w * 0.025f, pivot)
            }
        }
        Box(
            Modifier.offset(x = s * 0.5f - artR, y = s * 0.55f - artR).size(artR * 2).clip(CircleShape)
                .graphicsLayer { rotationZ = angle },
            contentAlignment = Alignment.Center
        ) {
            if (art != null) Image(art, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFFE98A9F), Color(0xFF5B49B8)))), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.MusicNote, null, tint = Color.White.copy(alpha = 0.85f))
            }
        }
    }
}

@Composable
private fun Transport(vp: Vp) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Column {
            Slider(
                value = if (vp.len > 0) (vp.time.toFloat() / vp.len).coerceIn(0f, 1f) else 0f,
                onValueChange = { vp.mp.time = (it * vp.len).toLong() },
                colors = SliderDefaults.colors(thumbColor = PInk, activeTrackColor = PInk, inactiveTrackColor = Color.White.copy(alpha = 0.2f))
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(clock(vp.time), color = PLilac, fontSize = 12.sp); Text(clock(vp.len), color = PLilac, fontSize = 12.sp)
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { Au.shuffle = !Au.shuffle }) { Icon(Icons.Rounded.Shuffle, null, tint = if (Au.shuffle) PInk else PLilac) }
                IconButton(onClick = { Au.prev() }) { Icon(Icons.Rounded.FastRewind, null, tint = PInk, modifier = Modifier.size(40.dp)) }
                IconButton(onClick = { vp.toggle() }, modifier = Modifier.size(72.dp)) {
                    Icon(if (vp.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null, tint = PInk, modifier = Modifier.size(60.dp))
                }
                IconButton(onClick = { Au.next() }) { Icon(Icons.Rounded.FastForward, null, tint = PInk, modifier = Modifier.size(40.dp)) }
                IconButton(onClick = { Au.rep = (Au.rep + 1) % 3 }) {
                    Icon(if (Au.rep == 2) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat, null, tint = if (Au.rep > 0) PInk else PLilac)
                }
            }
        }
    }
}

@Composable
private fun Extras(vp: Vp, onLyrics: (() -> Unit)?, onPickLrc: () -> Unit) {
    var eq by remember { mutableStateOf(false) }
    var speedMenu by remember { mutableStateOf(false) }
    var sleepMenu by remember { mutableStateOf(false) }
    val tc = ButtonDefaults.textButtonColors(contentColor = PLilac)
    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
        if (onLyrics != null) TextButton(onClick = onLyrics, colors = tc) { Text(tr("الكلمات", "Lyrics")) }
        Box {
            TextButton(onClick = { speedMenu = true }, colors = tc) { Text("${vp.mp.rate}x") }
            DropdownMenu(speedMenu, { speedMenu = false }) {
                listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f).forEach { v -> DropdownMenuItem(text = { Text("${v}x") }, onClick = { vp.mp.rate = v; speedMenu = false }) }
            }
        }
        TextButton(onClick = { eq = true }, colors = tc) { Text(tr("معادل", "EQ")) }
        Box {
            TextButton(onClick = { sleepMenu = true }, colors = tc) { Text(if (Au.sleepAt > 0) tr("مؤقت ✔", "Sleep ✔") else tr("مؤقت النوم", "Sleep")) }
            DropdownMenu(sleepMenu, { sleepMenu = false }) {
                listOf(0, 15, 30, 60).forEach { m -> DropdownMenuItem(text = { Text(if (m == 0) tr("إيقاف", "Off") else "$m min") }, onClick = { Au.sleep(m); sleepMenu = false }) }
            }
        }
        if (onLyrics == null) TextButton(onClick = onPickLrc, colors = tc) { Text(".lrc") }
    }
    if (eq) AlertDialog(onDismissRequest = { eq = false }, confirmButton = { TextButton(onClick = { eq = false }) { Text("OK") } },
        title = { Text(tr("المعادل", "Equalizer")) },
        text = {
            LazyColumn {
                item { TextButton(onClick = { vp.mp.setEqualizer(null); eq = false }) { Text(tr("إيقاف", "Off")) } }
                items((0 until MediaPlayer.Equalizer.getPresetCount()).toList()) { i ->
                    TextButton(onClick = { vp.mp.setEqualizer(MediaPlayer.Equalizer.createFromPreset(i)); eq = false }) { Text(MediaPlayer.Equalizer.getPresetName(i)) }
                }
            }
        })
}
