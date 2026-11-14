package com.toolbox.app.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.toolbox.app.ui.theme.*

/** Press-scale feedback shared by every tappable card. */
@Composable
private fun Modifier.pressable(onClick: () -> Unit, shape: androidx.compose.ui.graphics.Shape): Modifier {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val sc by animateFloatAsState(if (pressed) 0.96f else 1f, spring(dampingRatio = 0.6f), label = "press")
    return this.scale(sc).clip(shape).clickable(src, LocalIndication.current, onClick = onClick)
}

/** Home grid tile: tinted icon badge + title + one-line hint. */
@Composable
fun ToolCard(title: String, hint: String, icon: ImageVector, accent: Color,
             onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(26.dp)
    Column(
        modifier.fillMaxWidth().height(148.dp)
            .pressable(onClick, shape)
            .background(Brush.linearGradient(listOf(Ink2, Ink1)), shape)
            .background(Brush.linearGradient(listOf(Color.White.copy(alpha = if (TB.glass > 0) 0.10f else 0f), Color.Transparent)), shape)
            .border(1.dp, GlassStroke, shape)
            .padding(16.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Box(
            Modifier.size(48.dp)
                .then(if (TB.bareIcons) Modifier else Modifier.background(Brush.radialGradient(listOf(accent.copy(.32f), accent.copy(.10f))), RoundedCornerShape(16.dp))),
            contentAlignment = Alignment.Center
        ) { Icon(icon, null, tint = accent, modifier = Modifier.size(26.dp)) }
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium, color = TextHi, maxLines = 1)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = TextLow,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Screen top bar: back arrow, heavy title, optional trailing slot. */
@Composable
fun ToolBoxTopBar(title: String, onBack: (() -> Unit)? = null, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) IconButton(onClick = onBack,
            modifier = Modifier.size(44.dp).background(Ink1, CircleShape).border(1.dp, GlassStroke, CircleShape)) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, null, tint = TextHi)
        }
        Text(title, style = MaterialTheme.typography.headlineSmall, color = TextHi,
            modifier = Modifier.weight(1f).padding(horizontal = 12.dp), maxLines = 1)
        trailing()
    }
}

/** Card container used for settings sections, cache box, etc. */
@Composable
fun SectionCard(modifier: Modifier = Modifier, title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(24.dp)
    Column(modifier.fillMaxWidth().background(Ink1, shape).border(1.dp, GlassStroke, shape).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (title != null) Text(title, style = MaterialTheme.typography.titleMedium, color = TextHi)
        content()
    }
}

/** Filled lavender action button (pill). */
@Composable
fun PrimaryPill(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = CircleShape
    Box(modifier.height(48.dp).pressable(onClick, shape)
        .background(Brush.horizontalGradient(listOf(Lavender, Color(0xFFB59CFF))), shape)
        .padding(horizontal = 26.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = OnLavender)
    }
}

/** Outlined secondary pill / chip. */
@Composable
fun GhostPill(text: String, onClick: () -> Unit, selected: Boolean = false, modifier: Modifier = Modifier) {
    val shape = CircleShape
    Box(modifier.height(40.dp).pressable(onClick, shape)
        .background(if (selected) Lavender.copy(.16f) else Color.Transparent, shape)
        .border(1.dp, if (selected) Lavender.copy(.6f) else Ink3, shape)
        .padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = if (selected) Lavender else TextMid)
    }
}

/** Animated segmented storage bar + legend rows. */
data class Seg(val label: String, val value: Float, val color: Color, val text: String)

@Composable
fun StorageBar(segments: List<Seg>, free: Seg) {
    val all = segments + free
    val total = all.sumOf { it.value.toDouble() }.toFloat()
    var go by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { go = true }
    val p by animateFloatAsState(if (go) 1f else 0f, tween(900, easing = FastOutSlowInEasing), label = "bar")
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(Modifier.fillMaxWidth().height(14.dp).clip(CircleShape).background(Ink3), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            all.forEach { s ->
                val w = (s.value / total * p).coerceAtLeast(0.0001f)
                Box(Modifier.weight(w).fillMaxHeight().background(s.color.copy(alpha = if (s === free) .35f else 1f)))
            }
        }
        all.forEach { s ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).background(s.color, CircleShape))
                Text(s.label, Modifier.weight(1f).padding(horizontal = 10.dp), color = TextHi, style = MaterialTheme.typography.bodyMedium)
                Text(s.text, color = TextMid, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

/** Scrollable pill tabs (replaces the thin underline tabs). */
@Composable
fun PillTabs(tabs: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.padding(horizontal = 16.dp).fillMaxWidth().background(Ink1, CircleShape).padding(4.dp)) {
        tabs.forEachIndexed { i, t ->
            val on = i == selected
            Box(Modifier.weight(1f).height(40.dp).clip(CircleShape)
                .background(if (on) Lavender else Color.Transparent)
                .clickable { onSelect(i) }, contentAlignment = Alignment.Center) {
                Text(t, style = MaterialTheme.typography.labelMedium, color = if (on) OnLavender else TextMid, maxLines = 1)
            }
        }
    }
}

/** File / track row: icon badge, name, meta. */
@Composable
fun ItemRow(title: String, meta: String, icon: ImageVector, accent: Color, trailing: String? = null, onClick: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(46.dp).background(accent.copy(.14f), RoundedCornerShape(15.dp)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = accent, modifier = Modifier.size(24.dp))
        }
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = TextHi, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(meta, style = MaterialTheme.typography.bodySmall, color = TextLow, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (trailing != null) Text(trailing, style = MaterialTheme.typography.labelSmall, color = TextLow)
    }
}

/** Search field that actually looks like a field. */
@Composable
fun SearchField(value: String, onChange: (String) -> Unit, hint: String, modifier: Modifier = Modifier) {
    val shape = CircleShape
    Row(modifier.fillMaxWidth().height(52.dp).background(Ink1, shape).border(1.dp, GlassStroke, shape).padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.Search, null, tint = TextLow)
        Box(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            if (value.isEmpty()) Text(hint, color = TextLow, style = MaterialTheme.typography.bodyMedium)
            androidx.compose.foundation.text.BasicTextField(value, onChange,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = TextHi), singleLine = true,
                cursorBrush = androidx.compose.ui.graphics.SolidColor(Lavender))
        }
    }
}

/** Floating mini player (audio / video). */
@Composable
fun MiniPlayer(title: String, artist: String, progress: Float, playing: Boolean,
               onToggle: () -> Unit, onNext: () -> Unit, onPrev: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(28.dp)
    Column(modifier.fillMaxWidth().padding(12.dp)
        .background(Brush.verticalGradient(listOf(Color(0xFF2A2840).copy(alpha = TB.cardAlpha), Ink2)), shape)
        .border(1.dp, GlassStroke, shape).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium, color = TextHi, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(artist, style = MaterialTheme.typography.bodySmall, color = TextMid)
        }
        LinearProgressIndicator(progress = { progress }, Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
            color = Lavender, trackColor = Ink3, strokeCap = androidx.compose.ui.graphics.StrokeCap.Round)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onPrev) { Icon(Icons.Rounded.SkipPrevious, null, tint = TextHi, modifier = Modifier.size(30.dp)) }
            Box(Modifier.size(64.dp).clip(CircleShape).background(Lavender).clickable(onClick = onToggle), contentAlignment = Alignment.Center) {
                Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    null, tint = OnLavender, modifier = Modifier.size(34.dp))
            }
            IconButton(onNext) { Icon(Icons.Rounded.SkipNext, null, tint = TextHi, modifier = Modifier.size(30.dp)) }
        }
    }
}
