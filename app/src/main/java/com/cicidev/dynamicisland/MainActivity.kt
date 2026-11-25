package com.cicidev.dynamicisland

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.widget.CompoundButton
import android.widget.Switch
import android.widget.TextView

/** Ana ekran: durum, sağlık kontrolü ve ayar ekranlarına menü. */
class MainActivity : BaseActivity() {

    private lateinit var masterSw: Switch
    private lateinit var statusTv: TextView

    private val masterListener = CompoundButton.OnCheckedChangeListener { b, on ->
        if (on && !hasOverlay()) {
            toast("Önce 'Üstte gösterme' iznini ver")
            b.isChecked = false
        } else {
            prefs.edit().putBoolean("enabled", on).apply()
            if (on) startIsland() else stopService(Intent(this, IslandService::class.java))
            b.postDelayed({ refreshers.forEach { it() } }, 700)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!prefs.getBoolean("setup_done", false)) {
            startActivity(Intent(this, SetupActivity::class.java))
            finish()
            return
        }

        buildScreen("🏝️ Dinamik Ada", back = false)
        col.addView(tv("v3.0 • Huawei P40 Lite için", 13f, cSub))

        card("Durum") {
            statusTv = tv("", 15f, Color.WHITE)
            addView(statusTv)
            masterSw = Switch(this@MainActivity).apply {
                text = "Adayı çalıştır"
                textSize = 19f
                setTextColor(Color.WHITE)
                setPadding(0, dp(10), 0, dp(4))
                isChecked = IslandService.instance != null
                setOnCheckedChangeListener(masterListener)
            }
            addView(masterSw)
        }

        card("Sağlık kontrolü") {
            permRow("Üstte gösterme", { hasOverlay() }) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:$packageName")))
            }
            permRow("Bildirim erişimi", { hasNotifAccess() }) {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
            permRow("Arama izni", { hasPhone() }) {
                requestPermissions(arrayOf(android.Manifest.permission.READ_PHONE_STATE), 1)
            }
            button("🔋 Pil kısıtlamasını kaldır (Huawei'de şart)", cChip) {
                try {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                } catch (_: Exception) {
                    toast("Ayarlar > Pil > Uygulama başlatma > Dinamik Ada > Elle yönet")
                }
            }
        }

        col.addView(tv("AYARLAR", 12f, cAccent, true).apply { setPadding(dp(4), dp(18), 0, dp(2)) })
        col.menuRow("🎨", "Görünüm", "Boyut, renk, saydamlık, köşe, boşta kapsül") { openScreen("look") }
        col.menuRow("📍", "Konum ve davranış", "Yer, titreşim, çift ada") { openScreen("position") }
        col.menuRow("🧩", "Olaylar", "Neler adada gösterilsin") { openScreen("events") }
        col.menuRow("🔔", "Bildirimler", "Sistem mi ada mı, filtre, süre") { openScreen("notif") }
        col.menuRow("🌈", "Uygulama renkleri", "Her uygulamaya ayrı ada rengi") { openScreen("appcolors") }
        col.menuRow("🧰", "Araçlar", "Zamanlayıcı, kronometre, fener") { openScreen("tools") }

        col.addView(tv("DİĞER", 12f, cAccent, true).apply { setPadding(dp(4), dp(18), 0, dp(2)) })
        col.menuRow("🧪", "Test", "Adayı denemek için örnek olaylar") { openScreen("test") }
        col.menuRow("❓", "Yardım ve hakkında", "Jestler, sorun giderme, sıfırlama") { openScreen("help") }
        col.menuRow("🔁", "Kurulum sihirbazı", "Adımları baştan çalıştır") {
            startActivity(Intent(this, SetupActivity::class.java))
        }

        refreshers.add {
            val on = IslandService.instance != null
            statusTv.text = if (on) "🟢 Ada çalışıyor" else "⚪ Ada kapalı"
            masterSw.setOnCheckedChangeListener(null)
            masterSw.isChecked = on
            masterSw.setOnCheckedChangeListener(masterListener)
        }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        if (IslandService.instance != null) {   // arama dinleyicisi yeniden kurulsun
            stopService(Intent(this, IslandService::class.java))
            col.postDelayed({ startIsland(); refreshers.forEach { it() } }, 500)
        } else refreshers.forEach { it() }
    }
}
