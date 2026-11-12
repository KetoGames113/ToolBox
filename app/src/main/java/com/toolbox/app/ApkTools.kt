@file:OptIn(ExperimentalMaterial3Api::class)
package com.toolbox.app

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.graphics.Bitmap
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

data class AppRow(val pkg: String, val label: String, val ver: String, val splits: Int, val size: Long, val system: Boolean)

class BundleInfo(val pkg: String?, val name: String?, val ver: String?, val apks: List<String>, val obbs: List<String>)

class InstallReceiver : BroadcastReceiver() {
    @Suppress("DEPRECATION")
    override fun onReceive(c: Context, i: Intent) {
        when (i.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val n = i.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (n != null) c.startActivity(n.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> toast(tr("تم التثبيت ✔", "Installed"))
            else -> toast(tr("فشل التثبيت: ", "Install failed: ") + (i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""))
        }
    }
}

object ApkTools {
    fun apps(c: Context): List<AppRow> {
        val pm = c.packageManager
        return pm.getInstalledApplications(0).mapNotNull { ai ->
            try {
                val pi = pm.getPackageInfo(ai.packageName, 0)
                val files = listOf(ai.sourceDir) + (ai.splitSourceDirs?.toList() ?: emptyList())
                AppRow(ai.packageName, pm.getApplicationLabel(ai).toString(), pi.versionName ?: "?",
                    ai.splitSourceDirs?.size ?: 0, files.sumOf { File(it).length() }, (ai.flags and 1) != 0)
            } catch (_: Throwable) { null }
        }.sortedBy { it.label.lowercase() }
    }

    fun obbDir(pkg: String) = File(ROOT, "Android/obb/$pkg")

    fun obbSize(pkg: String): Long {
        val d = obbDir(pkg)
        val direct = d.listFiles()?.filter { it.isFile }
        if (!direct.isNullOrEmpty()) return direct.sumOf { it.length() }
        if (Sh.ready()) {
            val (code, out) = Sh.run("du -sk ${Sh.q(d.path)}")
            if (code == 0) return (out.trim().split(Regex("\\s+")).firstOrNull()?.toLongOrNull() ?: 0L) * 1024
        }
        return 0L
    }

    private fun addFile(z: ZipOutputStream, f: File, name: String) {
        z.putNextEntry(ZipEntry(name))
        f.inputStream().use { it.copyTo(z, 1 shl 16) }
        z.closeEntry()
    }

    /** mode: 0 = APK الأساسي فقط، 1 = XAPK بكل الأجزاء، 2 = XAPK مع OBB */
    fun export(c: Context, pkg: String, mode: Int, prog: (String) -> Unit): File {
        val pm = c.packageManager
        val ai = pm.getApplicationInfo(pkg, 0)
        val pi = pm.getPackageInfo(pkg, 0)
        val label = pm.getApplicationLabel(ai).toString().replace(Regex("[\\\\/:*?\"<>|]"), "_")
        val ver = pi.versionName ?: "1"
        val out = tbDir("Exports")
        val apks = listOf(File(ai.sourceDir)) + (ai.splitSourceDirs?.map { File(it) } ?: emptyList())
        if (mode == 0) {
            prog(tr("نسخ APK…", "Copying APK…"))
            val dest = uniqueFile(out, "${label}_$ver.apk")
            apks[0].copyTo(dest); return dest
        }
        val dest = uniqueFile(out, "${label}_$ver.xapk")
        var obbFiles = emptyList<File>()
        var tmpObb: File? = null
        if (mode == 2) {
            val d = obbDir(pkg)
            val direct = d.listFiles()?.filter { it.isFile }
            if (!direct.isNullOrEmpty()) obbFiles = direct
            else if (Sh.ready()) {
                prog(tr("نسخ OBB عبر Shizuku…", "Copying OBB via Shizuku…"))
                tmpObb = File(ROOT, "ToolBox/.tmp_obb/$pkg").apply { mkdirs() }
                Sh.run("cp -r ${Sh.q(d.path)}/. ${Sh.q(tmpObb.path)}/")
                obbFiles = tmpObb.listFiles()?.filter { it.isFile } ?: emptyList()
            }
        }
        try {
            ZipOutputStream(BufferedOutputStream(FileOutputStream(dest), 1 shl 16)).use { z ->
                z.setLevel(Deflater.NO_COMPRESSION)
                val splits = JSONArray()
                apks.forEachIndexed { i, f ->
                    prog(f.name)
                    val n = if (i == 0) "$pkg.apk" else f.name
                    addFile(z, f, n)
                    splits.put(JSONObject().put("file", n).put("id", if (i == 0) "base" else f.name.removeSuffix(".apk")))
                }
                val exp = JSONArray()
                obbFiles.forEach { f ->
                    prog(f.name)
                    addFile(z, f, "Android/obb/$pkg/${f.name}")
                    exp.put(JSONObject().put("file", "Android/obb/$pkg/${f.name}").put("install_location", "EXTERNAL_OBB_DIR").put("install_path", "$pkg/${f.name}"))
                }
                val m = JSONObject().put("xapk_version", 2).put("package_name", pkg).put("name", label)
                    .put("version_name", ver).put("version_code", (if (android.os.Build.VERSION.SDK_INT >= 28) pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong()).toString())
                    .put("min_sdk_version", ai.minSdkVersion.toString()).put("target_sdk_version", ai.targetSdkVersion.toString())
                    .put("permissions", JSONArray(pi.requestedPermissions?.toList() ?: emptyList<String>()))
                    .put("split_apks", splits).put("expansions", exp)
                z.putNextEntry(ZipEntry("manifest.json")); z.write(m.toString(2).toByteArray()); z.closeEntry()
                try {
                    val bmp = pm.getApplicationIcon(pkg).toBitmap(192, 192)
                    z.putNextEntry(ZipEntry("icon.png")); bmp.compress(Bitmap.CompressFormat.PNG, 100, z); z.closeEntry()
                } catch (_: Throwable) {}
            }
        } finally { tmpObb?.parentFile?.deleteRecursively() }
        return dest
    }

    fun bundleInfo(f: File): BundleInfo? = try {
        ZipFile(f).use { z ->
            val names = z.entries().asSequence().map { it.name }.toList()
            var pkg: String? = null; var name: String? = null; var ver: String? = null
            z.getEntry("manifest.json")?.let { e ->
                val j = JSONObject(z.getInputStream(e).bufferedReader().readText())
                pkg = j.optString("package_name"); name = j.optString("name"); ver = j.optString("version_name")
            }
            val apks = if ("universal.apk" in names) listOf("universal.apk") else names.filter { it.endsWith(".apk") && !it.contains("/../") }
            val obbs = names.filter { it.startsWith("Android/obb/") && !it.endsWith("/") }
            BundleInfo(pkg, name, ver, apks, obbs)
        }
    } catch (_: Throwable) { null }

    private fun session(c: Context, items: List<Triple<String, Long, () -> java.io.InputStream>>, beforeCommit: () -> Unit) {
        val pi = c.packageManager.packageInstaller
        val id = pi.createSession(PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL))
        val s = pi.openSession(id)
        try {
            for ((n, size, open) in items) {
                s.openWrite(n.substringAfterLast('/'), 0, size).use { o -> open().use { it.copyTo(o, 1 shl 16) }; s.fsync(o) }
            }
            beforeCommit()
            val pend = PendingIntent.getBroadcast(c, id, Intent(c, InstallReceiver::class.java).setPackage(c.packageName),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            s.commit(pend.intentSender)
        } catch (t: Throwable) { s.abandon(); throw t } finally { s.close() }
    }

    fun installApk(c: Context, f: File) = session(c, listOf(Triple(f.name, f.length(), { f.inputStream() as java.io.InputStream })), {})

    fun installBundle(c: Context, f: File, withObb: Boolean, prog: (String) -> Unit) {
        val info = bundleInfo(f) ?: throw IllegalStateException("bad archive")
        ZipFile(f).use { z ->
            val items = info.apks.map { n -> val e = z.getEntry(n); Triple(n, e.size, { z.getInputStream(e) }) }
            session(c, items) {
                if (withObb) info.obbs.forEach { n ->
                    prog("OBB: " + n.substringAfterLast('/'))
                    val e = z.getEntry(n)
                    val dest = File(ROOT, n)
                    val ok = try { dest.parentFile?.mkdirs(); z.getInputStream(e).use { i -> dest.outputStream().use { o -> i.copyTo(o, 1 shl 16) } }; true } catch (_: Throwable) { false }
                    if (!ok && Sh.ready()) {
                        val tmp = File(ROOT, "ToolBox/.tmp/${dest.name}"); tmp.parentFile?.mkdirs()
                        z.getInputStream(e).use { i -> tmp.outputStream().use { o -> i.copyTo(o, 1 shl 16) } }
                        Sh.run("mkdir -p ${Sh.q(dest.parent ?: "")} && cp ${Sh.q(tmp.path)} ${Sh.q(dest.path)}")
                        tmp.delete()
                    }
                }
            }
        }
    }
}

@Composable
fun ApkScreen() {
    val c = LocalContext.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    var apps by remember { mutableStateOf<List<AppRow>>(emptyList()) }
    var q by remember { mutableStateOf("") }
    var sys by remember { mutableStateOf(false) }
    var pick by remember { mutableStateOf<AppRow?>(null) }
    var mode by remember { mutableIntStateOf(1) }
    var obb by remember { mutableLongStateOf(0L) }
    var busy by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<File?>(null) }
    LaunchedEffect(Unit) { apps = withContext(Dispatchers.IO) { ApkTools.apps(c) } }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        if (u != null) scope.launch { val f = withContext(Dispatchers.IO) { copyToCache(c, u) }; nav.go("bundle", f.path) }
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(q, { q = it }, Modifier.weight(1f), singleLine = true, label = { Text(tr("بحث", "Search")) })
            Spacer(Modifier.width(8.dp))
            FilterChip(selected = sys, onClick = { sys = !sys }, label = { Text(tr("النظام", "System")) })
        }
        OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }, Modifier.padding(horizontal = 8.dp).fillMaxWidth()) {
            Text(tr("تثبيت APK / XAPK / APKS من الذاكرة…", "Install APK / XAPK / APKS from storage…"))
        }
        LazyColumn(Modifier.weight(1f)) {
            items(apps.filter { (sys || !it.system) && (q.isBlank() || it.label.contains(q, true) || it.pkg.contains(q, true)) }, key = { it.pkg }) { a ->
                val icon = remember(a.pkg) { try { c.packageManager.getApplicationIcon(a.pkg).toBitmap(96, 96).asImageBitmap() } catch (_: Throwable) { null } }
                Row(Modifier.fillMaxWidth().clickable {
                    pick = a; result = null; busy = ""; mode = if (a.splits > 0) 1 else 0; obb = 0
                    scope.launch { obb = withContext(Dispatchers.IO) { ApkTools.obbSize(a.pkg) } }
                }.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (icon != null) Image(icon, null, Modifier.size(40.dp)) else Spacer(Modifier.size(40.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(a.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${a.ver} • ${a.pkg}", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(fmt(a.size), style = MaterialTheme.typography.bodySmall)
                        if (a.splits > 0) Text("+${a.splits} splits", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
    val a = pick
    if (a != null) AlertDialog(
        onDismissRequest = { if (busy.isEmpty()) pick = null },
        title = { Text(a.label) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                val opts = listOf(
                    tr("APK فقط (الأساسي)", "APK only (base)"),
                    tr("XAPK (كل الأجزاء)", "XAPK (all splits)"),
                    tr("XAPK + OBB (${fmt(obb)})", "XAPK + OBB (${fmt(obb)})"))
                opts.forEachIndexed { i, t ->
                    if (i == 2 && obb == 0L) return@forEachIndexed
                    Row(Modifier.fillMaxWidth().clickable { mode = i }, verticalAlignment = Alignment.CenterVertically) { RadioButton(mode == i, { mode = i }); Text(t) }
                }
                if (a.splits > 0 && mode == 0) Text(tr("تنبيه: التطبيق مقسّم، الـAPK الأساسي وحده قد لا يعمل.", "Note: split app, base APK alone may not run."), style = MaterialTheme.typography.bodySmall)
                if (busy.isNotEmpty()) { Spacer(Modifier.height(8.dp)); LinearProgressIndicator(Modifier.fillMaxWidth()); Text(busy, style = MaterialTheme.typography.bodySmall) }
                result?.let { Text(tr("تم الحفظ:\n", "Saved:\n") + it.path, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            Row {
                result?.let { TextButton(onClick = { shareFiles(c, listOf(it)) }) { Text(tr("مشاركة", "Share")) } }
                TextButton(enabled = busy.isEmpty(), onClick = {
                    scope.launch {
                        busy = "…"; result = null
                        try { result = withContext(Dispatchers.IO) { ApkTools.export(c, a.pkg, mode) { busy = it } }; busy = "" }
                        catch (t: Throwable) { busy = ""; toast("Error: ${t.message}") }
                    }
                }) { Text(tr("استخراج", "Export")) }
            }
        },
        dismissButton = { TextButton(enabled = busy.isEmpty(), onClick = { pick = null }) { Text(tr("إغلاق", "Close")) } }
    )
}

@Composable
fun BundleScreen(path: String) {
    val c = LocalContext.current
    val scope = rememberCoroutineScope()
    val f = File(path)
    val isApk = f.extension.lowercase() == "apk"
    val info = remember(path) { if (isApk) null else ApkTools.bundleInfo(f) }
    var withObb by remember { mutableStateOf(true) }
    var log by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val n = Tick.n
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(f.name, style = MaterialTheme.typography.titleMedium); Text(fmt(f.length()))
        if (isApk) {
            val pi = c.packageManager.getPackageArchiveInfo(path, 0)
            if (pi != null) { Text("${pi.packageName}  v${pi.versionName}") }
        } else if (info == null) Text(tr("ملف غير صالح", "Invalid archive"))
        else {
            Text("${info.name ?: "?"}  ${info.pkg ?: ""}  v${info.ver ?: "?"}")
            Text("APK: ${info.apks.size}   OBB: ${info.obbs.size}")
            if (info.obbs.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(withObb, { withObb = it }); Text(tr("نسخ ملفات OBB", "Copy OBB files")) }
        }
        if (!Perms.install(c)) {
            Text(tr("يلزم السماح بتثبيت التطبيقات من ToolBox.", "Allow ToolBox to install apps first."))
            Button(onClick = { Perms.open(c, Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES) }) { Text(tr("السماح", "Allow")) }
        }
        key(n) {
            Button(enabled = !busy && Perms.install(c) && (isApk || info != null), onClick = {
                scope.launch {
                    busy = true; log = ""
                    try {
                        withContext(Dispatchers.IO) { if (isApk) ApkTools.installApk(c, f) else ApkTools.installBundle(c, f, withObb) { log = it } }
                        log = tr("بانتظار تأكيد النظام…", "Waiting for system confirmation…")
                    } catch (t: Throwable) { log = "Error: ${t.message}" }
                    busy = false
                }
            }) { Text(tr("تثبيت", "Install")) }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (log.isNotEmpty()) Text(log)
    }
}
