@file:OptIn(ExperimentalMaterial3Api::class)
package com.toolbox.app

import android.app.ActivityManager
import android.app.usage.StorageStatsManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import android.view.WindowManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import java.io.File

typealias Sect = Pair<String, List<Pair<String, String>>>

fun readFile(p: String) = try { File(p).readText() } catch (_: Throwable) { "" }

@Suppress("DEPRECATION")
fun deviceInfo(c: Context): List<Sect> {
    val out = mutableListOf<Sect>()
    fun sec(t: String, vararg kv: Pair<String, String>) { out.add(t to kv.toList()) }
    val am = c.getSystemService(ActivityManager::class.java)
    val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
    val cpu = readFile("/proc/cpuinfo")
    val hw = cpu.lines().firstOrNull { it.startsWith("Hardware") }?.substringAfter(":")?.trim() ?: Build.HARDWARE
    val swap = readFile("/proc/meminfo").lines().firstOrNull { it.startsWith("SwapTotal") }?.filter { it.isDigit() }?.toLongOrNull()?.times(1024) ?: 0L
    val cores = Runtime.getRuntime().availableProcessors()
    val freqs = (0 until cores).mapNotNull { readFile("/sys/devices/system/cpu/cpu$it/cpufreq/cpuinfo_max_freq").trim().toLongOrNull() }

    sec(tr("الجهاز", "Device"),
        tr("الشركة", "Manufacturer") to Build.MANUFACTURER, tr("الموديل", "Model") to Build.MODEL,
        tr("الاسم الداخلي", "Device") to Build.DEVICE, "Product" to Build.PRODUCT, "Board" to Build.BOARD,
        "Android" to "${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})",
        tr("تحديث الأمان", "Security patch") to Build.VERSION.SECURITY_PATCH,
        "Build" to Build.DISPLAY, "Fingerprint" to Build.FINGERPRINT, "Bootloader" to Build.BOOTLOADER,
        "Kernel" to (System.getProperty("os.version") ?: "?"),
        "ABI" to Build.SUPPORTED_ABIS.joinToString())
    sec(tr("المعالج", "CPU"),
        tr("المعالج", "SoC") to hw, tr("الأنوية", "Cores") to cores.toString(),
        tr("أعلى تردد", "Max freq") to (if (freqs.isEmpty()) "?" else freqs.joinToString { "${it / 1000} MHz" }))
    sec(tr("الذاكرة", "Memory"),
        "RAM" to fmt(mi.totalMem), tr("المتاح", "Available") to fmt(mi.availMem), "Swap" to fmt(swap))

    val data = StatFs(Environment.getDataDirectory().path)
    val ssm = c.getSystemService(StorageStatsManager::class.java)
    val tot = try { ssm.getTotalBytes(StorageManager.UUID_DEFAULT) } catch (_: Throwable) { data.totalBytes }
    val fr = try { ssm.getFreeBytes(StorageManager.UUID_DEFAULT) } catch (_: Throwable) { data.availableBytes }
    val st = mutableListOf(
        tr("السعة الحقيقية", "Real capacity") to fmt(tot), tr("المستخدم", "Used") to fmt(tot - fr),
        tr("الفارغ", "Free") to fmt(fr), tr("قسم البيانات", "Data partition") to fmt(data.totalBytes))
    c.getExternalFilesDirs(null).drop(1).filterNotNull().forEach {
        try { val s = StatFs(it.path); st.add(tr("بطاقة خارجية", "External") to "${fmt(s.totalBytes)} / ${fmt(s.availableBytes)} free") } catch (_: Throwable) {}
    }
    sec(tr("التخزين", "Storage"), *st.toTypedArray())

    val dm = c.resources.displayMetrics
    val rr = try { c.getSystemService(WindowManager::class.java).defaultDisplay.refreshRate } catch (_: Throwable) { 0f }
    sec(tr("الشاشة", "Display"),
        tr("الدقة", "Resolution") to "${dm.widthPixels} x ${dm.heightPixels}", "DPI" to dm.densityDpi.toString(),
        tr("معدل التحديث", "Refresh rate") to "%.0f Hz".format(rr))

    val b = c.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    if (b != null) {
        val lvl = b.getIntExtra("level", 0) * 100 / b.getIntExtra("scale", 100).coerceAtLeast(1)
        sec(tr("البطارية", "Battery"),
            tr("المستوى", "Level") to "$lvl%", tr("الحرارة", "Temperature") to "${b.getIntExtra("temperature", 0) / 10.0} °C",
            tr("الجهد", "Voltage") to "${b.getIntExtra("voltage", 0)} mV", tr("التقنية", "Technology") to (b.getStringExtra("technology") ?: "?"),
            tr("الحالة", "Status") to b.getIntExtra("status", 0).toString(), tr("الصحة", "Health") to b.getIntExtra("health", 0).toString())
    }

    val cm = c.getSystemService(ConnectivityManager::class.java)
    val caps = try { cm.getNetworkCapabilities(cm.activeNetwork) } catch (_: Throwable) { null }
    val type = when {
        caps == null -> "-"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Cellular"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
        else -> "?"
    }
    sec(tr("الشبكة", "Network"),
        tr("النوع", "Type") to type, "IP" to (Transfer.ips().joinToString().ifEmpty { "-" }),
        tr("سرعة التنزيل المقدّرة", "Est. downlink") to (caps?.linkDownstreamBandwidthKbps?.let { "${it / 1000} Mbps" } ?: "?"))

    val sm = c.getSystemService(SensorManager::class.java)
    val sensors = sm.getSensorList(Sensor.TYPE_ALL)
    sec(tr("المستشعرات", "Sensors"), tr("العدد", "Count") to sensors.size.toString(), "" to sensors.joinToString("\n") { it.name })
    sec(tr("التطبيقات", "Apps"), tr("المثبتة", "Installed") to c.packageManager.getInstalledPackages(0).size.toString())
    return out
}

@Composable
fun DeviceScreen() {
    val c = LocalContext.current
    val data = remember(Tick.n) { deviceInfo(c) }
    val cm = LocalClipboardManager.current
    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Button(onClick = {
                cm.setText(AnnotatedString(data.joinToString("\n\n") { (t, kv) -> "[$t]\n" + kv.joinToString("\n") { "${it.first}: ${it.second}" } }))
                toast("OK")
            }) { Icon(Icons.Default.ContentCopy, null); Spacer(Modifier.width(8.dp)); Text(tr("نسخ كل المعلومات", "Copy all")) }
        }
        items(data) { (t, kv) ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(t, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    kv.forEach { (k, v) ->
                        Row(Modifier.padding(vertical = 2.dp)) {
                            Text(k, Modifier.weight(0.4f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(v, Modifier.weight(0.6f), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
}
