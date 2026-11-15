package com.toolbox.app

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.toolbox.app.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private class AppRow(val pkg: String, val label: String)

@Composable
fun VpnScreen() {
    val c = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var pasteDlg by remember { mutableStateOf(false) }
    var appDlg by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    var tick by remember { mutableIntStateOf(0) }
    val st = Vpn.state
    LaunchedEffect(st) { if (st == 2) Vpn.poll() }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) scope.launch { Vpn.connect(c) } else Vpn.msg = tr("لم تُمنح صلاحية VPN", "VPN permission denied")
    }
    fun start() {
        val i = VpnService.prepare(c)
        if (i != null) permLauncher.launch(i) else scope.launch { Vpn.connect(c) }
    }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        if (u != null) scope.launch {
            val t = withContext(Dispatchers.IO) { try { c.contentResolver.openInputStream(u)?.use { it.readBytes().decodeToString() } } catch (_: Throwable) { null } }
            val err = if (t == null) "read error" else Vpn.validate(t)
            if (t != null && err == null) { Vpn.saveConfig(t, "import"); tick++; Vpn.msg = "" } else toast(tr("إعداد غير صالح: ", "Invalid config: ") + (err ?: ""))
        }
    }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // زر التشغيل الكبير
        val has = remember(tick, st) { Vpn.hasConfig() }
        Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val col = when (st) { 2 -> AccTransfer; 1 -> AccNotes; 3 -> MaterialTheme.colorScheme.error; else -> Lavender }
            Box(Modifier.size(132.dp).clip(CircleShape).background(Brush.radialGradient(listOf(col.copy(alpha = 0.55f), col.copy(alpha = 0.12f))))
                .clickable(enabled = st != 1 && !busy) {
                    if (st == 2) scope.launch { Vpn.disconnect(c) }
                    else if (!has) scope.launch { busy = true; try { Vpn.saveConfig(Vpn.registerWarp(), "warp"); tick++; start() } catch (t: Throwable) { Vpn.state = 3; Vpn.msg = t.message ?: "error" }; busy = false }
                    else start()
                }, contentAlignment = Alignment.Center) {
                if (st == 1 || busy) CircularProgressIndicator(Modifier.size(56.dp), color = col)
                else Icon(Icons.Rounded.PowerSettingsNew, null, tint = col, modifier = Modifier.size(64.dp))
            }
            Text(when { busy -> tr("جارٍ إنشاء حساب مجاني…", "Creating a free account…"); st == 2 -> tr("متصل", "Connected"); st == 1 -> tr("جارٍ الاتصال…", "Connecting…"); st == 3 -> tr("تعذّر الاتصال", "Connection failed"); has -> tr("اضغط للتشغيل", "Tap to connect"); else -> tr("اضغط للتشغيل السريع", "Tap for quick start") },
                style = MaterialTheme.typography.titleLarge)
            if (st == 2) {
                Text("↓ ${fmt(Vpn.rx)}   ↑ ${fmt(Vpn.tx)}" + (if (Vpn.endpoint.isNotEmpty()) "   •   ${Vpn.endpoint.substringAfterLast(':')}" else ""), style = MaterialTheme.typography.bodySmall, color = TextMid)
            }
            if (Vpn.msg.isNotEmpty()) Text(Vpn.msg, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tr("الإعداد", "Configuration"), style = MaterialTheme.typography.titleMedium)
                Text(when (Vpn.kind()) { "warp" -> tr("Cloudflare WARP (مجاني، بدون تسجيل)", "Cloudflare WARP (free, no sign-up)"); "import" -> tr("إعداد WireGuard مستورد", "Imported WireGuard config"); else -> tr("لا يوجد إعداد بعد. اضغط زر التشغيل للبدء السريع بـ WARP.", "No config yet. Tap the button for quick start with WARP.") },
                    style = MaterialTheme.typography.bodySmall, color = TextMid)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { import.launch(arrayOf("*/*")) }) { Text(tr("استيراد ملف .conf", "Import .conf")) }
                    OutlinedButton(onClick = { text = ""; pasteDlg = true }) { Text(tr("لصق إعدادات", "Paste config")) }
                    if (has) OutlinedButton(onClick = { scope.launch { Vpn.disconnect(c) }; Vpn.clearConfig(); tick++ }) { Text(tr("حذف الإعداد", "Remove")) }
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tr("ذكي", "Smart"), style = MaterialTheme.typography.titleMedium)
                var lan by remember { mutableStateOf(Vpn.lan) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(tr("تجاوز الشبكة المحلية", "Bypass local network"), style = MaterialTheme.typography.bodyMedium)
                        Text(tr("نقل الملفات والطابعات والأجهزة القريبة تبقى سريعة خارج VPN", "File transfer, printers and nearby devices stay fast outside the VPN"), style = MaterialTheme.typography.bodySmall, color = TextMid)
                    }
                    Switch(lan, { lan = it; Vpn.lan = it })
                }
                Text(tr("التطبيقات عبر VPN", "Apps through VPN"), style = MaterialTheme.typography.labelMedium, color = TextMid)
                var mode by remember { mutableIntStateOf(Vpn.mode) }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(tr("الكل", "All"), tr("المختارة فقط", "Selected only"), tr("الكل ما عدا المختارة", "All except selected")).forEachIndexed { i, s ->
                        FilterChip(selected = mode == i, onClick = { mode = i; Vpn.mode = i }, label = { Text(s) })
                    }
                }
                if (mode != 0) OutlinedButton(onClick = { appDlg = true }) { Text(tr("اختيار التطبيقات", "Choose apps") + " (${Vpn.apps.size})") }
                Text("DNS", style = MaterialTheme.typography.labelMedium, color = TextMid)
                var dns by remember { mutableIntStateOf(Vpn.dns) }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Vpn.DNS_OPTIONS.forEachIndexed { i, o -> FilterChip(selected = dns == i, onClick = { dns = i; Vpn.dns = i }, label = { Text(o.second) }) }
                }
                var auto by remember { mutableStateOf(Vpn.auto) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(tr("تشغيل تلقائي عند فتح التطبيق", "Auto-connect when the app opens"), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Switch(auto, { auto = it; Vpn.auto = it })
                }
                Text(tr("التغييرات تُطبّق عند الاتصال التالي.", "Changes apply on the next connection."), style = MaterialTheme.typography.bodySmall, color = TextMid)
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tr("تشغيل أسرع", "Faster start"), style = MaterialTheme.typography.titleMedium)
                Text(tr("أضف بلاطة «ToolBox VPN» إلى الإعدادات السريعة (اسحب الشريط ← أيقونة القلم) لتشغيل VPN بنقرة من أي مكان. لإجبار كل الاتصال على VPN، فعّل «VPN دائم التشغيل» من إعدادات النظام.",
                    "Add the “ToolBox VPN” tile to Quick Settings (pull down → pencil icon) to start the VPN with one tap from anywhere. To force all traffic through the VPN, enable “Always-on VPN” in system settings."), style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { try { c.startActivity(Intent(Settings.ACTION_VPN_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Throwable) {} }) { Text(tr("إعدادات VPN في النظام", "System VPN settings")) }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(tr("ملاحظات مهمة", "Good to know"), style = MaterialTheme.typography.titleMedium)
                Text(tr("• WARP يشفّر اتصالك ويتجاوز حجب بعض المواقع عند مزوّد الإنترنت، لكنه لا يغيّر بلدك الظاهر، فلا يفتح المحتوى المقيّد جغرافياً.\n• Cloudflare ترى حركة المرور المشفّرة بمعنى أنها وسيط، فاستخدمه بحسب ثقتك بها.\n• لتغيير البلد، استورد ملف WireGuard من مزوّد آخر (مثل الخطة المجانية من Proton VPN).\n• تسجيل WARP يتم عبر واجهة Cloudflare غير الرسمية وقد تتغير أو تُحجب في بعض الدول.",
                    "• WARP encrypts your traffic and bypasses some ISP blocks, but it does not change your apparent country, so geo-restricted content stays locked.\n• Cloudflare is the intermediary and can see your traffic metadata, so use it according to your trust.\n• To change country, import a WireGuard file from another provider (e.g. Proton VPN's free plan).\n• WARP sign-up uses Cloudflare's unofficial API and may change or be blocked in some countries."),
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    if (pasteDlg) AlertDialog(
        onDismissRequest = { pasteDlg = false }, title = { Text(tr("لصق إعداد WireGuard", "Paste WireGuard config")) },
        text = { OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth().heightIn(min = 160.dp)) },
        confirmButton = { TextButton(onClick = {
            val err = Vpn.validate(text)
            if (err == null) { Vpn.saveConfig(text, "import"); tick++; pasteDlg = false } else toast(tr("إعداد غير صالح: ", "Invalid config: ") + err)
        }) { Text(tr("حفظ", "Save")) } },
        dismissButton = { TextButton(onClick = { pasteDlg = false }) { Text(tr("إلغاء", "Cancel")) } })

    if (appDlg) AppPicker { appDlg = false }
}

@Composable
private fun AppPicker(onDone: () -> Unit) {
    val c = LocalContext.current
    var all by remember { mutableStateOf(listOf<AppRow>()) }
    var q by remember { mutableStateOf("") }
    val sel = remember { mutableStateListOf<String>().also { it.addAll(Vpn.apps) } }
    LaunchedEffect(Unit) {
        all = withContext(Dispatchers.IO) {
            val pm = c.packageManager
            val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            pm.queryIntentActivities(i, 0).map { AppRow(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
                .filter { it.pkg != c.packageName }.distinctBy { it.pkg }.sortedBy { it.label.lowercase() }
        }
    }
    AlertDialog(
        onDismissRequest = onDone, title = { Text(tr("اختيار التطبيقات", "Choose apps")) },
        text = {
            Column {
                OutlinedTextField(q, { q = it }, singleLine = true, modifier = Modifier.fillMaxWidth(), placeholder = { Text(tr("بحث", "Search")) })
                LazyColumn(Modifier.heightIn(max = 380.dp)) {
                    items(all.filter { q.isBlank() || it.label.contains(q, true) || it.pkg.contains(q, true) }, key = { it.pkg }) { a ->
                        val icon by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, a.pkg) {
                            value = withContext(Dispatchers.IO) { try { c.packageManager.getApplicationIcon(a.pkg).toBitmap(72, 72).asImageBitmap() } catch (_: Throwable) { null } }
                        }
                        Row(Modifier.fillMaxWidth().clickable { if (a.pkg in sel) sel.remove(a.pkg) else sel.add(a.pkg) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            val ic = icon
                            if (ic != null) Image(ic, null, Modifier.size(36.dp)) else Spacer(Modifier.size(36.dp))
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) { Text(a.label, maxLines = 1); Text(a.pkg, style = MaterialTheme.typography.bodySmall, color = TextMid, maxLines = 1) }
                            Checkbox(a.pkg in sel, { if (it) sel.add(a.pkg) else sel.remove(a.pkg) })
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { Vpn.apps = sel.toSet(); onDone() }) { Text(tr("تم", "Done")) } },
        dismissButton = { TextButton(onClick = onDone) { Text(tr("إلغاء", "Cancel")) } })
}

/** تشغيل تلقائي لـ VPN عند فتح التطبيق (إن فُعّل وكانت الصلاحية ممنوحة). */
@Composable
fun VpnAutoStart() {
    val c = LocalContext.current
    LaunchedEffect(Unit) {
        if (Vpn.auto && Vpn.hasConfig() && Vpn.state == 0 && !Vpn.needsPermission(c)) Vpn.connect(c)
    }
}
