package com.zugulu.cinecam

import android.content.Context
import android.opengl.GLSurfaceView
import android.util.AttributeSet

/** GLSurfaceView that keeps the video aspect ratio (letterboxed). */
class AspectGLSurfaceView(context: Context, attrs: AttributeSet?) : GLSurfaceView(context, attrs) {
    @Volatile var aspectW = 16
    @Volatile var aspectH = 9

    fun setAspect(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        if (aspectW == w && aspectH == h) return
        aspectW = w
        aspectH = h
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val parentW = MeasureSpec.getSize(widthMeasureSpec)
        val parentH = MeasureSpec.getSize(heightMeasureSpec)
        val ratio = aspectW.toFloat() / aspectH
        var w = parentW
        var h = (w / ratio).toInt()
        if (h > parentH) {
            h = parentH
            w = (h * ratio).toInt()
        }
        setMeasuredDimension(w, h)
    }
}
