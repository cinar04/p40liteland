package com.cicidev.dynamicisland

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/** Zamanlayıcı / pil için dairesel ilerleme halkası. */
class RingView(c: Context) : View(c) {
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0x33FFFFFF
    }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val rect = RectF()

    var fraction: Float = 0f
        set(v) { field = v.coerceIn(0f, 1f); invalidate() }
    var color: Int = Color.WHITE
        set(v) { field = v; invalidate() }

    override fun onDraw(canvas: Canvas) {
        val sw = width * 0.11f
        track.strokeWidth = sw
        arc.strokeWidth = sw
        arc.color = color
        val inset = sw / 2f + 1f
        rect.set(inset, inset, width - inset, height - inset)
        canvas.drawArc(rect, 0f, 360f, false, track)
        canvas.drawArc(rect, -90f, 360f * fraction, false, arc)
    }
}
