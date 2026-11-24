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
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadata
import android.media.session.PlaybackState
import android.os.BatteryManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import kotlin.math.abs

class IslandService : Service() {

    companion object {
        const val ACTION_TIMER = "com.cicidev.dynamicisland.TIMER"
        const val ACTION_CANCEL_TIMER = "com.cicidev.dynamicisland.CANCEL_TIMER"
        const val ACTION_SW_START = "com.cicidev.dynamicisland.SW_START"
        const val ACTION_SW_STOP = "com.cicidev.dynamicisland.SW_STOP"
        const val EXTRA_SECONDS = "seconds"
        @Volatile var instance: IslandService? = null
    }

    private val themes = intArrayOf(
        0xFF000000.toInt(), 0xFF0B1B3A.toInt(), 0xFF2A0F4A.toInt(),
        0xFF0C2E1F.toInt(), 0xFF3A0E12.toInt()
    )

    private lateinit var wm: WindowManager
    private lateinit var root: LinearLayout
    private lateinit var ivIcon: ImageView
    private lateinit var tvEmoji: TextView
    private lateinit var tvTitle: TextView
    private lateinit var tvCount: TextView
    private lateinit var tvSub: TextView
    private lateinit var progress: ProgressBar
    private lateinit var controls: LinearLayout
    private lateinit var lp: WindowManager.LayoutParams

    private var ready = false
    private var density = 1f
    private val ui = Handler(Looper.getMainLooper())
    private var expanded = false
    private var index = 0
    private var current: IslandItem? = null
    private val seenPops = HashSet<Long>()
    private var timerEnd = 0L
    private var swStart = 0L
    private var phone: TelephonyManager? = null

    // dokunma / sürükleme durumu
    private var touchSlop = 8
    private var downX = 0f
    private var downY = 0f
    private var startX = 0
    private var startY = 0
    private var dragging = false
    private var lastTap = 0L

    private val collapseRun = Runnable { expanded = false; index = 0; render() }
    private val refreshRun = Runnable { render() }
    private val timerRun = Runnable { tickTimer(false) }
    private val swRun = Runnable { tickSw(false) }
    private val longRun = Runnable {
        dragging = true
        haptic()
        root.animate().scaleX(1.06f).scaleY(1.06f).setDuration(120).start()
    }
    private val progRun = Runnable {
        val c = current
        if (expanded && c?.media != null) {
            updateProgress()
            ui.postDelayed(progRunSelf(), 1000)
        }
    }
    private fun progRunSelf(): Runnable = progRun

    private fun prefs() = Cfg.p(this)
    private fun sc() = prefs().getInt("scale", 120) / 100f
    private fun dpf(v: Float) = (v * density).toInt()
    private fun dp(v: Int) = dpf(v.toFloat())

    // ───────────────────────── yaşam döngüsü ─────────────────────────

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        startAsForeground()
        if (!Settings.canDrawOverlays(this)) { stopSelf(); return }

        density = resources.displayMetrics.density
        touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        buildView()
        applyStyle()

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

        registerReceiver(powerRx, IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_BATTERY_LOW)
        })
        registerReceiver(tickRx, IntentFilter(Intent.ACTION_TIME_TICK))
        startPhone()

        ready = true
        render()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (ready) {
            when (intent?.action) {
                ACTION_TIMER -> startTimer(intent.getIntExtra(EXTRA_SECONDS, 60))
                ACTION_CANCEL_TIMER -> cancelTimer()
                ACTION_SW_START -> startSw()
                ACTION_SW_STOP -> stopSw()
            }
        }
        return START_STICKY
    }

    @Suppress("DEPRECATION")
    override fun onDestroy() {
        if (ready) {
            ui.removeCallbacksAndMessages(null)
            IslandBus.onChange = null
            try { unregisterReceiver(powerRx) } catch (_: Exception) {}
            try { unregisterReceiver(tickRx) } catch (_: Exception) {}
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
            visibility = View.GONE
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        ivIcon = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            visibility = View.GONE
        }
        tvEmoji = TextView(this)
        tvTitle = TextView(this).apply {
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        tvCount = TextView(this).apply {
            setTextColor(0xFF9A9AAE.toInt())
            visibility = View.GONE
        }
        row.addView(ivIcon)
        row.addView(tvEmoji)
        row.addView(tvTitle, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(tvCount)

        tvSub = TextView(this).apply {
            setTextColor(0xFFB8B8C4.toInt())
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            visibility = View.GONE
        }
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progressTintList = ColorStateList.valueOf(Color.WHITE)
            progressBackgroundTintList = ColorStateList.valueOf(0x44FFFFFF)
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

        val mp = ViewGroup.LayoutParams.MATCH_PARENT
        val wp = ViewGroup.LayoutParams.WRAP_CONTENT
        root.addView(row, LinearLayout.LayoutParams(mp, wp))
        root.addView(tvSub, LinearLayout.LayoutParams(mp, wp).apply { topMargin = dp(4) })
        root.addView(progress, LinearLayout.LayoutParams(mp, dp(4)).apply { topMargin = dp(8) })
        root.addView(controls, LinearLayout.LayoutParams(mp, wp).apply { topMargin = dp(4) })

        // Jestler: dokun, çift dokun, uzun bas+sürükle, kaydır
        root.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX
                    downY = ev.rawY
                    startX = lp.x
                    startY = lp.y
                    dragging = false
                    ui.postDelayed(longRun, 450)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - downX
                    val dy = ev.rawY - downY
                    if (dragging) {
                        val m = resources.displayMetrics
                        val maxX = m.widthPixels / 2 - dp(30)
                        lp.x = (startX + dx).toInt().coerceIn(-maxX, maxX)
                        lp.y = (startY + dy).toInt().coerceIn(0, m.heightPixels - dp(80))
                        wm.updateViewLayout(root, lp)
                    } else if (abs(dx) > touchSlop || abs(dy) > touchSlop) {
                        ui.removeCallbacks(longRun)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    ui.removeCallbacks(longRun)
                    if (dragging) {
                        dragging = false
                        root.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                        prefs().edit()
                            .putInt("ox", (lp.x / density).toInt())
                            .putInt("oy", (lp.y / density).toInt())
                            .apply()
                    } else if (ev.actionMasked == MotionEvent.ACTION_UP) {
                        onGesture(ev.rawX - downX, ev.rawY - downY)
                    }
                }
            }
            true
        }
    }

    private fun ctl(label: String, onTap: () -> Unit) = TextView(this).apply {
        text = label
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        setOnClickListener { onTap() }
    }

    /** Boyut, renk, saydamlık, köşe, kenarlık ayarlarını uygula. */
    private fun applyStyle() {
        val p = prefs()
        val s = sc()
        val base = themes[p.getInt("theme", 0).coerceIn(0, themes.size - 1)]
        val alpha = p.getInt("opacity", 100).coerceIn(30, 100) * 255 / 100
        root.background = GradientDrawable().apply {
            setColor((base and 0x00FFFFFF) or (alpha shl 24))
            cornerRadius = dpf(p.getInt("radius", 24) * s).toFloat()
            if (p.getBoolean("border", true)) setStroke(dp(1), 0x77FFFFFF)
        }
        root.setPadding(dpf(16f * s), dpf(9f * s), dpf(16f * s), dpf(9f * s))
        tvEmoji.textSize = 19f * s
        tvEmoji.setPadding(0, 0, dpf(10f * s), 0)
        tvTitle.textSize = 15f * s
        tvCount.textSize = 11f * s
        tvCount.setPadding(dpf(8f * s), 0, 0, 0)
        tvSub.textSize = 13f * s
        ivIcon.layoutParams = LinearLayout.LayoutParams(dpf(32f * s), dpf(32f * s)).apply {
            rightMargin = dpf(10f * s)
        }
        ivIcon.background = GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            cornerRadius = dpf(9f * s).toFloat()
        }
        for (i in 0 until controls.childCount) {
            val t = controls.getChildAt(i) as TextView
            t.textSize = 18f * s
            t.setPadding(dpf(20f * s), dpf(6f * s), dpf(20f * s), dpf(4f * s))
        }
    }

    /** Konum ayarlarını uygula (ox: ortadan yatay kayma dp, oy: üstten dp). */
    fun applyLayout() {
        if (!::lp.isInitialized) return
        val p = prefs()
        lp.x = dp(p.getInt("ox", 0))
        lp.y = dp(p.getInt("oy", 10))
        if (root.isAttachedToWindow) wm.updateViewLayout(root, lp)
    }

    /** MainActivity ayar değiştirince çağırır. */
    fun refreshStyle() {
        if (!ready) return
        applyStyle()
        applyLayout()
        render()
    }

    private fun bounce() {
        root.scaleX = 0.85f
        root.scaleY = 0.85f
        root.alpha = 0.6f
        root.animate().scaleX(1f).scaleY(1f).alpha(1f)
            .setDuration(300).setInterpolator(OvershootInterpolator(1.6f)).start()
    }

    private fun haptic() {
        if (!prefs().getBoolean("haptic", true)) return
        try {
            val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            v.vibrate(VibrationEffect.createOneShot(22, 110))
        } catch (_: Exception) {}
    }

    private fun scheduleCollapse() {
        ui.removeCallbacks(collapseRun)
        ui.postDelayed(collapseRun, 4500)
    }

    // ───────────────────────── jestler ─────────────────────────

    private fun onGesture(dx: Float, dy: Float) {
        val adx = abs(dx)
        val ady = abs(dy)
        if (adx < touchSlop && ady < touchSlop) { onTap(); return }
        val t = dp(10).toFloat()
        if (ady >= adx) {
            if (dy > t) {                       // aşağı kaydır = aç
                if (!expanded) { expanded = true; scheduleCollapse(); render(); bounce() }
            } else if (dy < -t) {               // yukarı kaydır = kapat / olayı sil
                if (expanded) {
                    expanded = false
                    ui.removeCallbacks(collapseRun)
                    render()
                } else {
                    current?.let { IslandBus.remove(it.key) }
                }
            }
        } else {                                // sağa/sola = sonraki/önceki olay
            val t2 = dp(24).toFloat()
            if (dx > t2) cycle(-1) else if (dx < -t2) cycle(1)
        }
    }

    private fun onTap() {
        val now = SystemClock.uptimeMillis()
        if (now - lastTap < 320) {              // çift dokun = uygulamayı / bildirimi aç
            lastTap = 0
            openAction()
            return
        }
        lastTap = now
        if (current == null) return             // boşta: tek dokunuş bir şey yapmaz
        expanded = !expanded
        if (expanded) scheduleCollapse() else ui.removeCallbacks(collapseRun)
        render()
        bounce()
    }

    private fun cycle(dir: Int) {
        val n = IslandBus.all().size
        if (n <= 1) return
        index = (index + dir + n) % n
        expanded = true
        scheduleCollapse()
        haptic()
        render()
        bounce()
    }

    private fun openAction() {
        val a = current?.action
        if (a != null) {
            try { a.send() } catch (_: Exception) { openApp() }
        } else openApp()
    }

    private fun openApp() {
        try {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {}
    }

    // ───────────────────────── çizim ─────────────────────────

    private fun renderIdle() {
        current = null
        if (!prefs().getBoolean("idle", true)) {
            root.visibility = View.GONE
            expanded = false
            return
        }
        expanded = false
        ivIcon.visibility = View.GONE
        tvEmoji.visibility = View.GONE
        tvCount.visibility = View.GONE
        tvSub.visibility = View.GONE
        progress.visibility = View.GONE
        controls.visibility = View.GONE
        tvTitle.text = android.text.format.DateFormat.format("HH:mm", System.currentTimeMillis()).toString()
        tvTitle.maxWidth = dpf(120f * sc())
        tvTitle.gravity = Gravity.CENTER
        root.minimumWidth = dpf(104f * sc())
        root.visibility = View.VISIBLE
    }

    private fun render() {
        if (!ready) return
        ui.removeCallbacks(refreshRun)
        ui.removeCallbacks(progRun)
        val list = IslandBus.all()
        if (list.isEmpty()) {
            index = 0
            renderIdle()
            return
        }

        val wasHidden = root.visibility != View.VISIBLE
        var popped = false
        var fresh: IslandItem? = null
        for (cand in list) {
            if (cand.pop && seenPops.add(cand.stamp)) {
                if (fresh == null || cand.stamp > fresh.stamp) fresh = cand
            }
        }
        if (seenPops.size > 300) seenPops.clear()
        if (fresh != null) {
            index = list.indexOf(fresh).coerceAtLeast(0)
            expanded = true
            popped = true
            scheduleCollapse()
            haptic()
        }
        if (index >= list.size) index = 0
        val item = list[index]
        current = item

        val s = sc()
        val screenW = resources.displayMetrics.widthPixels
        root.minimumWidth = minOf(dpf((if (expanded) 320f else 150f) * s), screenW - dp(16))
        tvTitle.maxWidth = dpf((if (expanded) 250f else 170f) * s)
        tvTitle.gravity = Gravity.CENTER_VERTICAL or
            (if (expanded) Gravity.START else Gravity.CENTER_HORIZONTAL)

        if (item.icon != null) {
            ivIcon.setImageDrawable(item.icon)
            ivIcon.visibility = View.VISIBLE
            tvEmoji.visibility = View.GONE
        } else {
            ivIcon.visibility = View.GONE
            tvEmoji.text = item.emoji
            tvEmoji.visibility = View.VISIBLE
        }
        tvTitle.text = item.title
        tvCount.text = "${index + 1}/${list.size}"
        tvCount.visibility = if (list.size > 1) View.VISIBLE else View.GONE
        tvSub.text = item.subtitle
        tvSub.visibility = if (expanded && item.subtitle.isNotEmpty()) View.VISIBLE else View.GONE
        val showMedia = expanded && item.media != null
        controls.visibility = if (showMedia) View.VISIBLE else View.GONE
        progress.visibility = if (showMedia) View.VISIBLE else View.GONE
        root.visibility = View.VISIBLE
        if (showMedia) {
            updateProgress()
            ui.postDelayed(progRun, 1000)
        }
        if (wasHidden || popped) bounce()

        val next = list.filter { it.expireAt > 0 }.map { it.expireAt }.minOrNull()
        if (next != null) {
            val wait = (next - System.currentTimeMillis()).coerceAtLeast(0L) + 30
            ui.postDelayed(refreshRun, wait)
        }
    }

    private fun updateProgress() {
        val mc = current?.media ?: return
        val st = mc.playbackState ?: return
        val dur = mc.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        if (dur <= 0) { progress.progress = 0; return }
        var pos = st.position
        if (st.state == PlaybackState.STATE_PLAYING) {
            pos += ((SystemClock.elapsedRealtime() - st.lastPositionUpdateTime) * st.playbackSpeed).toLong()
        }
        progress.progress = (pos.coerceIn(0L, dur) * 1000L / dur).toInt()
    }

    // ───────────────────────── saat / şarj ─────────────────────────

    private val tickRx = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { if (current == null) render() }
    }

    private fun battery(): Int {
        val i = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val l = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val s = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        return if (l < 0 || s <= 0) 0 else l * 100 / s
    }

    private val powerRx = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (!prefs().getBoolean("src_charge", true)) return
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
            if (!prefs().getBoolean("src_call", true)) return
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

    // ───────────────────────── zamanlayıcı / kronometre ─────────────────────────

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
            vibrateLong()
            return
        }
        IslandBus.put(
            IslandItem(key = "timer", emoji = "⏱", title = "%02d:%02d".format(left / 60, left % 60),
                subtitle = "Zamanlayıcı", priority = 40, pop = first)
        )
        ui.postDelayed(timerRun, 1000)
    }

    private fun startSw() {
        swStart = SystemClock.elapsedRealtime()
        ui.removeCallbacks(swRun)
        tickSw(true)
    }

    private fun stopSw() {
        ui.removeCallbacks(swRun)
        IslandBus.remove("sw")
    }

    private fun tickSw(first: Boolean) {
        val s = (SystemClock.elapsedRealtime() - swStart) / 1000
        IslandBus.put(
            IslandItem(key = "sw", emoji = "🏁", title = "%02d:%02d".format(s / 60, s % 60),
                subtitle = "Kronometre", priority = 38, pop = first)
        )
        ui.postDelayed(swRun, 1000)
    }

    private fun vibrateLong() {
        try {
            val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 300, 150, 300), -1))
        } catch (_: Exception) {}
    }
}
