package com.toolbox.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.decode.SvgDecoder
import coil.decode.VideoFrameDecoder
import java.io.File
import java.util.Locale

object L { var ar by mutableStateOf(true) }
fun tr(ar: String, en: String) = if (L.ar) ar else en
object Tick { var n by mutableIntStateOf(0) }

class App : Application(), ImageLoaderFactory {
    companion object { lateinit var i: App }

    override fun onCreate() {
        super.onCreate()
        i = this
        L.ar = prefs().getString("lang", "ar") == "ar"
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel("tb", "ToolBox", NotificationManager.IMPORTANCE_LOW))
        Dl.load()
    }

    fun prefs() = getSharedPreferences("tb", MODE_PRIVATE)

    override fun newImageLoader() = ImageLoader.Builder(this).components {
        if (Build.VERSION.SDK_INT >= 28) add(ImageDecoderDecoder.Factory()) else add(GifDecoder.Factory())
        add(SvgDecoder.Factory())
        add(VideoFrameDecoder.Factory())
    }.crossfade(true).build()
}

val ROOT: File get() = Environment.getExternalStorageDirectory()
fun tbDir(sub: String): File = File(ROOT, "ToolBox/$sub").apply { mkdirs() }

fun fmt(b: Long): String {
    if (b < 0) return "?"
    val u = arrayOf("B", "KB", "MB", "GB", "TB")
    var v = b.toDouble(); var i = 0
    while (v >= 1024 && i < 4) { v /= 1024; i++ }
    return if (i == 0) "$b B" else String.format(Locale.US, "%.1f %s", v, u[i])
}

fun toast(s: String) { Handler(Looper.getMainLooper()).post { Toast.makeText(App.i, s, Toast.LENGTH_LONG).show() } }

fun uniqueFile(dir: File, name: String): File {
    var f = File(dir, name)
    if (!f.exists()) return f
    val base = if (name.contains('.')) name.substringBeforeLast('.') else name
    val ext = if (name.contains('.')) "." + name.substringAfterLast('.') else ""
    var i = 1
    while (f.exists()) { f = File(dir, "$base ($i)$ext"); i++ }
    return f
}

fun scan(vararg p: String) { try { MediaScannerConnection.scanFile(App.i, p, null, null) } catch (_: Throwable) {} }
fun mimeOf(path: String): String = MimeTypeMap.getSingleton().getMimeTypeFromExtension(File(path).extension.lowercase()) ?: "*/*"
fun uriFor(c: Context, f: File): Uri = FileProvider.getUriForFile(c, c.packageName + ".fp", f)

fun shareFiles(c: Context, files: List<File>) {
    val fs = files.filter { it.isFile }
    if (fs.isEmpty()) return
    val uris = ArrayList(fs.map { uriFor(c, it) })
    val i = Intent(if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE)
    i.type = if (uris.size == 1) mimeOf(fs[0].path) else "*/*"
    if (uris.size == 1) i.putExtra(Intent.EXTRA_STREAM, uris[0]) else i.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    c.startActivity(Intent.createChooser(i, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

fun copyToCache(c: Context, u: Uri): File {
    var name = "file_${System.currentTimeMillis()}"
    try {
        c.contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) name = it.getString(0) ?: name
        }
    } catch (_: Throwable) {}
    val f = File(File(c.cacheDir, "in").apply { mkdirs() }, name)
    c.contentResolver.openInputStream(u)?.use { i -> f.outputStream().use { o -> i.copyTo(o, 1 shl 16) } }
    return f
}
