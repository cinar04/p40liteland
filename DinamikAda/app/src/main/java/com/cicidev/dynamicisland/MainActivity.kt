package com.cicidev.dynamicisland

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private val prefs by lazy { Cfg.p(this) }
    private val dens by lazy { resources.displayMetrics.density }
    private fun dp(v: Int) = (v * dens).toInt()
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    private val cBg = Color.parseColor("#0E0E14")
    private val cCard = Color.parseColor("#181822")
    private val cChip = Color.parseColor("#262633")
    private val cAccent = Color.parseColor("#6C5CE7")
    private val cRed = Color.parseColor("#C0392B")
    private val cSub = Color.parseColor("#9A9AAE")

    private val refreshers = ArrayList<() -> Unit>()
    private lateinit var col: LinearLayout
    private lateinit var masterSw: Switch

    private val masterListener = CompoundButton.OnCheckedChangeListener { b, on ->
        if (on && !Settings.canDrawOverlays(this)) {
            toast("Önce 'Üstte gösterme' iznini ver")
            b.isChecked = false
        } else {
            prefs.edit().putBoolean("enabled", on).apply()
            if (on) startIsland() else stopService(Intent(this, IslandService::class.java))
            b.postDelayed({ refresh() }, 700)
        }
    }

    // ───────────────────────── küçük yardımcılar ─────────────────────────

    private fun tv(t: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = t
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun btn(t: String, color: Int = cAccent, onTap: () -> Unit) = Button(this).apply {
        text = t
        isAllCaps = false
        setTextColor(Color.WHITE)
        textSize = 14f
        background = GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(14).toFloat()
        }
        setOnClickListener { onTap() }
    }

    private fun card(title: String, content: LinearLayout.() -> Unit) {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = GradientDrawable().apply {
                setColor(cCard)
                cornerRadius = dp(18).toFloat()
            }
        }
        c.addView(tv(title, 17f, cAccent, true))
        c.content()
        col.addView(c, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(12) })
    }

    private fun LinearLayout.button(t: String, color: Int = cAccent, onTap: () -> Unit) {
        addView(btn(t, color, onTap), LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(6) })
    }

    private fun LinearLayout.switchRow(label: String, key: String, def: Boolean) {
        addView(Switch(this@MainActivity).apply {
            text = label
            textSize = 15f
            setTextColor(Color.WHITE)
            setPadding(0, dp(8), 0, dp(8))
            isChecked = prefs.getBoolean(key, def)
            setOnCheckedChangeListener { _, on ->
                prefs.edit().putBoolean(key, on).apply()
                IslandService.instance?.refreshStyle()
            }
        })
    }

    private fun LinearLayout.sliderRow(label: String, key: String, min: Int, max: Int, def: Int, unit: String = "") {
        val head = tv("", 14f, Color.WHITE)
        val cur = prefs.getInt(key, def).coerceIn(min, max)
        head.text = "$label: $cur$unit"
        addView(head)
        addView(SeekBar(this@MainActivity).apply {
            this.max = max - min
            progress = cur - min
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    val v = p + min
                    head.text = "$label: $v$unit"
                    prefs.edit().putInt(key, v).apply()
                    IslandService.instance?.refreshStyle()
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        })
    }

    private fun LinearLayout.choiceRow(label: String, key: String, opts: List<String>, def: Int) {
        addView(tv(label, 14f, Color.WHITE))
        val row = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL }
        val chips = ArrayList<TextView>()
        fun paint() {
            val sel = prefs.getInt(key, def)
            chips.forEachIndexed { i, c ->
                c.background = GradientDrawable().apply {
                    setColor(if (i == sel) cAccent else cChip)
                    cornerRadius = dp(12).toFloat()
                }
            }
        }
        opts.forEachIndexed { i, o ->
            val c = tv(o, 13f, Color.WHITE).apply {
                gravity = Gravity.CENTER
                setPadding(dp(4), dp(10), dp(4), dp(10))
                setOnClickListener {
                    prefs.edit().putInt(key, i).apply()
                    paint()
                    IslandService.instance?.refreshStyle()
                }
            }
            chips.add(c)
            row.addView(c, LinearLayout.LayoutParams(0, WRAP, 1f).apply {
                setMargins(dp(2), dp(4), dp(2), dp(4))
            })
        }
        paint()
        addView(row)
    }

    private fun LinearLayout.permRow(label: String, ok: () -> Boolean, fix: () -> Unit) {
        val row = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(4))
        }
        val st = tv("", 18f, Color.WHITE)
        val b = btn("Ayarla", cChip) { fix() }
        row.addView(tv(label, 15f, Color.WHITE), LinearLayout.LayoutParams(0, WRAP, 1f))
        row.addView(st)
        row.addView(b, LinearLayout.LayoutParams(WRAP, dp(44)).apply { leftMargin = dp(8) })
        refreshers.add {
            val good = ok()
            st.text = if (good) "✅" else "❌"
            b.visibility = if (good) View.GONE else View.VISIBLE
        }
        addView(row)
    }

    // ───────────────────────── ekran ─────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(26), dp(14), dp(40))
        }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(cBg)
            addView(col)
        })

        col.addView(tv("🏝️ Dinamik Ada", 30f, Color.WHITE, true))
        col.addView(tv("v2.1 • Huawei P40 Lite için", 13f, cSub))

        // 1) Durum ve izinler
        card("Durum ve izinler") {
            permRow("Üstte gösterme", { Settings.canDrawOverlays(this@MainActivity) }) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
            permRow("Bildirim erişimi (bildirim + müzik)", {
                (Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: "")
                    .contains(packageName)
            }) { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
            permRow("Arama izni", {
                checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
            }) { requestPermissions(arrayOf(Manifest.permission.READ_PHONE_STATE), 1) }
            button("🔋 Pil kısıtlamasını kaldır (Huawei'de şart)", cChip) {
                try {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                } catch (_: Exception) {
                    toast("Ayarlar > Pil > Uygulama başlatma > Dinamik Ada > Elle yönet (hepsini aç)")
                }
            }
            masterSw = Switch(this@MainActivity).apply {
                text = "Adayı çalıştır"
                textSize = 19f
                setTextColor(Color.WHITE)
                setPadding(0, dp(14), 0, dp(6))
                isChecked = IslandService.instance != null
                setOnCheckedChangeListener(masterListener)
            }
            addView(masterSw)
        }

        // 2) Görünüm
        card("🎨 Görünüm") {
            sliderRow("Boyut", "scale", 80, 170, 120, " %")
            sliderRow("Saydamlık", "opacity", 30, 100, 100, " %")
            sliderRow("Köşe yuvarlaklığı", "radius", 6, 30, 24)
            choiceRow("Renk", "theme", listOf("Siyah", "Lacivert", "Mor", "Yeşil", "Kırmızı"), 0)
            switchRow("Kenarlık çiz (siyah zeminde ada görünsün)", "border", true)
            switchRow("Boşta kapsül göster (ada hep görünür)", "idle", true)
            choiceRow("Boşta ne yazsın?", "idleMode", listOf("Saat", "Pil %", "Saat + Pil"), 0)
        }

        // 3) Konum
        card("📍 Konum") {
            sliderRow("Yatay konum (− sol, + sağ)", "ox", -200, 200, 0, " dp")
            sliderRow("Üstten uzaklık", "oy", 0, 300, 10, " dp")
            button("Konumu sıfırla (ortaya al)", cChip) {
                prefs.edit().putInt("ox", 0).putInt("oy", 10).apply()
                IslandService.instance?.refreshStyle()
                recreate()
            }
            addView(tv("İpucu: adaya uzun basıp sürükleyerek de yerini değiştirebilirsin.", 12f, cSub))
        }

        // 3.5) Bildirim gösterimi
        card("🔔 Bildirim gösterimi") {
            permRow("Rahatsız Etme erişimi (sistem bildirimini gizlemek için)", {
                SysNotif.hasAccess(this@MainActivity)
            }) { startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) }
            addView(Switch(this@MainActivity).apply {
                text = "Bildirimleri üstte (sistem) göster"
                textSize = 15f
                setTextColor(Color.WHITE)
                setPadding(0, dp(8), 0, dp(8))
                isChecked = prefs.getBoolean("sys_notif", false)
                setOnCheckedChangeListener { _, on ->
                    prefs.edit().putBoolean("sys_notif", on).apply()
                    if (!on && !SysNotif.hasAccess(this@MainActivity)) {
                        toast("Bildirimleri sadece adada göstermek için Rahatsız Etme erişimini ver")
                        startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
                    }
                    SysNotif.sync(this@MainActivity)
                    IslandService.instance?.refreshStyle()
                }
            })
            addView(tv(
                "AÇIK: bildirimler sistemin üst panelinde çıkar, adada gösterilmez.\n" +
                    "KAPALI: bildirimler sadece adada görünür, sistemin açılır bildirimi gizlenir. " +
                    "Bunun için Rahatsız Etme erişimi gerekir. Bu modda bildirim sesleri susar, " +
                    "aramalar, alarmlar ve medya normal çalışır. Bildirimler yine bildirim panelinde durur.",
                12f, cSub
            ))
        }

        // 4) Davranış
        card("⚙️ Davranış") {
            sliderRow("Bildirim ekranda kalma süresi", "duration", 3, 15, 6, " sn")
            switchRow("Titreşim geri bildirimi", "haptic", true)
        }

        // 5) Kaynaklar
        card("🧩 Neler gösterilsin?") {
            switchRow("🔔 Bildirimler", "src_notif", true)
            switchRow("🎵 Müzik oynatıcı", "src_music", true)
            switchRow("⚡ Şarj durumu", "src_charge", true)
            switchRow("📞 Arama", "src_call", true)
            switchRow("🎧 Sistem olayları (Bluetooth, sessiz mod, uçak modu, kulaklık)", "src_system", true)
            button("🚫 Bildirimi gösterilmeyecek uygulamalar", cChip) { pickBlockedApps() }
        }

        // 6) Zamanlayıcı ve kronometre
        card("⏱ Zamanlayıcı ve kronometre") {
            val row = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL }
            listOf(1, 5, 10, 15, 30).forEach { m ->
                row.addView(btn("$m dk") { startIsland(IslandService.ACTION_TIMER, m * 60) },
                    LinearLayout.LayoutParams(0, WRAP, 1f).apply { setMargins(dp(2), dp(6), dp(2), dp(6)) })
            }
            addView(row)
            val min = EditText(this@MainActivity).apply {
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
            button("🏁 Kronometre başlat") { startIsland(IslandService.ACTION_SW_START, 0) }
            button("Kronometreyi durdur", cRed) {
                if (IslandService.instance != null) startIsland(IslandService.ACTION_SW_STOP, 0)
            }
        }

        // 7) Test
        card("🧪 Test") {
            button("🔔 Örnek bildirim", cChip) {
                testOnly(IslandItem(key = "notif", emoji = "🔔", title = "Merhaba Çiçi!",
                    subtitle = "Dinamik Ada çalışıyor 🏝️", priority = 70, ttlMs = 6000, pop = true))
            }
            button("⚡ Şarj", cChip) {
                testOnly(IslandItem(key = "charge", emoji = "⚡", title = "Şarj oluyor",
                    subtitle = "Pil %85", priority = 70, ttlMs = 5000, pop = true))
            }
            button("📞 Gelen arama", cChip) {
                testOnly(IslandItem(key = "call", emoji = "📞", title = "Gelen arama",
                    subtitle = "Test araması", priority = 100, ttlMs = 6000, pop = true))
            }
            button("🎧 Sistem olayı", cChip) {
                testOnly(IslandItem(key = "sys", emoji = "🎧", title = "Kulaklık bağlandı",
                    subtitle = "Bluetooth", priority = 60, ttlMs = 4000, pop = true))
            }
            button("⏱ 10 sn zamanlayıcı (halkalı)", cChip) {
                if (IslandService.instance == null) toast("Önce adayı çalıştır")
                else startIsland(IslandService.ACTION_TIMER, 10)
            }
            button("🎵 Müzik", cChip) {
                testOnly(IslandItem(key = "music", emoji = "🎵", title = "Test Şarkısı",
                    subtitle = "Test Sanatçı", priority = 30, ttlMs = 8000, pop = true))
            }
        }

        // 8) Jestler
        card("👆 Jestler") {
            addView(tv(
                "• Dokun: aç / kapat\n" +
                    "• Çift dokun: bildirimi veya uygulamayı aç\n" +
                    "• Aşağı kaydır: genişlet\n" +
                    "• Yukarı kaydır: küçült (küçükken olayı sil)\n" +
                    "• Sağa / sola kaydır: sonraki veya önceki olay\n" +
                    "• Uzun bas + sürükle: adayı istediğin yere taşı\n" +
                    "• Müzikte: ◀◀ ▶❚❚ ▶▶, ekolayzır ve ilerleme çubuğu\n" +
                    "• Zamanlayıcıda: halka, −1 dk / İptal / +1 dk",
                14f, Color.WHITE
            ))
        }

        card("♻️ Sıfırla") {
            button("Tüm ayarları varsayılana döndür", cRed) {
                val en = prefs.getBoolean("enabled", false)
                prefs.edit().clear().putBoolean("enabled", en).apply()
                IslandService.instance?.refreshStyle()
                recreate()
            }
        }
    }

    // ───────────────────────── işlevler ─────────────────────────

    private fun testOnly(item: IslandItem) {
        if (IslandService.instance == null) toast("Önce adayı çalıştır")
        else IslandBus.put(item)
    }

    private fun pickBlockedApps() {
        val pm = packageManager
        val apps = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
        ).map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .distinctBy { it.first }
            .sortedBy { it.second.lowercase() }
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

    private fun startIsland(action: String? = null, sec: Int = 0) {
        if (!Settings.canDrawOverlays(this)) { toast("Önce üstte gösterme iznini ver"); return }
        val i = Intent(this, IslandService::class.java)
        if (action != null) {
            i.action = action
            i.putExtra(IslandService.EXTRA_SECONDS, sec)
        }
        startForegroundService(i)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        if (IslandService.instance != null) {   // arama dinleyicisi yeniden kurulsun
            stopService(Intent(this, IslandService::class.java))
            col.postDelayed({ startIsland(); refresh() }, 500)
        } else refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        refreshers.forEach { it() }
        masterSw.setOnCheckedChangeListener(null)
        masterSw.isChecked = IslandService.instance != null
        masterSw.setOnCheckedChangeListener(masterListener)
    }
}
