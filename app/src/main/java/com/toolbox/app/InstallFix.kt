package com.toolbox.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

/** تعارض بالحزمة (توقيع مختلف/إصدار أقدم): نعرض خيار حذف النسخة الحالية ثم إعادة التثبيت تلقائياً. */
object InstallConflict {
    var show by mutableStateOf(false)
    var pkg by mutableStateOf("")
    var hint = ""
    var retry: (() -> Unit)? = null
}

@Composable
fun InstallConflictHost() {
    val c = LocalContext.current
    var waiting by remember { mutableStateOf(false) }
    val lc = (c as? ComponentActivity)?.lifecycle
    DisposableEffect(lc, waiting) {
        val ob = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME && waiting) {
                val gone = try { c.packageManager.getPackageInfo(InstallConflict.pkg, 0); false } catch (_: Throwable) { true }
                if (gone) { waiting = false; InstallConflict.retry?.invoke() }
            }
        }
        lc?.addObserver(ob)
        onDispose { lc?.removeObserver(ob) }
    }
    if (!InstallConflict.show) return
    AlertDialog(
        onDismissRequest = { InstallConflict.show = false },
        title = { Text(tr("تعارض مع نسخة مثبّتة", "Conflict with installed app")) },
        text = {
            Text(tr("التطبيق (${InstallConflict.pkg}) مثبّت بتوقيع أو إصدار مختلف. تحذف النسخة الحالية ثم يُثبَّت الملف الجديد؟ ستُحذف بيانات التطبيق.",
                "(${InstallConflict.pkg}) is installed with a different signature or version. Remove the current one and install the new file? App data will be deleted."))
        },
        confirmButton = {
            TextButton(onClick = {
                InstallConflict.show = false; waiting = true
                c.startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:" + InstallConflict.pkg)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }) { Text(tr("حذف الحالي والتثبيت", "Uninstall & install")) }
        },
        dismissButton = { TextButton(onClick = { InstallConflict.show = false }) { Text(tr("إلغاء", "Cancel")) } }
    )
}
