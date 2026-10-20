package com.zugulu.cinecam

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot

/**
 * Dual reticle overlay (FiLMiC-style): yellow = exposure, red = focus.
 * - Drag a reticle to move it (nearest one within grab radius wins).
 * - Double-tap splits them; single tap on empty space re-centers both (linked).
 * Reports normalized positions via [onChanged].
 */
class ReticleView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    var aePos = PointF(0.5f, 0.5f)
        private set
    var afPos = PointF(0.5f, 0.5f)
        private set
    var split = false
        private set

    /** (aeX, aeY, afX, afY, split) — normalized [0,1] coords. */
    var onChanged: ((Float, Float, Float, Float, Boolean) -> Unit)? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var dragTarget = 0   // 0 none, 1 AE, 2 AF
    private val grabRadius = 60f // px

    private val tap = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            split = !split
            if (!split) afPos.set(aePos.x, aePos.y)
            fire()
            return true
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            // tap empty space: link both to the tapped point
            val n = normalize(e.x, e.y)
            aePos.set(n.x, n.y)
            afPos.set(n.x, n.y)
            split = false
            fire()
            return true
        }
    })

    private fun normalize(x: Float, y: Float): PointF =
        PointF((x / width).coerceIn(0f, 1f), (y / height).coerceIn(0f, 1f))

    private fun fire() {
        onChanged?.invoke(aePos.x, aePos.y, afPos.x, afPos.y, split)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (tap.onTouchEvent(event)) return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val dAe = hypot(event.x - aePos.x * width, event.y - aePos.y * height)
                val dAf = hypot(event.x - afPos.x * width, event.y - afPos.y * height)
                dragTarget = if (split) {
                    when {
                        dAe <= grabRadius -> 1
                        dAf <= grabRadius -> 2
                        else -> 0
                    }
                } else {
                    if (dAe <= grabRadius * 1.4f) 1 else 0
                }
                if (dragTarget != 0) {
                    val n = normalize(event.x, event.y)
                    if (dragTarget == 1) aePos.set(n.x, n.y) else afPos.set(n.x, n.y)
                    fire()
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragTarget == 0) return true
                val n = normalize(event.x, event.y)
                if (dragTarget == 1) aePos.set(n.x, n.y) else afPos.set(n.x, n.y)
                fire()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragTarget != 0) {
                    dragTarget = 0
                    fire()
                }
                return true
            }
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val r = 42f

        // AE — yellow square with bracket corners
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f
        paint.color = Color.rgb(255, 200, 77)
        drawBracket(canvas, aePos.x * w, aePos.y * h, r)
        if (!split) {
            // linked: draw AF marker on top of AE
            paint.color = Color.rgb(255, 80, 80)
            paint.style = Paint.Style.FILL
            canvas.drawCircle(aePos.x * w, aePos.y * h, 10f, paint)
        } else {
            // AF — red bracket
            paint.style = Paint.Style.STROKE
            paint.color = Color.rgb(255, 80, 80)
            drawBracket(canvas, afPos.x * w, afPos.y * h, r)
            // link line
            paint.color = Color.argb(120, 255, 255, 255)
            paint.strokeWidth = 1.5f
            canvas.drawLine(aePos.x * w, aePos.y * h, afPos.x * w, afPos.y * h, paint)
        }
    }

    private fun drawBracket(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val l = r * 0.45f
        // corners: TL, TR, BL, BR
        canvas.drawLine(cx - r, cy - r + l, cx - r, cy - r, paint)
        canvas.drawLine(cx - r, cy - r, cx - r + l, cy - r, paint)
        canvas.drawLine(cx + r - l, cy - r, cx + r, cy - r, paint)
        canvas.drawLine(cx + r, cy - r, cx + r, cy - r + l, paint)
        canvas.drawLine(cx - r, cy + r - l, cx - r, cy + r, paint)
        canvas.drawLine(cx - r, cy + r, cx - r + l, cy + r, paint)
        canvas.drawLine(cx + r - l, cy + r, cx + r, cy + r, paint)
        canvas.drawLine(cx + r, cy + r, cx + r, cy + r - l, paint)
    }
}
