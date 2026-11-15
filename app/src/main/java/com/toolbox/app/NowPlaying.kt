package com.toolbox.app

import android.graphics.Bitmap
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.draw.blur
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
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.toolbox.app.ui.theme.TB
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.videolan.libvlc.MediaPlayer
import java.io.File

data class PTheme(val ar: String, val en: String, val bg1: Color, val bg2: Color, val disc: Color, val rim: Color, val ink: Color, val lilac: Color)

val PThemes = listOf(
    PTheme("تلقائي (من الغلاف)", "Auto (cover)", Color(0xFF4A3896), Color(0xFF352876), Color(0xFF6853C4), Color(0xFF8272D2), Color(0xFFE9B9C9), Color(0xFFB9AEE3)),
    PTheme("بنفسجي", "Purple", Color(0xFF4A3896), Color(0xFF352876), Color(0xFF6853C4), Color(0xFF8272D2), Color(0xFFE9B9C9), Color(0xFFB9AEE3)),
    PTheme("وردي", "Rose", Color(0xFF6B2A47), Color(0xFF3F1730), Color(0xFFC25A86), Color(0xFFE08AB0), Color(0xFFFFD1E0), Color(0xFFE9B3CA)),
    PTheme("نعناع", "Mint", Color(0xFF1F5F55), Color(0xFF0F3A34), Color(0xFF2FA58E), Color(0xFF5CC9B0), Color(0xFFC9FFEF), Color(0xFF9FE0CE)),
    PTheme("سماوي", "Sky", Color(0xFF1F4A7A), Color(0xFF102B4D), Color(0xFF3F86D6), Color(0xFF6BA8EA), Color(0xFFD6EAFF), Color(0xFF9FC4EC)),
    PTheme("عنبري", "Amber", Color(0xFF6A4A12), Color(0xFF3B2807), Color(0xFFC98A1E), Color(0xFFE5A94A), Color(0xFFFFE6B0), Color(0xFFEBCB8F)),
    PTheme("ليلي", "Midnight", Color(0xFF14141C), Color(0xFF0A0A10), Color(0xFF2E2E40), Color(0xFF4A4A63), Color(0xFFEDEDF7), Color(0xFFA9A9BF)),
)

private val LocalPT = staticCompositionLocalOf { PThemes[1] }

private fun mix(base: Color, tint: Color?, k: Float): Color =
    if (tint == null) base else lerp(base, lerp(tint, Color.Black, 0.35f), k)

@Composable
fun NowPlaying(onClose: () -> Unit) {
    val cur = Au.cur ?: return
    val vp = Au.vp
    val scope = rememberCoroutineScope()
    var fav by remember(cur.path) { mutableStateOf(App.i.prefs().getStringSet("fav", emptySet())!!.contains(cur.path)) }
    var showLyrics by remember { mutableStateOf(false) }
    var themeSheet by remember { mutableStateOf(false) }
    var searchDlg by remember { mutableStateOf(false) }
    var lyrOff by remember(cur.path) { mutableLongStateOf(LyricsRepo.getOffset(cur)) }

    val artBmp by produceState<Bitmap?>(null, cur.path) { value = withContext(Dispatchers.IO) { Art.load(cur.path, 700) } }
    val art = remember(artBmp) { artBmp?.asImageBitmap() }
    val tint = remember(artBmp) { artBmp?.let { Color(avgColor(it)) } }
    // pre-blurred copy for old Android (< 12) where Modifier.blur is not available
    val bgArt = remember(artBmp, TB.plBlur) {
        val b = artBmp
        if (b == null) null
        else if (Build.VERSION.SDK_INT < 31 && TB.plBlur > 0) {
            val f = 1 + TB.plBlur / 3
            Bitmap.createScaledBitmap(b, (b.width / f).coerceAtLeast(8), (b.height / f).coerceAtLeast(8), true).asImageBitmap()
        } else b.asImageBitmap()
    }

    val base = PThemes[TB.plTheme.coerceIn(0, PThemes.lastIndex)]
    val auto = TB.plTheme == 0
    val top by animateColorAsState(if (auto) mix(base.bg1, tint, 0.45f) else base.bg1, tween(700), label = "top")
    val bottom by animateColorAsState(if (auto) mix(base.bg2, tint, 0.30f) else base.bg2, tween(700), label = "bottom")
    val disc by animateColorAsState(if (auto) mix(base.disc, tint, 0.30f) else base.disc, tween(700), label = "disc")
    val pt = base.copy(bg1 = top, bg2 = bottom, disc = disc)
    val dc: Pair<Color, Color> = when (TB.plStyle) {
        1 -> Pair(Color(0xFF16141D), Color(0xFF2C2938))
        2 -> { val tt = tint ?: pt.disc; Pair(lerp(tt, Color.Black, 0.55f), lerp(tt, Color.White, 0.18f)) }
        else -> Pair(pt.disc, pt.rim)
    }

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
    var vel by remember { mutableFloatStateOf(0f) }
    val topSpeed = when (TB.plSpeed) { 0 -> 24f; 2 -> 70f; else -> 42f }
    LaunchedEffect(vp.playing, topSpeed) {
        var last = withFrameNanos { it }
        while (vp.playing || vel > 0.4f) {
            val now = withFrameNanos { it }
            val dt = (now - last) / 1_000_000_000f; last = now
            val goal = if (vp.playing) topSpeed else 0f
            vel += (goal - vel) * (1f - kotlin.math.exp(-dt / 0.45f))
            angle = (angle + vel * dt) % 360f
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
    val lyricsPanel: @Composable (Modifier) -> Unit = { m ->
        LyricsPanel(lyr, lstate, vp.time + lyrOff, pt.ink, pt.lilac, TB.plLyr / 100f, { vp.mp.time = maxOf(0L, it - lyrOff) }, { searchDlg = true }, m)
    }

    CompositionLocalProvider(LocalPT provides pt) {
        BoxWithConstraints(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(top, bottom)))) {
            val land = maxWidth > maxHeight
            PlayerBackground(bgArt, TB.plBg, TB.plBlur, TB.plDim, top, land)
            if (land) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Row(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                        BoxWithConstraints(Modifier.weight(0.44f).fillMaxHeight().padding(12.dp), contentAlignment = Alignment.Center) {
                            val d = minOf(maxWidth, maxHeight)
                            if (TB.plDisc) Vinyl(art, angle, armDeg, dc.first, dc.second, TB.plArm, Modifier.size(d))
                        }
                        CompositionLocalProvider(LocalLayoutDirection provides appDir) {
                            Column(Modifier.weight(0.56f).fillMaxHeight().padding(horizontal = 20.dp, vertical = 4.dp)) {
                                Header(onClose, fav, onFav) { themeSheet = true }
                                TitleBlock(title, cur.artist, true)
                                lyricsPanel(Modifier.weight(1f).fillMaxWidth())
                                Transport(vp)
                                Extras(vp, null, lyrOff, { lyrOff = it; LyricsRepo.setOffset(cur, it) }) { searchDlg = true }
                            }
                        }
                    }
                }
            } else {
                Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
                    Header(onClose, fav, onFav) { themeSheet = true }
                    Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
                        if (showLyrics || !TB.plDisc) lyricsPanel(Modifier.fillMaxSize())
                        else Vinyl(art, angle, armDeg, dc.first, dc.second, TB.plArm, Modifier.fillMaxSize())
                    }
                    Spacer(Modifier.height(8.dp))
                    TitleBlock(title, cur.artist, false)
                    Transport(vp)
                    Extras(vp, { showLyrics = !showLyrics }, lyrOff, { lyrOff = it; LyricsRepo.setOffset(cur, it) }) { searchDlg = true }
                }
            }
        }
        if (themeSheet) PlayerThemeSheet { themeSheet = false }
        if (searchDlg) LyricsSearchDialog(cur, { searchDlg = false; reload++ }) { searchDlg = false }
    }
}

@Composable
private fun PlayerBackground(art: ImageBitmap?, mode: Int, blur: Int, dim: Int, edge: Color, land: Boolean) {
    if (art == null || mode == 0) return
    val blurMod = if (Build.VERSION.SDK_INT >= 31 && blur > 0) Modifier.blur(blur.dp) else Modifier
    Box(Modifier.fillMaxSize()) {
        if (mode == 1) {
            Image(art, null, Modifier.fillMaxSize().then(blurMod), contentScale = ContentScale.Crop)
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = dim / 100f)))
        } else {
            val m = if (land) Modifier.align(Alignment.CenterStart).fillMaxHeight().fillMaxWidth(0.6f)
            else Modifier.align(Alignment.TopCenter).fillMaxWidth().fillMaxHeight(0.6f)
            Box(m) {
                Image(art, null, Modifier.fillMaxSize().then(blurMod), contentScale = ContentScale.Crop)
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = dim / 100f)))
                Box(Modifier.fillMaxSize().background(
                    if (land) Brush.horizontalGradient(0.4f to Color.Transparent, 1f to edge)
                    else Brush.verticalGradient(0.4f to Color.Transparent, 1f to edge)))
            }
        }
    }
}

@Composable
private fun Header(onClose: () -> Unit, fav: Boolean, onFav: () -> Unit, onTheme: () -> Unit) {
    val pt = LocalPT.current
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onClose) { Icon(Icons.Rounded.KeyboardArrowDown, null, tint = pt.lilac, modifier = Modifier.size(32.dp)) }
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onTheme) { Icon(Icons.Rounded.Palette, null, tint = pt.lilac, modifier = Modifier.size(26.dp)) }
        IconButton(onClick = onFav) { Icon(if (fav) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, null, tint = if (fav) pt.ink else pt.lilac, modifier = Modifier.size(28.dp)) }
    }
}

@Composable
private fun TitleBlock(title: String, artist: String, compact: Boolean) {
    val pt = LocalPT.current
    Text(title, color = pt.ink, fontSize = if (compact) 21.sp else 26.sp, fontWeight = FontWeight.ExtraBold, maxLines = if (compact) 1 else 2, overflow = TextOverflow.Ellipsis)
    if (artist.isNotEmpty()) Text(artist, color = pt.lilac, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun Vinyl(art: ImageBitmap?, angle: Float, armDeg: Float, disc: Color, rim: Color, armOn: Boolean, modifier: Modifier) {
    BoxWithConstraints(modifier.aspectRatio(1f)) {
        val s = maxWidth
        val artR = s * 0.136f
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val ctr = Offset(w * 0.5f, w * 0.55f)
            val r = w * 0.40f
            drawCircle(Color(0x33000000), r * 1.03f, ctr + Offset(0f, w * 0.012f))
            drawCircle(rim, r, ctr)
            drawCircle(disc, r * 0.94f, ctr)
            for (i in 0..14) drawCircle(Color.White.copy(alpha = 0.05f), r * (0.46f + i * 0.032f), ctr, style = Stroke(1.2f))
            // لمعة ثابتة كالأسطوانة الحقيقية (لا تدور مع القرص)
            rotate(-35f, ctr) {
                drawCircle(
                    brush = Brush.sweepGradient(0f to Color.Transparent, 0.08f to Color.White.copy(alpha = 0.16f), 0.2f to Color.Transparent, 0.5f to Color.Transparent,
                        0.58f to Color.White.copy(alpha = 0.11f), 0.7f to Color.Transparent, 1f to Color.Transparent, center = ctr),
                    radius = r * 0.94f, center = ctr
                )
            }
            // علامات خفيفة تدور لتوضّح الحركة
            rotate(angle, ctr) {
                for (k in 0 until 7) {
                    val rr = r * (0.52f + 0.07f * k)
                    drawArc(Color.White.copy(alpha = 0.10f), k * 51f, 24f, false, Offset(ctr.x - rr, ctr.y - rr), Size(rr * 2, rr * 2), style = Stroke(w * 0.005f, cap = StrokeCap.Round))
                }
            }
            drawCircle(rim.copy(alpha = 0.9f), r * 0.42f, ctr)
            val pivot = Offset(w * 0.90f, w * 0.04f)
            val head = Offset(w * 0.70f, w * 0.40f)
            if (armOn) rotate(armDeg, pivot) {
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
    val pt = LocalPT.current
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Column {
            Slider(
                value = if (vp.len > 0) (vp.time.toFloat() / vp.len).coerceIn(0f, 1f) else 0f,
                onValueChange = { vp.mp.time = (it * vp.len).toLong() },
                colors = SliderDefaults.colors(thumbColor = pt.ink, activeTrackColor = pt.ink, inactiveTrackColor = Color.White.copy(alpha = 0.2f))
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(clock(vp.time), color = pt.lilac, fontSize = 12.sp); Text(clock(vp.len), color = pt.lilac, fontSize = 12.sp)
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { Au.shuffle = !Au.shuffle }) { Icon(Icons.Rounded.Shuffle, null, tint = if (Au.shuffle) pt.ink else pt.lilac) }
                IconButton(onClick = { Au.prev() }) { Icon(Icons.Rounded.FastRewind, null, tint = pt.ink, modifier = Modifier.size(40.dp)) }
                IconButton(onClick = { vp.toggle() }, modifier = Modifier.size(72.dp)) {
                    Icon(if (vp.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null, tint = pt.ink, modifier = Modifier.size(60.dp))
                }
                IconButton(onClick = { Au.next() }) { Icon(Icons.Rounded.FastForward, null, tint = pt.ink, modifier = Modifier.size(40.dp)) }
                IconButton(onClick = { Au.rep = (Au.rep + 1) % 3 }) {
                    Icon(if (Au.rep == 2) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat, null, tint = if (Au.rep > 0) pt.ink else pt.lilac)
                }
            }
        }
    }
}

@Composable
private fun Extras(vp: Vp, onLyrics: (() -> Unit)?, off: Long, onOff: (Long) -> Unit, onSearch: () -> Unit) {
    val pt = LocalPT.current
    var eq by remember { mutableStateOf(false) }
    var speedMenu by remember { mutableStateOf(false) }
    var sleepMenu by remember { mutableStateOf(false) }
    var syncMenu by remember { mutableStateOf(false) }
    val tc = ButtonDefaults.textButtonColors(contentColor = pt.lilac)
    Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        if (onLyrics != null) TextButton(onClick = onLyrics, colors = tc) { Text(tr("الكلمات", "Lyrics")) }
        IconButton(onClick = onSearch) { Icon(Icons.Rounded.Search, null, tint = pt.lilac) }
        Box {
            IconButton(onClick = { syncMenu = true }) { Icon(Icons.Rounded.Tune, null, tint = if (off != 0L) pt.ink else pt.lilac) }
            DropdownMenu(syncMenu, { syncMenu = false }) {
                Text(tr("تزامن الكلمات: ", "Lyrics sync: ") + (if (off > 0) "+" else "") + "${off / 1000f}s", Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium)
                listOf(-1000L to "−1.0s", -500L to "−0.5s", 500L to "+0.5s", 1000L to "+1.0s").forEach { (d, label) ->
                    DropdownMenuItem(text = { Text(tr("الكلمات أبكر ", "Lyrics earlier ").takeIf { d > 0 } ?: tr("الكلمات أبطأ ", "Lyrics later "), maxLines = 1) },
                        trailingIcon = { Text(label) }, onClick = { onOff(off + d) })
                }
                DropdownMenuItem(text = { Text(tr("إعادة ضبط", "Reset")) }, onClick = { onOff(0L); syncMenu = false })
            }
        }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerThemeSheet(onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(tr("تخصيص المشغل", "Customize player"), style = MaterialTheme.typography.titleMedium)
            Text(tr("الثيم", "Theme"), style = MaterialTheme.typography.labelMedium)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PThemes.forEachIndexed { i, t ->
                    Column(Modifier.clickable { TB.pickPlTheme(i) }, horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.size(46.dp).clip(CircleShape).background(Brush.linearGradient(listOf(t.bg1, t.disc)))
                            .border(if (TB.plTheme == i) 3.dp else 1.dp, if (TB.plTheme == i) t.ink else Color.White.copy(alpha = 0.2f), CircleShape))
                        Text(tr(t.ar, t.en), fontSize = 11.sp, maxLines = 1)
                    }
                }
            }
            Text(tr("الخلفية", "Background"), style = MaterialTheme.typography.labelMedium)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(tr("تدرّج", "Gradient"), tr("غلاف مموّه", "Blurred cover"), tr("غلاف جانبي", "Side cover")).forEachIndexed { i, s ->
                    FilterChip(selected = TB.plBg == i, onClick = { TB.pickPlBg(i) }, label = { Text(s) })
                }
            }
            if (TB.plBg != 0) {
                Text(tr("التمويه", "Blur") + ": ${TB.plBlur}", style = MaterialTheme.typography.labelMedium)
                Slider(value = TB.plBlur.toFloat(), onValueChange = { TB.pickPlBlur(it.toInt()) }, valueRange = 0f..25f)
                Text(tr("التعتيم", "Dim") + ": ${TB.plDim}%", style = MaterialTheme.typography.labelMedium)
                Slider(value = TB.plDim.toFloat(), onValueChange = { TB.pickPlDim(it.toInt()) }, valueRange = 0f..80f)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tr("إظهار الأسطوانة", "Show vinyl"), Modifier.weight(1f))
                Switch(checked = TB.plDisc, onCheckedChange = { TB.pickPlDisc(it) })
            }
            if (TB.plDisc) {
                Text(tr("شكل الأسطوانة", "Vinyl style"), style = MaterialTheme.typography.labelMedium)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(tr("حسب الثيم", "Theme"), tr("أسود كلاسيكي", "Classic black"), tr("بلون الغلاف", "Cover tint")).forEachIndexed { i, x ->
                        FilterChip(selected = TB.plStyle == i, onClick = { TB.pickPlStyle(i) }, label = { Text(x) })
                    }
                }
                Text(tr("سرعة الدوران", "Spin speed"), style = MaterialTheme.typography.labelMedium)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(tr("بطيء", "Slow"), tr("عادي", "Normal"), tr("سريع", "Fast")).forEachIndexed { i, x ->
                        FilterChip(selected = TB.plSpeed == i, onClick = { TB.pickPlSpeed(i) }, label = { Text(x) })
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(tr("إظهار الذراع", "Show tonearm"), Modifier.weight(1f))
                    Switch(checked = TB.plArm, onCheckedChange = { TB.pickPlArm(it) })
                }
            }
            Text(tr("حجم الكلمات", "Lyrics size") + ": ${TB.plLyr}%", style = MaterialTheme.typography.labelMedium)
            Slider(value = TB.plLyr.toFloat(), onValueChange = { TB.pickPlLyr(it.toInt()) }, valueRange = 70f..150f)
        }
    }
}
