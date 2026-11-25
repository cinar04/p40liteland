package com.cicidev.dynamicisland

import android.animation.LayoutTransition
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothDevice
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
import android.media.AudioManager
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
import android.widget.FrameLayout
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
    private lateinit var iconBox: FrameLayout
    private lateinit var ivIcon: ImageView
    private lateinit var ring: RingView
    private lateinit var tvEmoji: TextView
    private lateinit var tvTitle: TextView
    private lateinit var eq: EqView
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
    private var timerTotal = 60
    private var swStart = 0L
    private var phone: TelephonyManager? = null
    private var bgAccent = Int.MIN_VALUE
    private var controlsFor = ""

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
    private val progRun: Runnable = object : Runnable {
        override fun run() {
            val c = current
            if (expanded && c?.media != null) {
                updateProgress()
                ui.postDelayed(this, 1000)
            }
        }
    }

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
        registerReceiver(sysRx, IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(AudioManager.RINGER_MODE_CHANGED_ACTION)
            addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED)
            addAction(Intent.ACTION_HEADSET_PLUG)
        })
        startPhone()

        ready = true
        SysNotif.sync(this)
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
            try { eq.setActive(false) } catch (_: Exception) {}
            try { unregisterReceiver(powerRx) } catch (_: Exception) {}
            try { unregisterReceiver(tickRx) } catch (_: Exception) {}
            try { unregisterReceiver(sysRx) } catch (_: Exception) {}
            try { phone?.listen(phoneL, PhoneStateListener.LISTEN_NONE) } catch (_: Exception) {}
            try { wm.removeView(root) } catch (_: Exception) {}
            SysNotif.restore(this)
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
            layoutTransition = LayoutTransition().apply {
                enableTransitionType(LayoutTransition.CHANGING)
                setDuration(200)
            }
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        iconBox = FrameLayout(this)
        ivIcon = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            visibility = View.GONE
        }
        ring = RingView(this).apply { visibility = View.GONE }
        tvEmoji = TextView(this).apply { gravity = Gravity.CENTER }
        iconBox.addView(ivIcon, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        iconBox.addView(ring, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        iconBox.addView(tvEmoji, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))

        tvTitle = TextView(this).apply {
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        eq = EqView(this).apply { visibility = View.GONE }
        tvCount = TextView(this).apply {
            setTextColor(0xFF9A9AAE.toInt())
            visibility = View.GONE
        }
        row.addView(iconBox)
        row.addView(tvTitle, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(eq)
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

        val mp = ViewGroup.LayoutParams.MATCH_PARENT
        val wp = ViewGroup.LayoutParams.WRAP_CONTENT
        root.addView(row, LinearLayout.LayoutParams(mp, wp))
        root.addView(tvSub, LinearLayout.LayoutParams(mp, wp).apply { topMargin = dp(4) })
        root.addView(progress, LinearLayout.LayoutParams(mp, dp(4)).apply { topMargin = dp(8) })
        root.addView(controls, LinearLayout.LayoutParams(mp, wp).apply { topMargin = dp(8) })

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

    private fun addCtl(label: String, onTap: () -> Unit) {
        val s = sc()
        val t = TextView(this).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 15f * s
            gravity = Gravity.CENTER
            setPadding(dpf(18f * s), dpf(7f * s), dpf(18f * s), dpf(7f * s))
            background = GradientDrawable().apply {
                setColor(0x26FFFFFF)
                cornerRadius = dpf(16f * s).toFloat()
            }
            setOnClickListener { onTap() }
        }
        controls.addView(t, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(dpf(4f * s), 0, dpf(4f * s), 0) })
    }

    /** Olaya göre kontrol düğmeleri: müzik, zamanlayıcı, kronometre. */
    private fun setControls(item: IslandItem) {
        val sig = if (item.media != null) "media" else item.key
        if (sig == controlsFor) return
        controlsFor = sig
        controls.removeAllViews()
        when {
            item.media != null -> {
                addCtl("◀◀") { current?.media?.transportControls?.skipToPrevious() }
                addCtl("▶❚❚") {
                    val mc = current?.media
                    val playing = mc?.playbackState?.state == PlaybackState.STATE_PLAYING
                    if (playing) mc?.transportControls?.pause() else mc?.transportControls?.play()
                }
                addCtl("▶▶") { current?.media?.transportControls?.skipToNext() }
            }
            item.key == "timer" -> {
                addCtl("−1 dk") { adjustTimer(-60) }
                addCtl("İptal") { cancelTimer() }
                addCtl("+1 dk") { adjustTimer(60) }
            }
            item.key == "sw" -> addCtl("Durdur") { stopSw() }
        }
    }

    private fun lighten(c: Int, t: Float): Int {
        val r = (c shr 16 and 0xFF)
        val g = (c shr 8 and 0xFF)
        val b = (c and 0xFF)
        return Color.rgb(
            (r + (255 - r) * t).toInt(),
            (g + (255 - g) * t).toInt(),
            (b + (255 - b) * t).toInt()
        )
    }

    private fun accentFor(item: IslandItem): Int {
        if (item.accent != 0) return item.accent
        return when (item.key) {
            "call", "charge" -> 0xFF34C759.toInt()
            "timer", "timerdone", "sw" -> 0xFFFF9F0A.toInt()
            "music" -> 0xFFFF375F.toInt()
            "notif" -> 0xFF0A84FF.toInt()
            "sys" -> 0xFFBF5AF2.toInt()
            else -> 0
        }
    }

    /** Degrade zemin + olay rengine göre parlayan kenarlık. */
    private fun applyBg(acc: Int) {
        if (acc == bgAccent) return
        bgAccent = acc
        val p = prefs()
        val s = sc()
        val base = themes[p.getInt("theme", 0).coerceIn(0, themes.size - 1)]
        val alpha = p.getInt("opacity", 100).coerceIn(30, 100) * 255 / 100
        val top = (lighten(base, 0.16f) and 0x00FFFFFF) or (alpha shl 24)
        val bottom = (base and 0x00FFFFFF) or (alpha shl 24)
        root.background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(top, bottom)
        ).apply {
            cornerRadius = dpf(p.getInt("radius", 24) * s).toFloat()
            if (p.getBoolean("border", true)) {
                val stroke = if (acc != 0) ((acc and 0x00FFFFFF) or 0xCC000000.toInt()) else 0x66FFFFFF
                setStroke(dp(1), stroke)
            }
        }
    }

    /** Boyut, yazı, ikon ölçülerini uygula. */
    private fun applyStyle() {
        val s = sc()
        bgAccent = Int.MIN_VALUE
        controlsFor = ""
        root.setPadding(dpf(15f * s), dpf(9f * s), dpf(15f * s), dpf(9f * s))
        tvEmoji.textSize = 19f * s
        tvTitle.textSize = 15f * s
        tvCount.textSize = 11f * s
        tvCount.setPadding(dpf(8f * s), 0, 0, 0)
        tvSub.textSize = 13f * s
        iconBox.layoutParams = LinearLayout.LayoutParams(dpf(34f * s), dpf(34f * s)).apply {
            rightMargin = dpf(10f * s)
        }
        ivIcon.background = GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            cornerRadius = dpf(10f * s).toFloat()
        }
        eq.layoutParams = LinearLayout.LayoutParams(dpf(20f * s), dpf(16f * s)).apply {
            leftMargin = dpf(8f * s)
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
        root.scaleX = 0.88f
        root.scaleY = 0.88f
        root.alpha = 0.5f
        root.translationY = -dp(14).toFloat()
        root.animate().scaleX(1f).scaleY(1f).alpha(1f).translationY(0f)
            .setDuration(340).setInterpolator(OvershootInterpolator(1.5f)).start()
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
        eq.setActive(false)
        if (!prefs().getBoolean("idle", true)) {
            root.visibility = View.GONE
            expanded = false
            return
        }
        expanded = false
        applyBg(0)
        iconBox.visibility = View.GONE
        eq.visibility = View.GONE
        tvCount.visibility = View.GONE
        tvSub.visibility = View.GONE
        progress.visibility = View.GONE
        controls.visibility = View.GONE
        val time = android.text.format.DateFormat.format("HH:mm", System.currentTimeMillis()).toString()
        tvTitle.text = when (prefs().getInt("idleMode", 0)) {
            1 -> "%${battery()}"
            2 -> "$time • %${battery()}"
            else -> time
        }
        tvTitle.maxWidth = dpf(170f * sc())
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
        val acc = accentFor(item)
        val tint = if (acc != 0) acc else Color.WHITE
        applyBg(acc)

        val screenW = resources.displayMetrics.widthPixels
        root.minimumWidth = minOf(dpf((if (expanded) 330f else 156f) * s), screenW - dp(16))
        tvTitle.maxWidth = dpf((if (expanded) 250f else 170f) * s)
        tvTitle.gravity = Gravity.CENTER_VERTICAL or
            (if (expanded) Gravity.START else Gravity.CENTER_HORIZONTAL)

        // ikon kutusu: albüm kapağı / uygulama ikonu / emoji (+ ilerleme halkası)
        iconBox.visibility = View.VISIBLE
        val hasRing = item.ring >= 0f
        ring.visibility = if (hasRing) View.VISIBLE else View.GONE
        if (hasRing) {
            ring.color = tint
            ring.fraction = item.ring
        }
        if (item.icon != null) {
            ivIcon.setImageDrawable(item.icon)
            ivIcon.visibility = View.VISIBLE
            tvEmoji.visibility = View.GONE
        } else {
            ivIcon.visibility = View.GONE
            tvEmoji.text = item.emoji
            tvEmoji.textSize = (if (hasRing) 14f else 19f) * s
            tvEmoji.visibility = View.VISIBLE
        }

        val isMusic = item.key == "music"
        eq.color = tint
        eq.visibility = if (isMusic) View.VISIBLE else View.GONE
        eq.setActive(isMusic)

        tvTitle.text = item.title
        tvCount.text = "${index + 1}/${list.size}"
        tvCount.visibility = if (list.size > 1) View.VISIBLE else View.GONE
        tvSub.text = item.subtitle
        tvSub.visibility = if (expanded && item.subtitle.isNotEmpty()) View.VISIBLE else View.GONE

        setControls(item)
        controls.visibility = if (expanded && controls.childCount > 0) View.VISIBLE else View.GONE
        val showMedia = expanded && item.media != null
        progress.visibility = if (showMedia) View.VISIBLE else View.GONE
        if (showMedia) {
            progress.progressTintList = ColorStateList.valueOf(tint)
            updateProgress()
            ui.postDelayed(progRun, 1000)
        }

        root.visibility = View.VISIBLE
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

    // ───────────────────────── saat / şarj / sistem olayları ─────────────────────────

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
            val lvl = battery()
            when (i.action) {
                Intent.ACTION_POWER_CONNECTED -> IslandBus.put(
                    IslandItem(key = "charge", emoji = "⚡", title = "Şarj oluyor",
                        subtitle = "Pil %$lvl", priority = 70, ttlMs = 5000, pop = true,
                        ring = lvl / 100f)
                )
                Intent.ACTION_POWER_DISCONNECTED -> IslandBus.put(
                    IslandItem(key = "charge", emoji = "🔋", title = "Şarj kesildi",
                        subtitle = "Pil %$lvl", priority = 70, ttlMs = 4000, pop = true,
                        ring = lvl / 100f)
                )
                Intent.ACTION_BATTERY_LOW -> IslandBus.put(
                    IslandItem(key = "charge", emoji = "🪫", title = "Pil azalıyor",
                        subtitle = "Pil %$lvl — şarja tak", priority = 80, ttlMs = 6000, pop = true,
                        ring = lvl / 100f, accent = 0xFFFF453A.toInt())
                )
            }
        }
    }

    @Suppress("DEPRECATION")
    private val sysRx = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (isInitialStickyBroadcast) return
            if (!prefs().getBoolean("src_system", true)) return
            when (i.action) {
                BluetoothDevice.ACTION_ACL_CONNECTED, BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    val d = i.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                    val name = try { d?.name } catch (_: Exception) { null } ?: "Bluetooth cihazı"
                    val on = i.action == BluetoothDevice.ACTION_ACL_CONNECTED
                    sysItem("🎧", name, if (on) "Bağlandı" else "Bağlantı kesildi")
                }
                AudioManager.RINGER_MODE_CHANGED_ACTION -> {
                    when (i.getIntExtra(AudioManager.EXTRA_RINGER_MODE, -1)) {
                        AudioManager.RINGER_MODE_SILENT -> sysItem("🔕", "Sessiz mod", "Zil kapalı")
                        AudioManager.RINGER_MODE_VIBRATE -> sysItem("📳", "Titreşim modu", "Zil titreşime alındı")
                        AudioManager.RINGER_MODE_NORMAL -> sysItem("🔔", "Zil açık", "Normal mod")
                    }
                }
                Intent.ACTION_AIRPLANE_MODE_CHANGED -> {
                    val on = i.getBooleanExtra("state", false)
                    sysItem("✈️", if (on) "Uçak modu açık" else "Uçak modu kapalı", "")
                }
                Intent.ACTION_HEADSET_PLUG -> {
                    val on = i.getIntExtra("state", 0) == 1
                    sysItem("🎧", if (on) "Kulaklık takıldı" else "Kulaklık çıkarıldı", "")
                }
            }
        }
    }

    private fun sysItem(emoji: String, title: String, sub: String) {
        IslandBus.put(
            IslandItem(key = "sys", emoji = emoji, title = title, subtitle = sub,
                priority = 60, ttlMs = 4000, pop = true)
        )
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
        timerTotal = seconds.coerceAtLeast(1)
        timerEnd = System.currentTimeMillis() + seconds * 1000L
        ui.removeCallbacks(timerRun)
        tickTimer(true)
    }

    private fun adjustTimer(deltaSec: Int) {
        if (timerEnd == 0L) return
        timerEnd += deltaSec * 1000L
        timerTotal = (timerTotal + deltaSec).coerceAtLeast(1)
        ui.removeCallbacks(timerRun)
        tickTimer(false)
    }

    private fun cancelTimer() {
        ui.removeCallbacks(timerRun)
        timerEnd = 0L
        IslandBus.remove("timer")
    }

    private fun tickTimer(first: Boolean) {
        val left = (timerEnd - System.currentTimeMillis() + 999) / 1000
        if (left <= 0) {
            timerEnd = 0L
            IslandBus.remove("timer")
            IslandBus.put(
                IslandItem(key = "timerdone", emoji = "⏰", title = "Süre doldu!",
                    subtitle = "Zamanlayıcı bitti", priority = 90, ttlMs = 8000, pop = true,
                    ring = 1f)
            )
            vibrateLong()
            return
        }
        IslandBus.put(
            IslandItem(key = "timer", emoji = "⏱", title = "%02d:%02d".format(left / 60, left % 60),
                subtitle = "Zamanlayıcı", priority = 40, pop = first,
                ring = (left.toFloat() / timerTotal).coerceIn(0f, 1f))
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
