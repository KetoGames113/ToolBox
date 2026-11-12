@file:OptIn(ExperimentalMaterial3Api::class)
package com.toolbox.app

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

val IMG = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "svg", "heic", "heif", "avif", "ico", "tif", "tiff")
val VID = setOf("mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "ts", "m4v", "3gp", "3g2", "mpg", "mpeg", "rmvb", "rm", "vob", "m2ts", "mts", "ogv", "asf", "divx", "f4v")
val AUD = setOf("mp3", "flac", "wav", "ogg", "oga", "m4a", "aac", "opus", "wma", "ape", "mka", "amr", "mid", "midi", "aiff", "aif", "wv", "ac3", "dts")
val TXT = setOf("txt", "log", "json", "xml", "md", "csv", "ini", "conf", "cfg", "js", "css", "kt", "java", "py", "sh", "yaml", "yml", "properties", "srt", "vtt", "ass", "sql", "c", "cpp", "h", "gradle", "toml", "bat", "tsv", "rb", "php", "smali")
val ARC = setOf("zip", "rar", "7z", "tar", "gz", "tgz", "bz2", "xz", "cbz", "jar")

fun openPath(nav: Nav, c: Context, path: String) {
    val e = File(path).extension.lowercase()
    when {
        e == "apk" || e == "xapk" || e == "apks" || e == "apkm" -> nav.go("bundle", path)
        e in IMG -> nav.go("image", path)
        e in VID -> nav.go("video", path)
        e in AUD -> nav.go("audio", path)
        e == "pdf" -> nav.go("pdf", path)
        e in setOf("html", "htm", "xhtml", "mht", "mhtml") -> nav.go("html", path)
        e in ARC -> nav.go("archive", path)
        e in TXT -> nav.go("text", path)
        else -> external(c, path)
    }
}

fun external(c: Context, path: String) {
    val i = Intent(Intent.ACTION_VIEW).setDataAndType(uriFor(c, File(path)), mimeOf(path))
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    try { c.startActivity(i) } catch (_: Throwable) { toast(tr("لا يوجد تطبيق لفتح الملف", "No app can open this file")) }
}

@Composable
fun HtmlScreen(path: String) {
    AndroidView(factory = { ctx ->
        WebView(ctx).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            settings.allowContentAccess = true
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            webViewClient = WebViewClient()
            loadUrl(if (path.startsWith("http")) path else "file://$path")
        }
    }, modifier = Modifier.fillMaxSize())
}

@Composable
fun PdfScreen(path: String) {
    val pfd = remember { ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY) }
    val r = remember { PdfRenderer(pfd) }
    DisposableEffect(Unit) { onDispose { try { r.close(); pfd.close() } catch (_: Throwable) {} } }
    val lock = remember { Any() }
    var zoom by remember { mutableFloatStateOf(1f) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { zoom = (zoom - 0.5f).coerceAtLeast(1f) }) { Text("−") }
            Text("  ${(zoom * 100).toInt()}%  ")
            OutlinedButton(onClick = { zoom = (zoom + 0.5f).coerceAtMost(2.5f) }) { Text("+") }
            Spacer(Modifier.width(12.dp))
            Text("${r.pageCount} " + tr("صفحة", "pages"))
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val w = with(androidx.compose.ui.platform.LocalDensity.current) { (maxWidth * zoom).roundToPx() }.coerceAtMost(2600)
            Box(Modifier.horizontalScroll(rememberScrollState())) {
                LazyColumn(Modifier.width(maxWidth * zoom)) {
                    items(r.pageCount) { i ->
                        val bmp by produceState<ImageBitmap?>(null, i, w) {
                            value = withContext(Dispatchers.IO) {
                                synchronized(lock) {
                                    r.openPage(i).use { p ->
                                        val h = (w.toFloat() * p.height / p.width).toInt().coerceAtLeast(1)
                                        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                                        b.eraseColor(android.graphics.Color.WHITE)
                                        p.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                        b.asImageBitmap()
                                    }
                                }
                            }
                        }
                        val b = bmp
                        if (b != null) Image(b, null, Modifier.fillMaxWidth().padding(bottom = 4.dp))
                        else Box(Modifier.fillMaxWidth().height(400.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    }
                }
            }
        }
    }
}

@Composable
fun TextScreen(path: String) {
    val f = File(path)
    var text by remember { mutableStateOf("") }
    var ro by remember { mutableStateOf(false) }
    LaunchedEffect(path) {
        withContext(Dispatchers.IO) {
            try {
                if (f.length() > 1_000_000) { ro = true; text = f.inputStream().use { i -> val b = ByteArray(1_000_000); val n = i.read(b); String(b, 0, maxOf(n, 0)) } }
                else text = f.readText()
            } catch (e: Throwable) { text = "Error: ${e.message}"; ro = true }
        }
    }
    Column(Modifier.fillMaxSize().padding(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(f.name, Modifier.weight(1f), maxLines = 1)
            if (!ro) Button(onClick = { try { f.writeText(text); toast(tr("تم الحفظ", "Saved")) } catch (e: Throwable) { toast("Error: ${e.message}") } }) { Text(tr("حفظ", "Save")) }
        }
        if (ro) Text(tr("للقراءة فقط (ملف كبير)", "Read-only (large file)"), style = MaterialTheme.typography.bodySmall)
        Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(top = 8.dp)) {
            BasicTextField(
                value = text, onValueChange = { if (!ro) text = it }, readOnly = ro,
                textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
