package com.zugulu.cinecam

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/** Small luminance histogram drawn from ImageReader data. */
class HistogramView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var bins = IntArray(64)
    private var maxCount = 1

    fun update(newBins: IntArray) {
        bins = newBins
        maxCount = (newBins.maxOrNull() ?: 1).coerceAtLeast(1)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(90, 255, 255, 255)
        val bw = width / 64f
        for (i in 0 until 64) {
            val h = (bins[i].toFloat() / maxCount) * height
            canvas.drawRect(i * bw, height - h, (i + 1) * bw, height.toFloat(), paint)
        }
        // 100% zebra marker line
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        paint.color = Color.argb(160, 255, 60, 60)
        canvas.drawLine(width * 0.97f, 0f, width * 0.97f, height.toFloat(), paint)
    }
}
