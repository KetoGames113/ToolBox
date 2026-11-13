package com.toolbox.app

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager

class AudioService : Service() {
    private val h = Handler(Looper.getMainLooper())
    private var session: MediaSession? = null
    private var ang = 0f
    private var lastPath = ""
    private var artBmp: Bitmap? = null
    private var tint = 0xFF6853C4.toInt()

    private val ticker = object : Runnable {
        override fun run() {
            val pm = getSystemService(PowerManager::class.java)
            if (Au.vp.mp.isPlaying && pm.isInteractive) { ang = (ang + 14f) % 360f; push() }
            h.postDelayed(this, 1000)
        }
    }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        session = MediaSession(this, "ToolBox").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() { if (!Au.vp.mp.isPlaying) Au.vp.toggle(); h.postDelayed({ push() }, 250) }
                override fun onPause() { if (Au.vp.mp.isPlaying) Au.vp.toggle(); h.postDelayed({ push() }, 250) }
                override fun onSkipToNext() { Au.next() }
                override fun onSkipToPrevious() { Au.prev() }
                override fun onSeekTo(pos: Long) { Au.vp.mp.time = pos }
            })
            isActive = true
        }
        h.postDelayed(ticker, 1000)
    }

    override fun onDestroy() { h.removeCallbacksAndMessages(null); session?.release(); super.onDestroy() }

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        when (i?.action) {
            "pp" -> { Au.vp.toggle(); h.postDelayed({ push() }, 250) }
            "next" -> Au.next()
            "prev" -> Au.prev()
            "stop" -> { Au.vp.mp.pause(); stopForeground(true); stopSelf(); return START_NOT_STICKY }
        }
        val n = build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(2, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK) else startForeground(2, n)
        return START_NOT_STICKY
    }

    private fun push() { try { getSystemService(NotificationManager::class.java).notify(2, build()) } catch (_: Throwable) {} }

    private fun act(a: String, ic: Int, t: String) = Notification.Action.Builder(
        Icon.createWithResource(this, ic), t,
        PendingIntent.getService(this, a.hashCode(), Intent(this, AudioService::class.java).setAction(a), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)).build()

    private fun build(): Notification {
        val t = Au.cur
        val playing = Au.vp.mp.isPlaying
        if (t != null && t.path != lastPath) {
            lastPath = t.path
            artBmp = Art.load(t.path, 256)
            tint = darken(artBmp?.let { avgColor(it) } ?: 0xFF6853C4.toInt())
            session?.setMetadata(MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, t.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, t.artist)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, Au.vp.len.toLong()).build())
        }
        session?.setPlaybackState(PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_SEEK_TO)
            .setState(if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED, Au.vp.time.toLong(), if (playing) 1f else 0f).build())
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val b = Notification.Builder(this, "tb").setSmallIcon(R.drawable.ic_stat_vinyl)
            .setLargeIcon(vinylBitmap(artBmp, ang, 256, tint))
            .setContentTitle(t?.title?.ifEmpty { null } ?: "ToolBox").setContentText(t?.artist ?: "")
            .setContentIntent(open).setOngoing(playing).setOnlyAlertOnce(true).setShowWhen(false)
            .setColor(tint).setColorized(true)
            .addAction(act("prev", android.R.drawable.ic_media_previous, "Prev"))
            .addAction(act("pp", if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play, "Play/Pause"))
            .addAction(act("next", android.R.drawable.ic_media_next, "Next"))
            .addAction(act("stop", android.R.drawable.ic_menu_close_clear_cancel, "Stop"))
        val st = Notification.MediaStyle().setShowActionsInCompactView(0, 1, 2)
        session?.let { st.setMediaSession(it.sessionToken) }
        return b.setStyle(st).build()
    }
}
