@file:OptIn(ExperimentalMaterial3Api::class)
package com.toolbox.app

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

object Perms {
    fun files(c: Context) = if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
    else c.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    fun media(c: Context) = if (Build.VERSION.SDK_INT >= 33) c.checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED
    else c.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    @Suppress("DEPRECATION")
    fun usage(c: Context): Boolean {
        val m = c.getSystemService(AppOpsManager::class.java)
        val mode = if (Build.VERSION.SDK_INT >= 29) m.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.packageName)
        else m.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun install(c: Context) = c.packageManager.canRequestPackageInstalls()
    fun battery(c: Context) = c.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(c.packageName)
    fun notif(c: Context) = Build.VERSION.SDK_INT < 33 || c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun open(c: Context, action: String, withPkg: Boolean = true) {
        try {
            c.startActivity(Intent(action).apply {
                if (withPkg) data = Uri.parse("package:${c.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (_: Throwable) { toast("?") }
    }

    fun ask(vararg p: String) { MainActivity.i?.permLauncher?.launch(arrayOf(*p)) }

    fun grantFiles(c: Context) {
        if (Build.VERSION.SDK_INT >= 30) open(c, Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
        else ask(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }

    fun grantMedia() {
        if (Build.VERSION.SDK_INT >= 33) ask(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO)
        else ask(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
}

@Composable
fun PermRow(title: String, desc: String, ok: Boolean, onGrant: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (ok) Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary)
        else Button(onClick = onGrant) { Text(tr("منح", "Grant")) }
    }
}

@Composable
fun SettingsScreen() {
    val c = LocalContext.current
    val n = Tick.n
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text(tr("اللغة", "Language"), style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = L.ar, onClick = { L.ar = true; App.i.prefs().edit().putString("lang", "ar").apply() }, label = { Text("العربية") })
                    FilterChip(selected = !L.ar, onClick = { L.ar = false; App.i.prefs().edit().putString("lang", "en").apply() }, label = { Text("English") })
                }
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text(tr("الصلاحيات", "Permissions"), style = MaterialTheme.typography.titleMedium)
                key(n) {
                    PermRow(tr("الوصول لكل الملفات", "All files access"), tr("مدير الملفات والمنظف والنقل", "File manager, cleaner, transfer"), Perms.files(c)) { Perms.grantFiles(c) }
                    PermRow(tr("الصور والفيديو والصوت", "Photos, video, audio"), tr("المعرض والمشغلات واستقبال الوسائط", "Gallery, players, media sync"), Perms.media(c)) { Perms.grantMedia() }
                    PermRow(tr("الوصول للاستخدام", "Usage access"), tr("لقراءة أحجام التطبيقات الحقيقية", "Real app sizes for the cleaner"), Perms.usage(c)) { Perms.open(c, Settings.ACTION_USAGE_ACCESS_SETTINGS, false) }
                    PermRow(tr("تثبيت التطبيقات", "Install apps"), tr("لتثبيت APK/XAPK", "Install APK/XAPK"), Perms.install(c)) { Perms.open(c, Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES) }
                    PermRow(tr("الإشعارات", "Notifications"), tr("للتحميل والنقل والمشغل", "Downloads, transfer, player"), Perms.notif(c)) { Perms.ask(android.Manifest.permission.POST_NOTIFICATIONS) }
                    PermRow(tr("تجاهل توفير البطارية", "Ignore battery optimization"), tr("حتى لا يتوقف النقل والتحميل", "Keep transfers alive"), Perms.battery(c)) { Perms.open(c, Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS) }
                }
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Shizuku", style = MaterialTheme.typography.titleMedium)
                val up = Sh.shizukuUp(); val ok = Sh.shizukuOk()
                Text(
                    when {
                        ok -> tr("جاهز ✔ (يعمل ويملك الصلاحية)", "Ready (running and granted)")
                        up -> tr("يعمل لكن لم تمنح الصلاحية", "Running, permission not granted")
                        else -> tr("غير مشغّل. ثبّت Shizuku وشغّله (ADB لاسلكي أو root)", "Not running. Install and start Shizuku (wireless ADB or root)")
                    }
                )
                Text(tr("يُستخدم للوصول إلى Android/data وobb وتنظيف كاش كل التطبيقات.", "Used for Android/data, obb and cleaning all apps' cache."), style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (up && !ok) Button(onClick = { Sh.ask() }) { Text(tr("طلب الصلاحية", "Request")) }
                    OutlinedButton(onClick = {
                        val i = c.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                        if (i != null) c.startActivity(i) else toast(tr("Shizuku غير مثبت", "Shizuku not installed"))
                    }) { Text(tr("فتح Shizuku", "Open Shizuku")) }
                }
            }
        }
        Text("ToolBox 1.0", style = MaterialTheme.typography.bodySmall)
    }
}
