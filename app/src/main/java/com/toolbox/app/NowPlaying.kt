package com.toolbox.app

import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.videolan.libvlc.MediaPlayer
import java.io.File

private val PBg1 = Color(0xFF4A3896)
private val PBg2 = Color(0xFF352876)
private val PDisc = Color(0xFF6853C4)
private val PRim = Color(0xFF8272D2)
private val PInk = Color(0xFFE9B9C9)
private val PLilac = Color(0xFFB9AEE3)

@Composable
fun NowPlaying(onClose: () -> Unit) {
    val cur = Au.cur ?: return
    val vp = Au.vp
    var eq by remember { mutableStateOf(false) }
    var speedMenu by remember { mutableStateOf(false) }
    var sleepMenu by remember { mutableStateOf(false) }
    var fav by remember(cur.path) { mutableStateOf(App.i.prefs().getStringSet("fav", emptySet())!!.contains(cur.path)) }

    val art by produceState<ImageBitmap?>(null, cur.path) {
        value = withContext(Dispatchers.IO) {
            try {
                val r = MediaMetadataRetriever(); r.setDataSource(cur.path)
                val b = r.embeddedPicture; r.release()
                b?.let { BitmapFactory.decodeByteArray(it, 0, it.size).asImageBitmap() }
            } catch (_: Throwable) { null }
        }
    }

    // spin while playing, keep the angle when paused
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

    Column(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(PBg1, PBg2)))
            .statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
    ) {
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.Rounded.KeyboardArrowDown, null, tint = PLilac, modifier = Modifier.size(32.dp)) }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = {
                val s = App.i.prefs().getStringSet("fav", emptySet())!!.toMutableSet()
                if (!s.add(cur.path)) s.remove(cur.path)
                App.i.prefs().edit().putStringSet("fav", s).apply()
                fav = s.contains(cur.path)
            }) { Icon(if (fav) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, null, tint = if (fav) PInk else PLilac, modifier = Modifier.size(28.dp)) }
        }

        BoxWithConstraints(Modifier.fillMaxWidth().aspectRatio(1f)) {
            val s = maxWidth
            val artR = s * 0.136f
            Canvas(Modifier.fillMaxSize()) {
                val w = size.width
                val ctr = Offset(w * 0.5f, w * 0.55f)
                val r = w * 0.40f
                drawCircle(Color(0x33000000), r * 1.03f, ctr + Offset(0f, w * 0.012f))
                drawCircle(PRim, r, ctr)
                drawCircle(PDisc, r * 0.94f, ctr)
                for (i in 0..14) drawCircle(Color.White.copy(alpha = 0.05f), r * (0.46f + i * 0.032f), ctr, style = Stroke(1.2f))
                rotate(angle, ctr) {
                    drawCircle(
                        brush = Brush.sweepGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.10f), Color.Transparent, Color.White.copy(alpha = 0.08f), Color.Transparent), ctr),
                        radius = r * 0.94f, center = ctr
                    )
                }
                drawCircle(Color(0xFF7B68D0), r * 0.42f, ctr)
                // tonearm
                val pivot = Offset(w * 0.90f, w * 0.04f)
                val head = Offset(w * 0.70f, w * 0.40f)
                rotate(armDeg, pivot) {
                    val p = Path().apply {
                        moveTo(pivot.x, pivot.y)
                        cubicTo(pivot.x - w * 0.02f, pivot.y + w * 0.36f, pivot.x - w * 0.10f, pivot.y + w * 0.30f, head.x, head.y - w * 0.04f)
                    }
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
                val a = art
                if (a != null) Image(a, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                else Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFFE98A9F), Color(0xFF5B49B8)))), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.MusicNote, null, tint = Color.White.copy(alpha = 0.85f))
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Text(cur.title.ifEmpty { File(cur.path).nameWithoutExtension }, color = PInk, fontSize = 26.sp,
            fontWeight = FontWeight.ExtraBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (cur.artist.isNotEmpty()) Text(cur.artist, color = PLilac, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)

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
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
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

        val tc = ButtonDefaults.textButtonColors(contentColor = PLilac)
        Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            Box {
                TextButton(onClick = { speedMenu = true }, colors = tc) { Text("${vp.mp.rate}x") }
                DropdownMenu(speedMenu, { speedMenu = false }) {
                    listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f).forEach { sp -> DropdownMenuItem(text = { Text("${sp}x") }, onClick = { vp.mp.rate = sp; speedMenu = false }) }
                }
            }
            TextButton(onClick = { eq = true }, colors = tc) { Text(tr("معادل", "EQ")) }
            Box {
                TextButton(onClick = { sleepMenu = true }, colors = tc) { Text(if (Au.sleepAt > 0) tr("مؤقت ✔", "Sleep ✔") else tr("مؤقت النوم", "Sleep")) }
                DropdownMenu(sleepMenu, { sleepMenu = false }) {
                    listOf(0, 15, 30, 60).forEach { m -> DropdownMenuItem(text = { Text(if (m == 0) tr("إيقاف", "Off") else "$m min") }, onClick = { Au.sleep(m); sleepMenu = false }) }
                }
            }
        }
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
