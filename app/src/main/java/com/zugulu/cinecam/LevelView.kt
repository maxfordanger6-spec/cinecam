package com.zugulu.cinecam

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/** Horizon level indicator driven by the accelerometer. */
class LevelView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    @Volatile var rollDegrees = 0f   // positive = tilted right

    fun setRoll(deg: Float) {
        rollDegrees = deg
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = (minOf(width, height) / 2f) - 8f

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f
        paint.color = Color.argb(140, 255, 255, 255)
        canvas.drawCircle(cx, cy, r, paint)

        // horizon line: counter-rotates with device roll
        val rad = Math.toRadians(rollDegrees.toDouble())
        val dx = (r * Math.cos(rad)).toFloat()
        val dy = (r * Math.sin(rad)).toFloat()
        paint.color = if (Math.abs(rollDegrees) < 1.2f) Color.GREEN else Color.argb(230, 255, 210, 60)
        paint.strokeWidth = 5f
        canvas.drawLine(cx - dx, cy + dy, cx + dx, cy - dy, paint)

        // center bubble
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(200, 255, 255, 255)
        canvas.drawCircle(cx, cy, 6f, paint)
    }
}
