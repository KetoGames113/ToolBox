package com.toolbox.app

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.pm.PackageInfoCompat
import com.toolbox.app.ui.theme.TextMid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** تحديث ذاتي: يقرأ update.json من إصدار latest على GitHub، ينزّل APK داخل التطبيق ويثبّته عبر PackageInstaller. */
object Updater {
    private const val BASE = "https://github.com/KetoGames113/ToolBox/releases/download/latest/"
    class Info(val code: Int, val name: String)

    var info by mutableStateOf<Info?>(null); private set
    var checking by mutableStateOf(false); private set
    var progress by mutableFloatStateOf(-1f); private set   // -1 خامل، 0..1 تنزيل، 2 تثبيت
    var status by mutableStateOf(""); private set
    var dismissed by mutableStateOf(false)

    fun installedCode(c: Context): Int = try {
        PackageInfoCompat.getLongVersionCode(c.packageManager.getPackageInfo(c.packageName, 0)).toInt()
    } catch (_: Throwable) { 0 }

    fun installedName(c: Context): String = try { c.packageManager.getPackageInfo(c.packageName, 0).versionName ?: "" } catch (_: Throwable) { "" }

    private fun open(u: String): HttpURLConnection {
        val c = URL(u).openConnection() as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 15000; c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "ToolBox-Updater")
        return c
    }

    suspend fun check(c: Context, manual: Boolean) {
        if (checking) return
        checking = true
        if (manual) status = tr("جارٍ التحقق…", "Checking…")
        val r = withContext(Dispatchers.IO) {
            try {
                val con = open(BASE + "update.json")
                if (con.responseCode != 200) null else JSONObject(con.inputStream.bufferedReader().use { it.readText() })
            } catch (_: Throwable) { null }
        }
        checking = false
        c.applicationContext.getSharedPreferences("tb", Context.MODE_PRIVATE).edit().putLong("upd_last", System.currentTimeMillis()).apply()
        if (r == null) { if (manual) status = tr("تعذّر الوصول لخادم التحديث", "Couldn't reach the update server"); return }
        val code = r.optInt("versionCode", 0)
        if (code > installedCode(c)) {
            info = Info(code, r.optString("versionName", ""))
            dismissed = false
            if (manual) status = tr("يوجد تحديث جديد", "Update available")
        } else {
            info = null
            if (manual) status = tr("أنت على آخر إصدار ✔", "You're up to date ✔")
        }
    }

    suspend fun install(c: Context) {
        if (info == null || progress >= 0f) return
        if (!c.packageManager.canRequestPackageInstalls()) {
            status = tr("اسمح بالتثبيت من ToolBox ثم ارجع واضغط تحديث", "Allow installs from ToolBox, then come back and tap Update")
            c.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + c.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        progress = 0f; status = tr("جارٍ التنزيل…", "Downloading…")
        val err = withContext(Dispatchers.IO) {
            try {
                val abi = Build.SUPPORTED_ABIS.firstOrNull { it == "arm64-v8a" || it == "armeabi-v7a" } ?: "arm64-v8a"
                val f = File(c.cacheDir, "update.apk"); f.delete()
                val con = open(BASE + "ToolBox-$abi.apk")
                if (con.responseCode != 200) return@withContext "HTTP " + con.responseCode
                val total = con.contentLengthLong
                con.inputStream.use { inp ->
                    f.outputStream().use { out ->
                        val buf = ByteArray(1 shl 16); var done = 0L; var last = 0L
                        while (true) {
                            val n = inp.read(buf); if (n < 0) break
                            out.write(buf, 0, n); done += n
                            if (total > 0 && done - last > 300_000) { last = done; progress = done.toFloat() / total }
                        }
                    }
                }
                val pi = c.packageManager.getPackageArchiveInfo(f.path, 0)
                if (pi == null || pi.packageName != c.packageName) return@withContext tr("الملف المنزّل غير صالح", "Downloaded file is invalid")
                progress = 2f; status = tr("جارٍ التثبيت…", "Installing…")
                val inst = c.packageManager.packageInstaller
                val p = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                if (Build.VERSION.SDK_INT >= 31) p.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                val id = inst.createSession(p)
                val s = inst.openSession(id)
                try {
                    s.openWrite("update", 0, f.length()).use { o -> f.inputStream().use { it.copyTo(o, 1 shl 16) }; s.fsync(o) }
                    val pend = PendingIntent.getBroadcast(c, id, Intent(c, InstallReceiver::class.java).setPackage(c.packageName),
                        PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                    s.commit(pend.intentSender)
                } catch (t: Throwable) { s.abandon(); throw t } finally { s.close() }
                null
            } catch (t: Throwable) { t.message ?: "error" }
        }
        if (err != null) { status = tr("فشل التحديث: ", "Update failed: ") + err; progress = -1f }
    }
}

/** يفحص التحديث تلقائياً عند الفتح (كل 6 ساعات) ويعرض نافذة التحديث. */
@Composable
fun UpdateHost() {
    val c = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        val p = c.getSharedPreferences("tb", Context.MODE_PRIVATE)
        if (p.getBoolean("upd_auto", true) && System.currentTimeMillis() - p.getLong("upd_last", 0L) > 6 * 3600_000L) Updater.check(c, false)
    }
    val i = Updater.info ?: return
    if (Updater.dismissed) return
    AlertDialog(
        onDismissRequest = { if (Updater.progress < 0f) Updater.dismissed = true },
        title = { Text(tr("تحديث جديد متوفر", "Update available")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("v" + i.name)
                if (Updater.progress >= 0f) LinearProgressIndicator(progress = { if (Updater.progress in 0f..1f) Updater.progress else 1f }, Modifier.fillMaxWidth())
                if (Updater.status.isNotEmpty()) Text(Updater.status, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(enabled = Updater.progress < 0f, onClick = { scope.launch { Updater.install(c) } }) { Text(tr("تحديث الآن", "Update now")) } },
        dismissButton = { TextButton(enabled = Updater.progress < 0f, onClick = { Updater.dismissed = true }) { Text(tr("لاحقاً", "Later")) } }
    )
}

@Composable
fun UpdateCard() {
    val c = LocalContext.current
    val scope = rememberCoroutineScope()
    var auto by remember { mutableStateOf(c.getSharedPreferences("tb", Context.MODE_PRIVATE).getBoolean("upd_auto", true)) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(tr("التحديث", "Updates"), style = MaterialTheme.typography.titleMedium)
            Text(tr("الإصدار الحالي: ", "Current version: ") + Updater.installedName(c), style = MaterialTheme.typography.bodyMedium, color = TextMid)
            if (Updater.status.isNotEmpty()) Text(Updater.status, style = MaterialTheme.typography.bodySmall, color = TextMid)
            if (Updater.progress >= 0f) LinearProgressIndicator(progress = { if (Updater.progress in 0f..1f) Updater.progress else 1f }, Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !Updater.checking && Updater.progress < 0f, onClick = { scope.launch { Updater.check(c, true) } }) { Text(tr("تحقق من التحديثات", "Check for updates")) }
                if (Updater.info != null) FilledTonalButton(enabled = Updater.progress < 0f, onClick = { scope.launch { Updater.install(c) } }) { Text(tr("تحديث الآن", "Update now")) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tr("التحقق تلقائياً عند الفتح", "Check automatically on launch"), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Switch(checked = auto, onCheckedChange = { auto = it; c.getSharedPreferences("tb", Context.MODE_PRIVATE).edit().putBoolean("upd_auto", it).apply() })
            }
        }
    }
}
