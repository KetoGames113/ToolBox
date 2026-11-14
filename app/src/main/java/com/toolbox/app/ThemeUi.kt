package com.toolbox.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.toolbox.app.ui.theme.Accents
import com.toolbox.app.ui.theme.TB
import com.toolbox.app.ui.theme.TextHi
import com.toolbox.app.ui.theme.TextMid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** خلفية التطبيق: صورة اختيارية مع تمويه وتعتيم. */
@Composable
fun BackgroundLayer() {
    val path = TB.bg
    if (path.isEmpty()) return
    val blur = TB.blur
    val bmp by produceState<ImageBitmap?>(null, path, blur) {
        value = withContext(Dispatchers.IO) {
            try {
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(path, o)
                var s = 1
                while (o.outWidth / s > 1600 || o.outHeight / s > 1600) s *= 2
                var b: Bitmap = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = s }) ?: return@withContext null
                if (Build.VERSION.SDK_INT < 31 && blur > 0) {
                    val f = 1 + blur / 3
                    b = Bitmap.createScaledBitmap(b, (b.width / f).coerceAtLeast(8), (b.height / f).coerceAtLeast(8), true)
                }
                b.asImageBitmap()
            } catch (_: Throwable) { null }
        }
    }
    val img = bmp ?: return
    Box(Modifier.fillMaxSize()) {
        Image(img, null,
            Modifier.fillMaxSize().then(if (Build.VERSION.SDK_INT >= 31 && blur > 0) Modifier.blur(blur.dp) else Modifier),
            contentScale = ContentScale.Crop, filterQuality = FilterQuality.Medium)
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = TB.dim / 100f)))
    }
}

@Composable
fun ThemeCard() {
    val c = LocalContext.current
    val scope = rememberCoroutineScope()
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        if (u != null) scope.launch(Dispatchers.IO) {
            try {
                val f = File(c.filesDir, "bg_${System.currentTimeMillis()}.jpg")
                c.contentResolver.openInputStream(u)?.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
                withContext(Dispatchers.Main) { TB.pickBg(f.path) }
            } catch (_: Throwable) {}
        }
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(tr("المظهر", "Appearance"), style = MaterialTheme.typography.titleMedium)
            Text(tr("لون التطبيق", "Accent color"), style = MaterialTheme.typography.labelMedium, color = TextMid)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Accents.forEachIndexed { i, a ->
                    Box(Modifier.size(40.dp).clip(CircleShape).background(Brush.linearGradient(listOf(a.main, a.deep)))
                        .border(if (TB.accent == i) 3.dp else 0.dp, TextHi, CircleShape).clickable { TB.pickAccent(i) })
                }
            }
            Text(tr("صورة الخلفية", "Background image"), style = MaterialTheme.typography.labelMedium, color = TextMid)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { pick.launch(arrayOf("image/*")) }) { Text(tr("اختيار صورة", "Choose image")) }
                if (TB.bg.isNotEmpty()) OutlinedButton(onClick = { TB.pickBg("") }) { Text(tr("إزالة", "Remove")) }
            }
            if (TB.bg.isNotEmpty()) {
                Text(tr("التمويه (Blur)", "Blur") + ": ${TB.blur}", style = MaterialTheme.typography.labelMedium, color = TextMid)
                Slider(value = TB.blur.toFloat(), onValueChange = { TB.pickBlur(it.toInt()) }, valueRange = 0f..25f)
                Text(tr("التعتيم", "Dim") + ": ${TB.dim}%", style = MaterialTheme.typography.labelMedium, color = TextMid)
                Slider(value = TB.dim.toFloat(), onValueChange = { TB.pickDim(it.toInt()) }, valueRange = 0f..90f)
            }
            Text(tr("شفافية البطاقات (زجاج)", "Card transparency (glass)") + ": ${TB.glass}%", style = MaterialTheme.typography.labelMedium, color = TextMid)
            Slider(value = TB.glass.toFloat(), onValueChange = { TB.pickGlass(it.toInt()) }, valueRange = 0f..85f)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tr("أيقونات بدون خلفية", "Icons without tile"), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Switch(checked = TB.bareIcons, onCheckedChange = { TB.pickBare(it) })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(tr("جلب كلمات الأغاني من الإنترنت", "Fetch lyrics online"), style = MaterialTheme.typography.bodyMedium)
                    Text(tr("يرسل اسم الأغنية والفنان إلى lrclib.net", "Sends song title and artist to lrclib.net"), style = MaterialTheme.typography.bodySmall, color = TextMid)
                }
                Switch(checked = TB.lyricsOnline, onCheckedChange = { TB.pickLyrics(it) })
            }
        }
    }
}
