package com.cicidev.dynamicisland

import android.app.NotificationManager
import android.content.Context
import android.content.SharedPreferences

/**
 * "Bildirimleri sadece adada göster" modu.
 * Android, uygulamaların sistemin açılır (heads-up) bildirimini doğrudan gizlemesine izin vermez.
 * Bu yüzden Rahatsız Etme politikası kullanılır: açılır bildirim, durum çubuğu ikonu ve ışık bastırılır,
 * aramalar / alarmlar / medya serbest kalır. Bildirimler yine de bildirim panelinde ve adada görünür.
 */
object SysNotif {

    fun hasAccess(c: Context): Boolean =
        c.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted

    /** Ayara ve ada durumuna göre gizlemeyi aç/kapat. */
    fun sync(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java)
        if (!nm.isNotificationPolicyAccessGranted) return
        val p = Cfg.p(c)
        val wantHide = !p.getBoolean("sys_notif", false) &&
            p.getBoolean("src_notif", true) &&
            IslandService.instance != null
        if (wantHide) hide(nm, p) else restore(nm, p)
    }

    /** Ada kapanırken eski bildirim ayarlarını geri yükle. */
    fun restore(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java)
        if (!nm.isNotificationPolicyAccessGranted) return
        restore(nm, Cfg.p(c))
    }

    private fun hide(nm: NotificationManager, p: SharedPreferences) {
        if (p.getBoolean("dnd_hiding", false)) return
        try {
            val pol = nm.notificationPolicy
            p.edit()
                .putBoolean("dnd_hiding", true)
                .putInt("dnd_filter", nm.currentInterruptionFilter)
                .putInt("dnd_cat", pol.priorityCategories)
                .putInt("dnd_call", pol.priorityCallSenders)
                .putInt("dnd_msg", pol.priorityMessageSenders)
                .putInt("dnd_fx", pol.suppressedVisualEffects)
                .apply()
            val cats = NotificationManager.Policy.PRIORITY_CATEGORY_CALLS or
                NotificationManager.Policy.PRIORITY_CATEGORY_ALARMS or
                NotificationManager.Policy.PRIORITY_CATEGORY_MEDIA or
                NotificationManager.Policy.PRIORITY_CATEGORY_SYSTEM
            val fx = NotificationManager.Policy.SUPPRESSED_EFFECT_PEEK or
                NotificationManager.Policy.SUPPRESSED_EFFECT_STATUS_BAR or
                NotificationManager.Policy.SUPPRESSED_EFFECT_LIGHTS
            nm.notificationPolicy = NotificationManager.Policy(
                cats,
                NotificationManager.Policy.PRIORITY_SENDERS_ANY,
                NotificationManager.Policy.PRIORITY_SENDERS_ANY,
                fx
            )
            nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
        } catch (_: Exception) {}
    }

    private fun restore(nm: NotificationManager, p: SharedPreferences) {
        if (!p.getBoolean("dnd_hiding", false)) return
        try {
            nm.notificationPolicy = NotificationManager.Policy(
                p.getInt("dnd_cat", 0),
                p.getInt("dnd_call", 0),
                p.getInt("dnd_msg", 0),
                p.getInt("dnd_fx", 0)
            )
            val prev = p.getInt("dnd_filter", NotificationManager.INTERRUPTION_FILTER_ALL)
            nm.setInterruptionFilter(
                if (prev in 1..4) prev else NotificationManager.INTERRUPTION_FILTER_ALL
            )
        } catch (_: Exception) {}
        p.edit().putBoolean("dnd_hiding", false).apply()
    }
}
