package com.toolbox.app

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** بلاطة في الإعدادات السريعة: تشغيل/إيقاف VPN بنقرة من أي مكان. */
class VpnTile : TileService() {
    override fun onStartListening() { refresh() }
    private fun refresh() {
        val t = qsTile ?: return
        t.state = if (Vpn.state == 2) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        t.label = "ToolBox VPN"
        t.updateTile()
    }
    override fun onClick() {
        val ctx = applicationContext
        if (Vpn.state == 2) {
            CoroutineScope(Dispatchers.IO).launch { Vpn.disconnect(ctx); refresh() }
        } else if (!Vpn.hasConfig() || Vpn.needsPermission(ctx)) {
            val i = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (Build.VERSION.SDK_INT >= 34) startActivityAndCollapse(PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE))
            else @Suppress("DEPRECATION") startActivityAndCollapse(i)
        } else {
            t0 = System.currentTimeMillis()
            CoroutineScope(Dispatchers.IO).launch { Vpn.connect(ctx); refresh() }
        }
    }
    private var t0 = 0L
}
