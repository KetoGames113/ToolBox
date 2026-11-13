package com.toolbox.app.ui.screens

import android.os.StatFs
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.toolbox.app.ROOT
import com.toolbox.app.tr
import com.toolbox.app.ui.components.*
import com.toolbox.app.ui.theme.*

data class Tool(val route: String, val ar: String, val en: String, val hintAr: String, val hintEn: String,
                val icon: ImageVector, val accent: Color)

val Tools = listOf(
    Tool("cleaner", "المنظف", "Cleaner", "حرّر مساحة بنقرة", "Free up space", Icons.Rounded.CleaningServices, AccCleaner),
    Tool("files", "مدير الملفات", "Files", "تصفّح وأدر ملفاتك", "Browse & manage", Icons.Rounded.Folder, AccFiles),
    Tool("transfer", "النقل للكمبيوتر", "Transfer to PC", "عبر الشبكة المحلية", "Over local network", Icons.Rounded.SwapHoriz, AccTransfer),
    Tool("videos", "الفيديو", "Video", "مشغّل سلس", "Smooth player", Icons.Rounded.PlayCircle, AccVideo),
    Tool("audios", "الصوتيات", "Audio", "موسيقاك في مكان واحد", "Your music, one place", Icons.Rounded.MusicNote, AccAudio),
    Tool("photos", "الصور", "Photos", "معرض سريع", "Fast gallery", Icons.Rounded.Image, AccImages),
    Tool("editor", "محرر الصور", "Photo editor", "قص وفلاتر ورسم", "Crop, filters, draw", Icons.Rounded.Brush, AccEditor),
    Tool("apk", "APK / XAPK", "APK / XAPK", "تثبيت واستخراج", "Install & extract", Icons.Rounded.Android, AccApk),
    Tool("dl", "مدير التحميل", "Downloader", "تحميلات منظّمة", "Organized downloads", Icons.Rounded.Download, AccDownload),
    Tool("notes", "المفكرة", "Notepad", "ملاحظات سريعة", "Quick notes", Icons.Rounded.EditNote, AccNotes),
    Tool("device", "معلومات الجهاز", "Device info", "المواصفات والحالة", "Specs & status", Icons.Rounded.Info, AccInfo),
    Tool("settings", "الإعدادات", "Settings", "اللغة والصلاحيات", "Language & permissions", Icons.Rounded.Settings, AccSettings),
)

@Composable
fun HomeScreen(onOpen: (String) -> Unit) {
    val storage = remember {
        try {
            val st = StatFs(ROOT.path)
            val t = st.totalBytes.toFloat(); val f = st.availableBytes.toFloat()
            Pair((t - f) / 1e9f, t / 1e9f)
        } catch (_: Throwable) { Pair(0f, 1f) }
    }
    Box(Modifier.fillMaxSize().background(Ink0)) {
        Box(Modifier.fillMaxWidth().height(260.dp)
            .background(Brush.verticalGradient(listOf(Lavender.copy(.14f), Color.Transparent))))
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(span = { GridItemSpan(2) }) {
                Column(Modifier.padding(top = 8.dp, bottom = 6.dp)) {
                    Text("ToolBox", style = MaterialTheme.typography.displaySmall, color = TextHi)
                    Text(tr("كل أدواتك في مكان واحد", "All your tools in one place"),
                        style = MaterialTheme.typography.bodyMedium, color = TextMid)
                }
            }
            item(span = { GridItemSpan(2) }) { StorageHero(storage.first, storage.second) { onOpen("cleaner") } }
            items(Tools) { t ->
                ToolCard(tr(t.ar, t.en), tr(t.hintAr, t.hintEn), t.icon, t.accent, { onOpen(t.route) })
            }
        }
    }
}

@Composable
private fun StorageHero(used: Float, total: Float, onClean: () -> Unit) {
    val shape = RoundedCornerShape(28.dp)
    Row(Modifier.fillMaxWidth()
        .background(Brush.linearGradient(listOf(Color(0xFF2B2450), Ink1)), shape)
        .border(1.dp, Stroke, shape).padding(20.dp),
        horizontalArrangement = Arrangement.SpaceBetween) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(tr("مساحة التخزين", "Storage"), style = MaterialTheme.typography.labelMedium, color = TextMid)
            Text("%.1f / %.1f GB".format(used, total), style = MaterialTheme.typography.headlineSmall, color = TextHi)
            LinearProgressIndicator(progress = { (used / total).coerceIn(0f, 1f) }, Modifier.width(170.dp).height(8.dp),
                color = Lavender, trackColor = Ink3, strokeCap = StrokeCap.Round)
        }
        PrimaryPill(tr("تنظيف", "Clean"), onClean, Modifier.align(Alignment.CenterVertically))
    }
}
