@file:OptIn(ExperimentalMaterial3Api::class)
package com.toolbox.app

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.toolbox.app.ui.screens.HomeScreen
import com.toolbox.app.ui.theme.Ink0
import com.toolbox.app.ui.theme.TextHi
import com.toolbox.app.ui.theme.ToolBoxTheme
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import java.io.File

class Nav {
    val stack = mutableStateListOf("home" to "")
    fun go(r: String, a: String = "") { stack.add(r to a) }
    fun back(): Boolean {
        if (stack.size > 1) { stack.removeAt(stack.lastIndex); return true }
        return false
    }
}

val LocalNav = staticCompositionLocalOf<Nav> { error("no nav") }

class MainActivity : ComponentActivity() {
    companion object { var i: MainActivity? = null }

    val nav = Nav()
    val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { Tick.n++ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        i = this
        handle(intent)
        setContent { Root(nav) }
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handle(intent) }
    override fun onResume() { super.onResume(); Tick.n++ }
    override fun onWindowFocusChanged(hasFocus: Boolean) { super.onWindowFocusChanged(hasFocus); if (hasFocus) Clip.read(this) }

    private fun handle(it: Intent?) {
        if (it == null) return
        val rx = Regex("https?://\\S+")
        when (it.action) {
            Intent.ACTION_SEND -> {
                val t = it.getStringExtra(Intent.EXTRA_TEXT)
                val m = t?.let { s -> rx.find(s) }
                if (m != null) nav.go("dl", m.value)
                else @Suppress("DEPRECATION") (it.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))?.let { u -> openUri(u) }
            }
            Intent.ACTION_VIEW -> {
                val d = it.data ?: return
                if (d.scheme == "http" || d.scheme == "https") nav.go("dl", d.toString()) else openUri(d)
            }
        }
    }

    private fun openUri(u: Uri) {
        if (u.scheme == "file") { openPath(nav, this, u.path ?: return); return }
        val type = contentResolver.getType(u) ?: ""
        when {
            type.startsWith("video/") -> nav.go("video", u.toString())
            type.startsWith("audio/") -> nav.go("audio", u.toString())
            else -> Thread {
                try { val f = copyToCache(this, u); runOnUiThread { openPath(nav, this, f.path) } } catch (_: Throwable) {}
            }.start()
        }
    }
}

data class HomeItem(val icon: ImageVector, val ar: String, val en: String, val route: String)

fun titleOf(r: String) = when (r) {
    "home" -> "ToolBox"
    "settings" -> tr("الإعدادات والصلاحيات", "Settings & permissions")
    "device" -> tr("معلومات الجهاز", "Device info")
    "cleaner" -> tr("المنظف", "Cleaner")
    "apk" -> tr("استخراج وتثبيت APK/XAPK", "APK / XAPK tools")
    "bundle" -> tr("تثبيت", "Install")
    "transfer" -> tr("النقل (جوال ↔ كمبيوتر)", "Transfer (phone ↔ PC)")
    "files" -> tr("مدير الملفات", "File manager")
    "archive" -> tr("أرشيف", "Archive")
    "dl" -> tr("مدير التحميل", "Downloader")
    "videos" -> tr("الفيديو", "Video")
    "video" -> tr("مشغل الفيديو", "Video player")
    "audios", "audio" -> tr("مشغل الصوت", "Audio player")
    "photos" -> tr("الصور", "Photos")
    "image" -> tr("عارض الصور", "Image viewer")
    "notes" -> tr("المفكرة", "Notepad")
    "note" -> tr("ملاحظة", "Note")
    "editor" -> tr("محرر الصور", "Photo editor")
    "html" -> "HTML"
    "pdf" -> "PDF"
    "text" -> tr("محرر النصوص", "Text editor")
    else -> "ToolBox"
}

@Composable
fun Root(nav: Nav) {
    ToolBoxTheme(arabic = L.ar) {
        UpdateHost()
        CompositionLocalProvider(
            LocalLayoutDirection provides (if (L.ar) LayoutDirection.Rtl else LayoutDirection.Ltr),
            LocalNav provides nav
        ) {
            BackHandler(enabled = nav.stack.size > 1) { nav.back() }
            val (route, arg) = nav.stack.last()
            val immersive = (route == "audio" || route == "audios") && Au.full
            Box(Modifier.fillMaxSize().background(Ink0)) {
            BackgroundLayer()
            Scaffold(containerColor = Color.Transparent, contentColor = TextHi, topBar = {
                if (route != "home" && !immersive) TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent, titleContentColor = TextHi, navigationIconContentColor = TextHi, actionIconContentColor = TextHi),
                    title = { Text(titleOf(route)) },
                    navigationIcon = {
                        if (nav.stack.size > 1) IconButton(onClick = { nav.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) }
                    },
                    actions = {
                        if (route == "home") IconButton(onClick = { nav.go("settings") }) { Icon(Icons.Default.Settings, null) }
                    }
                )
            }) { pad ->
                Box((if (immersive) Modifier else Modifier.padding(pad)).fillMaxSize()) {
                    AnimatedContent(targetState = route to arg, transitionSpec = {
                        (fadeIn(tween(260)) + scaleIn(tween(260), initialScale = 0.96f)) togetherWith fadeOut(tween(120))
                    }, label = "nav") { (route, arg) ->
                        when (route) {
                            "home" -> HomeScreen { nav.go(it) }
                            "settings" -> SettingsScreen()
                            "device" -> DeviceScreen()
                            "cleaner" -> CleanerScreen()
                            "apk" -> ApkScreen()
                            "bundle" -> BundleScreen(arg)
                            "transfer" -> TransferScreen()
                            "files" -> FilesScreen(arg)
                            "archive" -> ArchiveScreen(arg)
                            "dl" -> DlScreen(arg)
                            "videos" -> VideoListScreen()
                            "video" -> VideoScreen(arg)
                            "audios" -> AudioScreen("")
                            "audio" -> AudioScreen(arg)
                            "photos" -> PhotosScreen()
                            "image" -> ImageScreen(arg)
                            "notes" -> NotesScreen()
                            "note" -> NoteScreen(arg)
                            "editor" -> EditorScreen(arg)
                            "html" -> HtmlScreen(arg)
                            "pdf" -> PdfScreen(arg)
                            "text" -> TextScreen(arg)
                        }
                    }
                }
            }
            }
        }
    }
}

@Composable
fun Home(nav: Nav) {
    val items = listOf(
        HomeItem(Icons.Default.CleaningServices, "المنظف", "Cleaner", "cleaner"),
        HomeItem(Icons.Default.Info, "معلومات الجهاز", "Device info", "device"),
        HomeItem(Icons.Default.Folder, "مدير الملفات", "Files", "files"),
        HomeItem(Icons.Default.Android, "APK / XAPK", "APK / XAPK", "apk"),
        HomeItem(Icons.Default.SwapHoriz, "النقل للكمبيوتر", "Transfer to PC", "transfer"),
        HomeItem(Icons.Default.Download, "مدير التحميل", "Downloader", "dl"),
        HomeItem(Icons.Default.PlayCircle, "الفيديو", "Video", "videos"),
        HomeItem(Icons.Default.MusicNote, "الصوتيات", "Audio", "audios"),
        HomeItem(Icons.Default.Image, "الصور", "Photos", "photos"),
        HomeItem(Icons.Default.EditNote, "المفكرة", "Notepad", "notes"),
        HomeItem(Icons.Default.Brush, "محرر الصور", "Photo editor", "editor"),
        HomeItem(Icons.Default.Settings, "الإعدادات", "Settings", "settings"),
    )
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(items) { it ->
            Card(onClick = { nav.go(it.route) }, modifier = Modifier.fillMaxWidth().height(110.dp)) {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(it.icon, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(8.dp))
                    Text(tr(it.ar, it.en), textAlign = TextAlign.Center)
                }
            }
        }
    }
}
