package com.cicidev.dynamicisland

import android.app.Notification
import android.content.ComponentName
import android.content.Intent
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/** Bildirimleri ve çalan müziği yakalayıp adaya gönderir. */
class NotifListener : NotificationListenerService() {

    private val handler = Handler(Looper.getMainLooper())
    private var msm: MediaSessionManager? = null
    private val controllers = ArrayList<MediaController>()
    private var lastTrack = ""

    private val cn by lazy { ComponentName(this, NotifListener::class.java) }

    private val cb = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) { refreshMusic() }
        override fun onMetadataChanged(metadata: MediaMetadata?) { refreshMusic() }
    }

    private val sessionsL =
        MediaSessionManager.OnActiveSessionsChangedListener { list ->
            setControllers(list ?: emptyList())
        }

    override fun onListenerConnected() {
        super.onListenerConnected()
        try {
            msm = getSystemService(MediaSessionManager::class.java)
            msm?.addOnActiveSessionsChangedListener(sessionsL, cn, handler)
            setControllers(msm?.getActiveSessions(cn) ?: emptyList())
        } catch (_: Exception) {}

        val on = Cfg.p(this).getBoolean("enabled", false)
        if (on && IslandService.instance == null && Settings.canDrawOverlays(this)) {
            try { startForegroundService(Intent(this, IslandService::class.java)) } catch (_: Exception) {}
        }
    }

    override fun onListenerDisconnected() {
        try { msm?.removeOnActiveSessionsChangedListener(sessionsL) } catch (_: Exception) {}
        setControllers(emptyList())
        super.onListenerDisconnected()
    }

    // ───────────────────────── bildirimler ─────────────────────────

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val p = Cfg.p(this)
        if (!p.getBoolean("src_notif", true)) return
        if (sbn.packageName == packageName) return
        val blocked = p.getStringSet("blocked", emptySet()) ?: emptySet()
        if (sbn.packageName in blocked) return

        val n = sbn.notification ?: return
        if (n.flags and Notification.FLAG_ONGOING_EVENT != 0) return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        if (n.category == Notification.CATEGORY_TRANSPORT) return
        if (n.category == Notification.CATEGORY_CALL) return

        val ex = n.extras
        val t = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val x = ex.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        if (t.isEmpty() && x.isEmpty()) return

        val app = try {
            packageManager.getApplicationLabel(
                packageManager.getApplicationInfo(sbn.packageName, 0)
            ).toString()
        } catch (_: Exception) { sbn.packageName }

        val icon: Drawable? =
            (try { n.getLargeIcon()?.loadDrawable(this) } catch (_: Exception) { null })
                ?: (try { packageManager.getApplicationIcon(sbn.packageName) } catch (_: Exception) { null })

        IslandBus.put(
            IslandItem(
                key = "notif",
                emoji = "🔔",
                title = if (t.isEmpty()) app else t,
                subtitle = if (x.isEmpty()) app else "$app • $x",
                priority = 70,
                ttlMs = p.getInt("duration", 6) * 1000L,
                pop = true,
                action = n.contentIntent,
                icon = icon
            )
        )
    }

    // ───────────────────────── müzik ─────────────────────────

    private fun setControllers(list: List<MediaController>) {
        controllers.forEach { try { it.unregisterCallback(cb) } catch (_: Exception) {} }
        controllers.clear()
        controllers.addAll(list)
        controllers.forEach { try { it.registerCallback(cb, handler) } catch (_: Exception) {} }
        refreshMusic()
    }

    private fun refreshMusic() {
        if (!Cfg.p(this).getBoolean("src_music", true)) {
            IslandBus.remove("music")
            return
        }
        val playing = controllers.firstOrNull {
            it.playbackState?.state == PlaybackState.STATE_PLAYING
        }
        if (playing == null) {
            IslandBus.remove("music")
            lastTrack = ""
            return
        }
        val md = playing.metadata
        val title = md?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "Müzik çalıyor"
        val artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST) ?: ""
        val bmp = md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: md?.getBitmap(MediaMetadata.METADATA_KEY_ART)
        val pop = title != lastTrack
        lastTrack = title
        IslandBus.put(
            IslandItem(
                key = "music", emoji = "🎵", title = title, subtitle = artist,
                priority = 30, pop = pop, action = playing.sessionActivity, media = playing,
                icon = bmp?.let { BitmapDrawable(resources, it) }
            )
        )
    }
}
