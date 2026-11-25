package com.cicidev.dynamicisland

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/** Tüm ekranların ortak iskeleti ve arayüz parçaları. */
open class BaseActivity : Activity() {

    protected val prefs by lazy { Cfg.p(this) }
    private val dens by lazy { resources.displayMetrics.density }
    protected fun dp(v: Int) = (v * dens).toInt()
    protected fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    protected val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    protected val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    protected val cBg = Color.parseColor("#0B0B10")
    protected val cCard = Color.parseColor("#16161F")
    protected val cChip = Color.parseColor("#262633")
    protected val cAccent = Color.parseColor("#7C6CF0")
    protected val cGreen = Color.parseColor("#34C759")
    protected val cRed = Color.parseColor("#E5484D")
    protected val cSub = Color.parseColor("#9A9AAE")

    protected val refreshers = ArrayList<() -> Unit>()
    protected lateinit var col: LinearLayout

    protected fun hasOverlay() = Settings.canDrawOverlays(this)
    protected fun hasNotifAccess() =
        (Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: "")
            .contains(packageName)
    protected fun hasPhone() =
        checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED

    protected fun openScreen(id: String) {
        startActivity(Intent(this, ScreenActivity::class.java).putExtra("s", id))
    }

    protected fun startIsland(action: String? = null, sec: Int = 0) {
        if (!hasOverlay()) { toast("Önce 'Üstte gösterme' iznini ver"); return }
        val i = Intent(this, IslandService::class.java)
        if (action != null) {
            i.action = action
            i.putExtra(IslandService.EXTRA_SECONDS, sec)
        }
        startForegroundService(i)
    }

    override fun onResume() {
        super.onResume()
        refreshers.forEach { it() }
    }

    // ───────────────────────── iskelet ─────────────────────────

    protected fun buildScreen(title: String, back: Boolean = true): LinearLayout {
        window.statusBarColor = cBg
        window.navigationBarColor = cBg
        val rootL = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(cBg)
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(16), dp(16), dp(10))
        }
        if (back) {
            bar.addView(tv("←", 26f, Color.WHITE, true).apply {
                setPadding(dp(12), 0, dp(16), 0)
                setOnClickListener { finish() }
            })
        } else {
            bar.setPadding(dp(18), dp(16), dp(16), dp(10))
        }
        bar.addView(tv(title, 22f, Color.WHITE, true))
        col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(4), dp(14), dp(40))
        }
        rootL.addView(bar)
        rootL.addView(ScrollView(this).apply {
            isFillViewport = true
            addView(col)
        }, LinearLayout.LayoutParams(MATCH, 0, 1f))
        setContentView(rootL)
        return col
    }

    // ───────────────────────── parçalar ─────────────────────────

    protected fun tv(t: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = t
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        setPadding(0, dp(4), 0, dp(4))
    }

    protected fun btn(t: String, color: Int = cAccent, onTap: () -> Unit) = Button(this).apply {
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

    protected fun card(title: String, parent: LinearLayout = col, content: LinearLayout.() -> Unit) {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = GradientDrawable().apply {
                setColor(cCard)
                cornerRadius = dp(18).toFloat()
            }
        }
        if (title.isNotEmpty()) c.addView(tv(title, 16f, cAccent, true))
        c.content()
        parent.addView(c, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(12) })
    }

    protected fun LinearLayout.hint(t: String) {
        addView(tv(t, 12f, cSub))
    }

    protected fun LinearLayout.button(t: String, color: Int = cAccent, onTap: () -> Unit) {
        addView(btn(t, color, onTap), LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(6) })
    }

    protected fun LinearLayout.menuRow(icon: String, title: String, sub: String, onTap: () -> Unit) {
        val r = LinearLayout(this@BaseActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = GradientDrawable().apply {
                setColor(cCard)
                cornerRadius = dp(16).toFloat()
            }
            setOnClickListener { onTap() }
        }
        r.addView(tv(icon, 22f, Color.WHITE).apply { setPadding(0, 0, dp(14), 0) })
        val t = LinearLayout(this@BaseActivity).apply { orientation = LinearLayout.VERTICAL }
        t.addView(tv(title, 16f, Color.WHITE, true))
        if (sub.isNotEmpty()) t.addView(tv(sub, 12f, cSub))
        r.addView(t, LinearLayout.LayoutParams(0, WRAP, 1f))
        r.addView(tv("›", 24f, cSub))
        addView(r, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) })
    }

    protected fun LinearLayout.optionCard(title: String, desc: String, selected: Boolean, onTap: () -> Unit) {
        val c = LinearLayout(this@BaseActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply {
                setColor(cCard)
                cornerRadius = dp(16).toFloat()
                setStroke(dp(2), if (selected) cAccent else cChip)
            }
            setOnClickListener { onTap() }
        }
        c.addView(tv((if (selected) "● " else "○ ") + title, 16f, Color.WHITE, true))
        c.addView(tv(desc, 12f, cSub))
        addView(c, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) })
    }

    protected fun LinearLayout.switchRow(label: String, key: String, def: Boolean, note: String = "") {
        addView(Switch(this@BaseActivity).apply {
            text = label
            textSize = 15f
            setTextColor(Color.WHITE)
            setPadding(0, dp(8), 0, dp(4))
            isChecked = prefs.getBoolean(key, def)
            setOnCheckedChangeListener { _, on ->
                prefs.edit().putBoolean(key, on).apply()
                IslandService.instance?.refreshStyle()
            }
        })
        if (note.isNotEmpty()) hint(note)
    }

    protected fun LinearLayout.sliderRow(
        label: String, key: String, min: Int, max: Int, def: Int, unit: String = ""
    ) {
        val head = tv("", 14f, Color.WHITE)
        val cur = prefs.getInt(key, def).coerceIn(min, max)
        head.text = "$label: $cur$unit"
        addView(head)
        addView(SeekBar(this@BaseActivity).apply {
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

    protected fun LinearLayout.choiceRow(label: String, key: String, opts: List<String>, def: Int) {
        addView(tv(label, 14f, Color.WHITE))
        val row = LinearLayout(this@BaseActivity).apply { orientation = LinearLayout.HORIZONTAL }
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

    /** Anlık durum satırı (yenilenmez). */
    protected fun LinearLayout.statusRow(label: String, ok: Boolean, fixText: String = "Ayarla", fix: (() -> Unit)? = null) {
        val row = LinearLayout(this@BaseActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(4))
        }
        row.addView(tv(label, 15f, Color.WHITE), LinearLayout.LayoutParams(0, WRAP, 1f))
        row.addView(tv(if (ok) "✅" else "❌", 18f, Color.WHITE))
        if (!ok && fix != null) {
            row.addView(btn(fixText, cChip) { fix() },
                LinearLayout.LayoutParams(WRAP, dp(44)).apply { leftMargin = dp(8) })
        }
        addView(row)
    }

    /** Ekran her açıldığında kendini yenileyen izin satırı. */
    protected fun LinearLayout.permRow(label: String, ok: () -> Boolean, fix: () -> Unit) {
        val row = LinearLayout(this@BaseActivity).apply {
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
}
