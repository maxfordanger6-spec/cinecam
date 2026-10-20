package com.zugulu.cinecam

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/** Simple audio level meter. */
class AudioMeterView(context: Context, attrs: AttributeSet?) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    @Volatile var level = 0f   // 0..1

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(70, 255, 255, 255)
        canvas.drawRoundRect(0f, 0f, w, h, h / 2, h / 2, paint)
        // -20dB mark
        paint.color = Color.argb(120, 255, 255, 255)
        canvas.drawLine(w * 0.79f, 0f, w * 0.79f, h, paint)
        paint.color = if (level > 0.93f) Color.argb(255, 255, 60, 60)
        else Color.argb(220, 80, 200, 90)
        val fillW = (w * level).coerceAtLeast(h / 2)
        canvas.drawRoundRect(0f, 0f, fillW, h, h / 2, h / 2, paint)
    }
}
