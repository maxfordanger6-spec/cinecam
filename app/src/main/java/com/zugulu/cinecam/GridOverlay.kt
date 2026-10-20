package com.zugulu.cinecam

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/** Rule-of-thirds grid + center crosshair overlay. */
class GridOverlay @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.5f
        paint.color = Color.argb(120, 255, 255, 255)
        val w = width.toFloat(); val h = height.toFloat()
        for (i in 1..2) {
            canvas.drawLine(w * i / 3f, 0f, w * i / 3f, h, paint)
            canvas.drawLine(0f, h * i / 3f, w, h * i / 3f, paint)
        }
        // center cross
        paint.color = Color.argb(90, 255, 255, 255)
        canvas.drawLine(w / 2f, 0f, w / 2f, h, paint)
        canvas.drawLine(0f, h / 2f, w, h / 2f, paint)
        paint.color = Color.argb(140, 255, 80, 80)
        canvas.drawCircle(w / 2f, h / 2f, 26f, paint)
    }
}
