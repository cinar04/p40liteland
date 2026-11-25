package com.cicidev.dynamicisland

import android.Manifest
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** İlk açılışta adım adım kurulum sihirbazı. */
class SetupActivity : BaseActivity() {

    private var step = 0
    private val last = 6
    private lateinit var body: LinearLayout
    private lateinit var stepLabel: TextView
    private lateinit var dots: LinearLayout
    private lateinit var btnBack: Button
    private lateinit var btnNext: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildScreen("Kurulum", back = false)

        stepLabel = tv("", 13f, cSub)
        col.addView(stepLabel)
        dots = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        repeat(last + 1) {
            dots.addView(View(this), LinearLayout.LayoutParams(0, dp(4), 1f).apply {
                setMargins(dp(2), 0, dp(2), 0)
            })
        }
        col.addView(dots, LinearLayout.LayoutParams(MATCH, WRAP).apply {
            topMargin = dp(6)
            bottomMargin = dp(10)
        })

        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(body)

        val nav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btnBack = btn("Geri", cChip) { if (step > 0) { step--; render() } }
        btnNext = btn("Devam") { onNext() }
        nav.addView(btnBack, LinearLayout.LayoutParams(0, WRAP, 1f).apply { rightMargin = dp(6) })
        nav.addView(btnNext, LinearLayout.LayoutParams(0, WRAP, 2f))
        col.addView(nav, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(16) })
        render()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        render()
    }

    override fun onBackPressed() {
        if (step > 0) { step--; render() } else super.onBackPressed()
    }

    private fun onNext() {
        when (step) {
            1 -> if (!hasOverlay()) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                return
            }
            2 -> if (!hasNotifAccess()) {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                return
            }
            3 -> if (!hasPhone()) {
                requestPermissions(arrayOf(Manifest.permission.READ_PHONE_STATE), 1)
                return
            }
            6 -> { finishSetup(); return }
        }
        step++
        render()
    }

    private fun finishSetup() {
        prefs.edit().putBoolean("setup_done", true).putBoolean("enabled", true).apply()
        if (hasOverlay()) startIsland()
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun skipButton() {
        body.button("Şimdilik atla", cChip) { step++; render() }
    }

    private fun render() {
        body.removeAllViews()
        stepLabel.text = "Adım ${step + 1} / ${last + 1}"
        for (i in 0..last) {
            dots.getChildAt(i).background = GradientDrawable().apply {
                setColor(if (i <= step) cAccent else cChip)
                cornerRadius = dp(2).toFloat()
            }
        }
        btnBack.visibility = if (step == 0) View.GONE else View.VISIBLE

        when (step) {
            0 -> {
                card("🏝️ Dinamik Ada'ya hoş geldin", body) {
                    addView(tv(
                        "Ekranının üstünde canlı bir ada: bildirimler, müzik, şarj, arama, zamanlayıcı, " +
                            "indirme ilerlemesi, fener, kamera ve mikrofon göstergesi hep tek yerde.",
                        14f, Color.WHITE
                    ))
                    hint("Kurulum 6 kısa adım. Birkaç izin istenecek, her birinin neden gerektiğini anlatacağım.")
                }
                btnNext.text = "Başlayalım"
            }
            1 -> {
                card("Üstte gösterme izni", body) {
                    addView(tv("Ada, diğer uygulamaların üstüne çizilir. Bunun için Android'in 'Üstte gösterme' izni şart.", 14f, Color.WHITE))
                    statusRow("Üstte gösterme", hasOverlay())
                    hint("Açılan ekranda Dinamik Ada'yı bulup anahtarı aç, sonra geri dön.")
                }
                btnNext.text = if (hasOverlay()) "Devam" else "İzni ver"
            }
            2 -> {
                card("Bildirim erişimi", body) {
                    addView(tv("Bildirimleri ve çalan müziği yakalamak için gerekli. Bildirimler sadece telefonunda işlenir, hiçbir yere gönderilmez.", 14f, Color.WHITE))
                    statusRow("Bildirim erişimi", hasNotifAccess())
                    hint("Listeden Dinamik Ada'yı aç, çıkan uyarıda 'İzin ver' de.")
                }
                skipButton()
                btnNext.text = if (hasNotifAccess()) "Devam" else "Erişimi aç"
            }
            3 -> {
                card("Arama izni", body) {
                    addView(tv("Gelen ve süren aramaları adada göstermek için telefon durumu izni gerekir. İstemezsen atlayabilirsin.", 14f, Color.WHITE))
                    statusRow("Arama izni", hasPhone())
                }
                skipButton()
                btnNext.text = if (hasPhone()) "Devam" else "İzni ver"
            }
            4 -> {
                card("Huawei arka plan ayarı", body) {
                    addView(tv("EMUI, pil tasarrufu için uygulamaları kapatır. Adanın hep açık kalması için şunları yap:", 14f, Color.WHITE))
                    button("🔋 Pil kısıtlamasını kaldır") {
                        try {
                            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                        } catch (_: Exception) {
                            toast("Ayarlar > Pil > Uygulama başlatma yolunu kullan")
                        }
                    }
                    addView(tv(
                        "Sonra: Ayarlar > Pil > Uygulama başlatma > Dinamik Ada anahtarını kapat. " +
                            "Açılan pencerede 3 seçeneği de aç: Otomatik başlat, İkincil başlat, Arka planda çalıştır.",
                        13f, cSub
                    ))
                }
            }
            5 -> {
                val island = !prefs.getBoolean("sys_notif", false)
                card("Bildirimler nerede görünsün?", body) {
                    optionCard("Sadece adada",
                        "Bildirimler adada görünür, sistemin üst açılır bildirimi gizlenir. Rahatsız Etme erişimi ister, bildirim sesleri susar.",
                        island) { prefs.edit().putBoolean("sys_notif", false).apply(); render() }
                    optionCard("Sistemde göster",
                        "Bildirimler her zamanki gibi üstte çıkar, adada gösterilmez.",
                        !island) { prefs.edit().putBoolean("sys_notif", true).apply(); render() }
                }
                if (island) {
                    card("Rahatsız Etme erişimi", body) {
                        statusRow("Rahatsız Etme erişimi", SysNotif.hasAccess(this@SetupActivity), "İzni ver") {
                            startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
                        }
                        hint("İzin vermezsen bildirimler hem sistemde hem adada çıkar. Sonradan Ayarlar > Bildirimler'den değiştirebilirsin.")
                    }
                }
            }
            else -> {
                card("Her şey hazır 🚀", body) {
                    statusRow("Üstte gösterme", hasOverlay())
                    statusRow("Bildirim erişimi", hasNotifAccess())
                    statusRow("Arama izni", hasPhone())
                    hint("Düğmeye basınca ada başlar. Görünüm, konum ve olayları ana ekrandan ayarlayabilirsin.")
                }
                btnNext.text = "Adayı başlat 🚀"
            }
        }
        if (step in 4..5) btnNext.text = "Devam"
    }
}
