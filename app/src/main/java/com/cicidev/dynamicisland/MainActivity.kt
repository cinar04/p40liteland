package com.cicidev.dynamicisland

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var status: TextView
    private val prefs by lazy { getSharedPreferences("ada", MODE_PRIVATE) }
    private val dens by lazy { resources.displayMetrics.density }
    private fun dp(v: Int) = (v * dens).toInt()
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(28), dp(18), dp(28))
        }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#0E0E14"))
            addView(col)
        })

        fun label(t: String, size: Float = 14f) = TextView(this).apply {
            text = t
            textSize = size
            setTextColor(Color.WHITE)
            setPadding(0, dp(10), 0, dp(4))
        }
        fun button(t: String, onTap: () -> Unit) = Button(this).apply {
            text = t
            isAllCaps = false
            setOnClickListener { onTap() }
        }

        col.addView(label("🏝️ Dinamik Ada", 28f))
        col.addView(label("Bildirim, müzik, şarj, arama ve zamanlayıcı ekranın üstünde canlı ada olarak görünür."))
        status = label("")
        col.addView(status)

        col.addView(button("1) Üstte gösterme iznini ver") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        })
        col.addView(button("2) Bildirim erişimini aç (bildirim + müzik)") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        })
        col.addView(button("3) Arama iznini ver") {
            requestPermissions(arrayOf(Manifest.permission.READ_PHONE_STATE), 1)
        })
        col.addView(button("4) Pil kısıtlamasını kaldır (Huawei için şart!)") {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: Exception) {
                toast("Ayarlar > Pil > Uygulama başlatma > Dinamik Ada > Elle yönet (hepsini aç)")
            }
        })

        val sw = Switch(this).apply {
            text = "Adayı çalıştır"
            textSize = 18f
            setTextColor(Color.WHITE)
            setPadding(0, dp(16), 0, dp(8))
            isChecked = IslandService.instance != null
        }
        sw.setOnCheckedChangeListener { _, on ->
            if (on && !Settings.canDrawOverlays(this)) {
                toast("Önce 1. adımdaki izni ver")
                sw.isChecked = false
            } else {
                prefs.edit().putBoolean("enabled", on).apply()
                if (on) startIsland() else stopService(Intent(this, IslandService::class.java))
                status.postDelayed({ refresh() }, 700)
            }
        }
        col.addView(sw)

        col.addView(label("Yatay konum (sol = kamera deliği, orta, sağ)"))
        col.addView(slider("x", 100, 50))
        col.addView(label("Üstten boşluk"))
        col.addView(slider("y", 40, 6))

        col.addView(label("Zamanlayıcı (dakika)"))
        val min = EditText(this).apply {
            setText("1")
            inputType = InputType.TYPE_CLASS_NUMBER
            setTextColor(Color.WHITE)
        }
        col.addView(min)
        col.addView(button("⏱ Zamanlayıcıyı başlat") {
            val m = min.text.toString().toIntOrNull()?.coerceIn(1, 600) ?: 1
            startIsland(IslandService.ACTION_TIMER, m * 60)
        })
        col.addView(button("Zamanlayıcıyı iptal et") {
            if (IslandService.instance != null) startIsland(IslandService.ACTION_CANCEL_TIMER, 0)
        })

        col.addView(button("🔔 Test: örnek bildirim göster") {
            IslandBus.put(
                IslandItem(key = "notif", emoji = "🔔", title = "Merhaba Çiçi!",
                    subtitle = "Dinamik Ada çalışıyor 🏝️", priority = 70, ttlMs = 6000, pop = true)
            )
        })
    }

    private fun slider(key: String, max: Int, def: Int) = SeekBar(this).apply {
        this.max = max
        progress = prefs.getInt(key, def)
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                prefs.edit().putInt(key, p).apply()
                IslandService.instance?.applyLayout()
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
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
            status.postDelayed({ startIsland(); refresh() }, 500)
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        fun ok(b: Boolean) = if (b) "✅" else "❌"
        val listeners = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: ""
        status.text = "Üstte gösterme: ${ok(Settings.canDrawOverlays(this))}\n" +
            "Bildirim erişimi: ${ok(listeners.contains(packageName))}\n" +
            "Arama izni: ${ok(checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED)}\n" +
            "Ada çalışıyor: ${ok(IslandService.instance != null)}"
    }
}
