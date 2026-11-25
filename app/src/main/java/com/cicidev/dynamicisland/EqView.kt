package com.cicidev.dynamicisland

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.abs
import kotlin.math.sin

/** Müzik çalarken oynayan küçük ekolayzır çubukları. */
class EqView(c: Context) : View(c) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var phase = 0f
    private var anim: ValueAnimator? = null
    var color: Int = Color.WHITE
        set(v) { field = v; invalidate() }

    fun setActive(on: Boolean) {
        if (on) {
            if (anim == null) {
                anim = ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = 900
                    repeatCount = ValueAnimator.INFINITE
                    interpolator = LinearInterpolator()
                    addUpdateListener { phase = it.animatedValue as Float; invalidate() }
                    start()
                }
            }
        } else {
            anim?.cancel()
            anim = null
        }
    }

    override fun onDetachedFromWindow() {
        anim?.cancel()
        anim = null
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        paint.color = color
        val n = 4
        val gap = width * 0.10f
        val bw = (width - gap * (n - 1)) / n
        for (i in 0 until n) {
            val f = 0.30f + 0.70f * abs(sin(2.0 * Math.PI * (phase + i * 0.23)).toFloat())
            val h = height * f
            val l = i * (bw + gap)
            rect.set(l, height - h, l + bw, height.toFloat())
            canvas.drawRoundRect(rect, bw / 2f, bw / 2f, paint)
        }
    }
}
