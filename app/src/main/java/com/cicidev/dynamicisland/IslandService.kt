package com.cicidev.dynamicisland

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.session.PlaybackState
import android.os.BatteryManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.widget.LinearLayout
import android.widget.TextView

class IslandService : Service() {

    companion object {
        const val ACTION_TIMER = "com.cicidev.dynamicisland.TIMER"
        const val ACTION_CANCEL_TIMER = "com.cicidev.dynamicisland.CANCEL_TIMER"
        const val EXTRA_SECONDS = "seconds"
        @Volatile var instance: IslandService? = null
    }

    private lateinit var wm: WindowManager
    private lateinit var root: LinearLayout
    private lateinit var tvEmoji: TextView
    private lateinit var tvTitle: TextView
    private lateinit var tvSub: TextView
    private lateinit var controls: LinearLayout
    private lateinit var lp: WindowManager.LayoutParams

    private var ready = false
    private var density = 1f
    private val ui = Handler(Looper.getMainLooper())
    private var expanded = false
    private var current: IslandItem? = null
    private val seenPops = HashSet<Long>()
    private var timerEnd = 0L
    private var phone: TelephonyManager? = null

    private val collapseRun = Runnable { expanded = false; render() }
    private val refreshRun = Runnable { render() }
    private val timerRun = Runnable { tickTimer(false) }

    private fun dp(v: Int) = (v * density).toInt()

    // ───────────────────────── yaşam döngüsü ─────────────────────────

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        startAsForeground()
        if (!Settings.canDrawOverlays(this)) { stopSelf(); return }

        density = resources.displayMetrics.density
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        buildView()

        lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        // Kamera deliği (punch-hole) alanının üstüne de çizebilsin
        lp.layoutInDisplayCutoutMode =
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        wm.addView(root, lp)
        applyLayout()

        IslandBus.onChange = { render() }

        val f = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_BATTERY_LOW)
        }
        registerReceiver(powerRx, f)
        startPhone()

        ready = true
        render()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TIMER -> if (ready) startTimer(intent.getIntExtra(EXTRA_SECONDS, 60))
            ACTION_CANCEL_TIMER -> cancelTimer()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        if (ready) {
            ui.removeCallbacksAndMessages(null)
            IslandBus.onChange = null
            try { unregisterReceiver(powerRx) } catch (_: Exception) {}
            @Suppress("DEPRECATION")
            try { phone?.listen(phoneL, PhoneStateListener.LISTEN_NONE) } catch (_: Exception) {}
            try { wm.removeView(root) } catch (_: Exception) {}
        }
        instance = null
        super.onDestroy()
    }

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("ada", "Dinamik Ada", NotificationManager.IMPORTANCE_MIN)
        )
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, "ada")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Dinamik Ada çalışıyor")
            .setContentIntent(pi)
            .build()
        startForeground(1, n)
    }

    // ───────────────────────── görünüm ─────────────────────────

    private fun buildView() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(14), dp(7), dp(14), dp(7))
            background = GradientDrawable().apply {
                setColor(Color.BLACK)
                cornerRadius = dp(24).toFloat()
            }
            visibility = View.GONE
            setOnClickListener {
                if (!expanded) {
                    expanded = true
                    scheduleCollapse()
                    render()
                    bounce()
                } else {
                    try { current?.action?.send() } catch (_: Exception) {}
                    expanded = false
                    ui.removeCallbacks(collapseRun)
                    render()
                }
            }
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        tvEmoji = TextView(this).apply { textSize = 16f }
        tvTitle = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(8), 0, 0, 0)
        }
        row.addView(tvEmoji)
        row.addView(tvTitle)

        tvSub = TextView(this).apply {
            setTextColor(Color.parseColor("#B0B0B8"))
            textSize = 12f
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            maxWidth = dp(260)
            visibility = View.GONE
        }

        controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        controls.addView(ctl("◀◀") { current?.media?.transportControls?.skipToPrevious() })
        controls.addView(ctl("▶❚❚") {
            val mc = current?.media
            if (mc?.playbackState?.state == PlaybackState.STATE_PLAYING)
                mc.transportControls.pause()
            else mc?.transportControls?.play()
        })
        controls.addView(ctl("▶▶") { current?.media?.transportControls?.skipToNext() })

        root.addView(row)
        root.addView(tvSub)
        root.addView(controls)
    }

    private fun ctl(label: String, onTap: () -> Unit) = TextView(this).apply {
        text = label
        setTextColor(Color.WHITE)
        textSize = 15f
        gravity = Gravity.CENTER
        setPadding(dp(16), dp(6), dp(16), dp(2))
        setOnClickListener { onTap() }
    }

    /** Konum ayarlarını (yatay/dikey) uygula. MainActivity kaydırıcıları çağırır. */
    fun applyLayout() {
        if (!ready && !::lp.isInitialized) return
        val p = getSharedPreferences("ada", MODE_PRIVATE)
        val xp = p.getInt("x", 50)   // 0 = sol (kamera), 50 = orta, 100 = sağ
        val yp = p.getInt("y", 6)    // dp cinsinden üstten boşluk
        val m = resources.displayMetrics
        val half = m.widthPixels / 2f - 110 * m.density
        lp.x = ((xp - 50) / 50f * half).toInt()
        lp.y = (yp * m.density).toInt()
        if (root.isAttachedToWindow) wm.updateViewLayout(root, lp)
    }

    private fun bounce() {
        root.scaleX = 0.85f
        root.scaleY = 0.85f
        root.alpha = 0.6f
        root.animate().scaleX(1f).scaleY(1f).alpha(1f)
            .setDuration(300).setInterpolator(OvershootInterpolator(1.6f)).start()
    }

    private fun scheduleCollapse() {
        ui.removeCallbacks(collapseRun)
        ui.postDelayed(collapseRun, 4000)
    }

    private fun render() {
        if (!ready) return
        ui.removeCallbacks(refreshRun)
        val item = IslandBus.top()
        current = item
        if (item == null) {
            root.visibility = View.GONE
            expanded = false
            ui.removeCallbacks(collapseRun)
            return
        }

        val wasHidden = root.visibility != View.VISIBLE
        var popped = false
        if (item.pop && seenPops.add(item.stamp)) {
            if (seenPops.size > 300) seenPops.clear()
            expanded = true
            popped = true
            scheduleCollapse()
        }

        tvEmoji.text = item.emoji
        tvTitle.text = item.title
        tvTitle.maxWidth = dp(if (expanded) 220 else 120)
        tvSub.text = item.subtitle
        tvSub.visibility = if (expanded && item.subtitle.isNotEmpty()) View.VISIBLE else View.GONE
        controls.visibility = if (expanded && item.media != null) View.VISIBLE else View.GONE
        root.visibility = View.VISIBLE
        if (wasHidden || popped) bounce()

        if (item.expireAt > 0) {
            val wait = (item.expireAt - System.currentTimeMillis()).coerceAtLeast(0L) + 30
            ui.postDelayed(refreshRun, wait)
        }
    }

    // ───────────────────────── şarj ─────────────────────────

    private fun battery(): Int {
        val i = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val l = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val s = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        return if (l < 0 || s <= 0) 0 else l * 100 / s
    }

    private val powerRx = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                Intent.ACTION_POWER_CONNECTED -> IslandBus.put(
                    IslandItem(key = "charge", emoji = "⚡", title = "Şarj oluyor",
                        subtitle = "Pil %${battery()}", priority = 70, ttlMs = 5000, pop = true)
                )
                Intent.ACTION_POWER_DISCONNECTED -> IslandBus.put(
                    IslandItem(key = "charge", emoji = "🔋", title = "Şarj kesildi",
                        subtitle = "Pil %${battery()}", priority = 70, ttlMs = 4000, pop = true)
                )
                Intent.ACTION_BATTERY_LOW -> IslandBus.put(
                    IslandItem(key = "charge", emoji = "🪫", title = "Pil azalıyor",
                        subtitle = "Pil %${battery()} — şarja tak", priority = 80, ttlMs = 6000, pop = true)
                )
            }
        }
    }

    // ───────────────────────── arama ─────────────────────────

    @Suppress("DEPRECATION")
    private val phoneL = object : PhoneStateListener() {
        override fun onCallStateChanged(state: Int, phoneNumber: String?) {
            when (state) {
                TelephonyManager.CALL_STATE_RINGING -> IslandBus.put(
                    IslandItem(key = "call", emoji = "📞", title = "Gelen arama",
                        subtitle = if (phoneNumber.isNullOrEmpty()) "Telefon çalıyor" else phoneNumber,
                        priority = 100, pop = true)
                )
                TelephonyManager.CALL_STATE_OFFHOOK -> IslandBus.put(
                    IslandItem(key = "call", emoji = "📞", title = "Görüşmede",
                        subtitle = "Arama sürüyor", priority = 45, pop = true)
                )
                else -> IslandBus.remove("call")
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun startPhone() {
        if (checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE)
            != PackageManager.PERMISSION_GRANTED) return
        try {
            phone = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            phone?.listen(phoneL, PhoneStateListener.LISTEN_CALL_STATE)
        } catch (_: Exception) {}
    }

    // ───────────────────────── zamanlayıcı ─────────────────────────

    private fun startTimer(seconds: Int) {
        timerEnd = System.currentTimeMillis() + seconds * 1000L
        ui.removeCallbacks(timerRun)
        tickTimer(true)
    }

    private fun cancelTimer() {
        ui.removeCallbacks(timerRun)
        IslandBus.remove("timer")
    }

    private fun tickTimer(first: Boolean) {
        val left = (timerEnd - System.currentTimeMillis() + 999) / 1000
        if (left <= 0) {
            IslandBus.remove("timer")
            IslandBus.put(
                IslandItem(key = "timerdone", emoji = "⏰", title = "Süre doldu!",
                    subtitle = "Zamanlayıcı bitti", priority = 90, ttlMs = 8000, pop = true)
            )
            vibrate()
            return
        }
        IslandBus.put(
            IslandItem(key = "timer", emoji = "⏱", title = "%02d:%02d".format(left / 60, left % 60),
                subtitle = "Zamanlayıcı", priority = 40, pop = first)
        )
        ui.postDelayed(timerRun, 1000)
    }

    private fun vibrate() {
        try {
            val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 300, 150, 300), -1))
        } catch (_: Exception) {}
    }
}
