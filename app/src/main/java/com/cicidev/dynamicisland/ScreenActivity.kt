package com.cicidev.dynamicisland

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/** Tüm ayar alt ekranları tek sınıfta; hangisinin açılacağı "s" bilgisiyle gelir. */
class ScreenActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        when (intent.getStringExtra("s")) {
            "look" -> screenLook()
            "position" -> screenPosition()
            "events" -> screenEvents()
            "notif" -> screenNotif()
            "appcolors" -> screenAppColors()
            "tools" -> screenTools()
            "test" -> screenTest()
            else -> screenHelp()
        }
    }

    // ───────────────────────── Görünüm ─────────────────────────

    private fun screenLook() {
        buildScreen("🎨 Görünüm")
        col.addView(tv("Değişiklikler adaya anında yansır. Ada kapalıysa önce ana ekrandan başlat.", 13f, cSub))
        card("Boyut ve biçim") {
            sliderRow("Boyut", "scale", 80, 170, 120, " %")
            sliderRow("Saydamlık", "opacity", 30, 100, 100, " %")
            sliderRow("Köşe yuvarlaklığı", "radius", 6, 30, 24)
        }
        card("Renk") {
            choiceRow("Zemin rengi", "theme", listOf("Siyah", "Lacivert", "Mor", "Yeşil", "Kırmızı"), 0)
            switchRow("Kenarlık çiz", "border", true, "Siyah zeminde adanın görünmesini sağlar, olaya göre renk değiştirir.")
        }
        card("Boşta kapsül") {
            switchRow("Hiçbir olay yokken de göster", "idle", true, "Kapalıysa ada sadece bir olay olunca belirir.")
            choiceRow("Boşta ne yazsın?", "idleMode", listOf("Saat", "Pil %", "Saat + Pil"), 0)
        }
    }

    // ───────────────────────── Konum ve davranış ─────────────────────────

    private fun screenPosition() {
        buildScreen("📍 Konum ve davranış")
        card("Konum") {
            sliderRow("Yatay (− sol, + sağ)", "ox", -200, 200, 0, " dp")
            sliderRow("Üstten uzaklık", "oy", 0, 300, 10, " dp")
            button("Ortaya al (sıfırla)", cChip) {
                prefs.edit().putInt("ox", 0).putInt("oy", 10).apply()
                IslandService.instance?.refreshStyle()
                recreate()
            }
            hint("İpucu: adaya uzun basıp sürükleyerek de yerini değiştirebilirsin.")
        }
        card("Davranış") {
            switchRow("Titreşim geri bildirimi", "haptic", true)
            switchRow("Çift ada", "dual", true,
                "İki olay aynı anda varsa ikincisi ana adanın yanında küçük bir daire olarak durur. Dokununca yer değiştirirler.")
        }
    }

    // ───────────────────────── Olaylar ─────────────────────────

    private fun screenEvents() {
        buildScreen("🧩 Olaylar")
        card("Adada neler gösterilsin?") {
            switchRow("🔔 Bildirimler", "src_notif", true)
            switchRow("⬇️ İndirme / ilerleme çubuğu", "src_progress", true,
                "İlerleme çubuklu bildirimleri (indirme, kopyalama) canlı halka olarak gösterir.")
            switchRow("🎵 Müzik oynatıcı", "src_music", true)
            switchRow("⚡ Şarj durumu", "src_charge", true)
            switchRow("📞 Arama", "src_call", true)
            switchRow("🎧 Sistem olayları", "src_system", true, "Bluetooth, kulaklık, sessiz mod, uçak modu.")
            switchRow("🔦 Fener göstergesi", "src_torch", true)
            switchRow("📷🎙️ Kamera ve mikrofon göstergesi", "src_privacy", true,
                "Deneysel: kamera ve mikrofon kullanılırken ada uyarır. Bazı telefonlarda yüz tanıma kısa süre kamera göstergesini tetikleyebilir.")
        }
    }

    // ───────────────────────── Bildirimler ─────────────────────────

    private fun screenNotif() {
        buildScreen("🔔 Bildirimler")
        card("Bildirimler nerede görünsün?") {
            addView(Switch0())
            hint("AÇIK: bildirimler sistemin üst panelinde çıkar, adada gösterilmez.\nKAPALI: bildirimler sadece adada görünür, sistemin açılır bildirimi gizlenir. Bildirim sesleri susar, aramalar ve alarmlar normal çalışır.")
            permRow("Rahatsız Etme erişimi", { SysNotif.hasAccess(this@ScreenActivity) }) {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
            }
        }
        card("Süre ve filtre") {
            sliderRow("Bildirim adada kalma süresi", "duration", 3, 15, 6, " sn")
            button("🚫 Bildirimi gösterilmeyecek uygulamalar", cChip) { pickBlockedApps() }
        }
    }

    private fun Switch0(): android.widget.Switch = android.widget.Switch(this).apply {
        text = "Bildirimleri üstte (sistem) göster"
        textSize = 15f
        setTextColor(Color.WHITE)
        setPadding(0, dp(8), 0, dp(8))
        isChecked = prefs.getBoolean("sys_notif", false)
        setOnCheckedChangeListener { _, on ->
            prefs.edit().putBoolean("sys_notif", on).apply()
            if (!on && !SysNotif.hasAccess(this@ScreenActivity)) {
                toast("Bildirimleri sadece adada göstermek için Rahatsız Etme erişimini ver")
                startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
            }
            SysNotif.sync(this@ScreenActivity)
            IslandService.instance?.refreshStyle()
        }
    }

    private fun launcherApps(): List<Pair<String, String>> {
        val pm = packageManager
        return pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
        ).map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .distinctBy { it.first }
            .sortedBy { it.second.lowercase() }
    }

    private fun pickBlockedApps() {
        val apps = launcherApps()
        val blocked = (prefs.getStringSet("blocked", emptySet()) ?: emptySet()).toMutableSet()
        val names = apps.map { it.second }.toTypedArray()
        val checked = BooleanArray(apps.size) { apps[it].first in blocked }
        AlertDialog.Builder(this)
            .setTitle("Gösterilmeyecek uygulamalar")
            .setMultiChoiceItems(names, checked) { _, i, on ->
                if (on) blocked.add(apps[i].first) else blocked.remove(apps[i].first)
            }
            .setPositiveButton("Kaydet") { _, _ ->
                prefs.edit().putStringSet("blocked", HashSet(blocked)).apply()
            }
            .setNegativeButton("İptal", null)
            .show()
    }

    // ───────────────────────── Uygulama renkleri ─────────────────────────

    private fun screenAppColors() {
        buildScreen("🌈 Uygulama renkleri")
        col.addView(tv("Bir uygulamaya dokun, o uygulamanın bildirimlerinde adanın rengini seç. WhatsApp, Instagram gibi bilinenler hazır renkli gelir.", 13f, cSub))
        for ((pkg, name) in launcherApps()) {
            val dot = tv("●", 22f, Color.WHITE).apply { setPadding(0, 0, dp(14), 0) }
            fun paint() {
                val c = AppColors.forPkg(this, pkg)
                dot.setTextColor(if (c != 0) c else cSub)
            }
            paint()
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
                background = GradientDrawable().apply {
                    setColor(cCard)
                    cornerRadius = dp(14).toFloat()
                }
                setOnClickListener {
                    AlertDialog.Builder(this@ScreenActivity)
                        .setTitle(name)
                        .setItems(AppColors.names.toTypedArray()) { _, i ->
                            if (i == 0) prefs.edit().remove("ac_$pkg").apply()
                            else prefs.edit().putInt("ac_$pkg", AppColors.values[i]).apply()
                            paint()
                        }
                        .show()
                }
            }
            row.addView(dot)
            row.addView(tv(name, 15f, Color.WHITE), LinearLayout.LayoutParams(0, WRAP, 1f))
            col.addView(row, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(6) })
        }
    }

    // ───────────────────────── Araçlar ─────────────────────────

    private fun screenTools() {
        buildScreen("🧰 Araçlar")
        card("⏱ Zamanlayıcı") {
            val row = LinearLayout(this@ScreenActivity).apply { orientation = LinearLayout.HORIZONTAL }
            listOf(1, 5, 10, 15, 30).forEach { m ->
                row.addView(btn("$m dk") { startIsland(IslandService.ACTION_TIMER, m * 60) },
                    LinearLayout.LayoutParams(0, WRAP, 1f).apply { setMargins(dp(2), dp(6), dp(2), dp(6)) })
            }
            addView(row)
            val min = EditText(this@ScreenActivity).apply {
                hint = "Özel süre (dakika)"
                inputType = InputType.TYPE_CLASS_NUMBER
                setTextColor(Color.WHITE)
                setHintTextColor(cSub)
            }
            addView(min)
            button("Özel süreyi başlat") {
                val m = min.text.toString().toIntOrNull()?.coerceIn(1, 600)
                if (m == null) toast("Dakika yaz") else startIsland(IslandService.ACTION_TIMER, m * 60)
            }
            button("Zamanlayıcıyı iptal et", cRed) {
                if (IslandService.instance != null) startIsland(IslandService.ACTION_CANCEL_TIMER, 0)
            }
            hint("Zamanlayıcı çalışırken adayı açıp −1 dk / İptal / +1 dk düğmelerini kullanabilirsin.")
        }
        card("🏁 Kronometre") {
            button("Başlat") { startIsland(IslandService.ACTION_SW_START, 0) }
            button("Durdur", cRed) {
                if (IslandService.instance != null) startIsland(IslandService.ACTION_SW_STOP, 0)
            }
        }
        card("🔦 Fener") {
            button("Fener aç") { if (!Torch.set(this@ScreenActivity, true)) toast("Fener açılamadı") }
            button("Fener kapat", cChip) { Torch.set(this@ScreenActivity, false) }
            hint("Fener açıkken adada 'Fener açık' göstergesi ve Kapat düğmesi çıkar.")
        }
    }

    // ───────────────────────── Test ─────────────────────────

    private fun testOnly(item: IslandItem) {
        if (IslandService.instance == null) toast("Önce adayı çalıştır")
        else IslandBus.put(item)
    }

    private fun screenTest() {
        buildScreen("🧪 Test")
        col.addView(tv("Örnek olaylarla adayı dene. Aynı anda birkaçına basarsan çift adayı da görürsün.", 13f, cSub))
        card("") {
            button("🔔 Bildirim", cChip) {
                testOnly(IslandItem(key = "notif", emoji = "🔔", title = "Merhaba Çiçi!",
                    subtitle = "Dinamik Ada çalışıyor 🏝️", priority = 70, ttlMs = 6000, pop = true,
                    accent = 0xFF0A84FF.toInt()))
            }
            button("⬇️ İndirme çubuğu", cChip) {
                testOnly(IslandItem(key = "dl_test", emoji = "⬇️", title = "dosya.zip",
                    subtitle = "%60 • İndiriliyor", priority = 35, ttlMs = 12000, pop = true,
                    ring = 0.6f, accent = 0xFF5AC8FA.toInt()))
            }
            button("⚡ Şarj", cChip) {
                testOnly(IslandItem(key = "charge", emoji = "⚡", title = "Şarj oluyor",
                    subtitle = "Pil %85", priority = 70, ttlMs = 5000, pop = true, ring = 0.85f))
            }
            button("📞 Gelen arama", cChip) {
                testOnly(IslandItem(key = "call", emoji = "📞", title = "Gelen arama",
                    subtitle = "Test araması", priority = 100, ttlMs = 6000, pop = true))
            }
            button("🎵 Müzik", cChip) {
                testOnly(IslandItem(key = "music", emoji = "🎵", title = "Test Şarkısı",
                    subtitle = "Test Sanatçı", priority = 30, ttlMs = 8000, pop = true))
            }
            button("🎧 Sistem olayı", cChip) {
                testOnly(IslandItem(key = "sys", emoji = "🎧", title = "Kulaklık bağlandı",
                    subtitle = "Bluetooth", priority = 60, ttlMs = 4000, pop = true))
            }
            button("📷 Kamera göstergesi", cChip) {
                testOnly(IslandItem(key = "cam", emoji = "📷", title = "Kamera kullanılıyor",
                    priority = 25, ttlMs = 6000, accent = 0xFF34C759.toInt()))
            }
            button("⏱ 10 sn zamanlayıcı (halkalı)", cChip) {
                if (IslandService.instance == null) toast("Önce adayı çalıştır")
                else startIsland(IslandService.ACTION_TIMER, 10)
            }
        }
    }

    // ───────────────────────── Yardım ─────────────────────────

    private fun screenHelp() {
        buildScreen("❓ Yardım ve hakkında")
        card("👆 Jestler") {
            addView(tv(
                "• Dokun: aç / kapat\n" +
                    "• Çift dokun: bildirimi veya uygulamayı aç\n" +
                    "• Aşağı kaydır: genişlet\n" +
                    "• Yukarı kaydır: küçült (küçükken olayı sil)\n" +
                    "• Sağa / sola kaydır: sonraki veya önceki olay\n" +
                    "• Uzun bas + sürükle: adayı taşı\n" +
                    "• Yandaki küçük daireye dokun: olayları değiştir\n" +
                    "• Müzikte ◀◀ ▶❚❚ ▶▶, zamanlayıcıda −1 dk / İptal / +1 dk, fenerde Kapat",
                14f, Color.WHITE
            ))
        }
        card("🛠 Sorun giderme") {
            addView(tv(
                "• Ada görünmüyor: Ana ekranda 'Adayı çalıştır' açık mı, üç izin ✅ mi?\n" +
                    "• Bir süre sonra kayboluyor: Pil kısıtlamasını kaldır ve Uygulama başlatma'da 3 seçeneği aç.\n" +
                    "• Bildirim gelmiyor: Bildirim erişimini kapatıp tekrar aç.\n" +
                    "• Bildirim sesleri sustu: Bildirimler ekranında 'üstte göster'i aç, ses geri gelir.",
                14f, Color.WHITE
            ))
        }
        card("ℹ️ Hakkında") {
            addView(tv("Dinamik Ada v3.0\nHuawei P40 Lite (EMUI 10) için, Google servisi gerektirmez.", 14f, Color.WHITE))
            button("Kurulum sihirbazını aç", cChip) { startActivity(Intent(this@ScreenActivity, SetupActivity::class.java)) }
            button("Tüm ayarları sıfırla", cRed) {
                AlertDialog.Builder(this@ScreenActivity)
                    .setTitle("Emin misin?")
                    .setMessage("Tüm ayarlar varsayılana döner.")
                    .setPositiveButton("Sıfırla") { _, _ ->
                        val en = prefs.getBoolean("enabled", false)
                        prefs.edit().clear().putBoolean("enabled", en).putBoolean("setup_done", true).apply()
                        SysNotif.restore(this@ScreenActivity)
                        IslandService.instance?.refreshStyle()
                        toast("Sıfırlandı")
                    }
                    .setNegativeButton("Vazgeç", null)
                    .show()
            }
        }
    }
}
