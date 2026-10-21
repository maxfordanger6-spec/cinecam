package com.zugulu.cinecam
import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix as GLMatrix
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig as KConfig
import javax.microedition.khronos.opengles.GL10

class PreviewRenderer(
    context: Context
) : GLSurfaceView.Renderer {

    var surfaceTexture: SurfaceTexture? = null
        private set

    /** Called (on GL thread) when the OES SurfaceTexture is ready.
     *  Second arg = the previous texture (if the GL context was recreated) —
     *  the camera must be re-wired to the new texture before the old one is released. */
    var onTextureReady: ((SurfaceTexture, SurfaceTexture?) -> Unit)? = null

    /** Debug sink for GL diagnostics. */
    var onDebug: ((String) -> Unit)? = null

    // shader uniforms (set externally)
    @Volatile var peakOn = false
    @Volatile var zebraOn = false
    @Volatile var falseColorOn = false
    @Volatile var lutOn = false
    @Volatile var zebraLevel = 0.95f
    @Volatile var portraitMode = false   // true: portrait buffer + 9:16 view (full screen)

    /** Latest center-pixel average from the GL framebuffer (for diagnostics). */
    @Volatile var lastCenterRgb = intArrayOf(0, 0, 0)

    // LUT plumbing: set from any thread, uploaded on GL thread when version changes
    @Volatile private var lutData: ByteArray? = null
    @Volatile private var lutVersion = 0
    private var lutUploadedVersion = 0

    fun setLut(data: ByteArray?) {
        lutData = data
        lutVersion++
    }

    private val vb: FloatBuffer = ByteBuffer.allocateDirect(16 * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer()

    private var program = 0
    private var aPos = 0
    private var aUV = 0
    private var uSTMatrix = 0
    private var uPeak = 0
    private var uZebra = 0
    private var uFalseColor = 0
    private var uLutOn = 0
    private var uLutSampler = 0
    private var uZebraLvl = 0
    private var uTexel = 0
    private var texId = 0
    private var lutTexId = 0
    private var textureTransform = FloatArray(16)
    private var frameCount = 0L
    private var lastTsLogged = 0L

    private var glSurfaceW = 0
    private var glSurfaceH = 0

    /** Preview texture size (camera buffer), landscape-normalized. */
    @Volatile var textureW = 1920
    @Volatile var textureH = 1080

    // Two static quads — zero rotation math, the camera content is always upright
    // and correctly handed in the view.
    //
    // LANDSCAPE (16:9 buffer): the raw sensor buffer is rotated 90° inside the
    // landscape frame (scene-up at u=0, scene-left at v=0, scene-right at v=1):
    private val quadLandscape = floatArrayOf(
        //  x,   y,   u,   v
        -1f, -1f, 1f, 0f,   // bottom-left : scene-down + scene-left
         1f, -1f, 1f, 1f,   // bottom-right: scene-down + scene-right
        -1f,  1f, 0f, 0f,   // top-left    : scene-up   + scene-left
         1f,  1f, 0f, 1f    // top-right   : scene-up   + scene-right
    )
    // PORTRAIT (9:16 buffer): the HAL delivers the scene already upright —
    // buffer top = scene-up, buffer left = scene-left (standard, non-mirrored).
    private val quadPortrait = floatArrayOf(
        //  x,   y,   u,   v
        -1f, -1f, 0f, 1f,   // bottom-left : scene-left  + scene-down
         1f, -1f, 1f, 1f,   // bottom-right: scene-right + scene-down
        -1f,  1f, 0f, 0f,   // top-left    : scene-left  + scene-up
         1f,  1f, 1f, 0f    // top-right   : scene-right + scene-up
    )

    private val VERT = """
        uniform mat4 uSTMatrix;
        attribute vec4 aPosition;
        attribute vec2 aTexCoord;
        varying vec2 vTexCoord;
        void main() {
            gl_Position = aPosition;
            vTexCoord = (uSTMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;
        }
    """

    private val FRAG = """
        #extension GL_OES_EGL_image_external : require
        precision mediump float;
        uniform samplerExternalOES sTexture;
        uniform sampler2D uLut;
        uniform int uLutOn;
        uniform int uPeak;
        uniform int uZebra;
        uniform int uFalseColor;
        uniform float uZebraLevel;
        uniform vec2 uTexel;
        varying vec2 vTexCoord;

        float luma(vec2 uv) {
            vec3 c = texture2D(sTexture, uv).rgb;
            return dot(c, vec3(0.299, 0.587, 0.114));
        }

        // 33^3 LUT atlas: 33 tiles of 33x33 stacked horizontally (1089x33)
        vec3 sampleLut(vec3 c) {
            vec3 cc = clamp(c, 0.0, 1.0);
            float ri = cc.r * 32.0, gi = cc.g * 32.0, bi = cc.b * 32.0;
            float r0 = floor(ri), g0 = floor(gi), b0 = floor(bi);
            float fr = fract(ri), fg = fract(gi), fb = fract(bi);
            float x0 = (b0 * 33.0 + r0 + 0.5) / 1089.0;
            float x1 = (b0 * 33.0 + r0 + 1.5) / 1089.0;
            float y0 = (g0 + 0.5) / 33.0;
            float y1 = (g0 + 1.5) / 33.0;
            float xb = (b0 + 1.0) * 33.0;
            float x2 = (xb + r0 + 0.5) / 1089.0;
            float x3 = (xb + r0 + 1.5) / 1089.0;
            vec3 c00 = texture2D(uLut, vec2(x0, y0)).rgb;
            vec3 c01 = texture2D(uLut, vec2(x1, y0)).rgb;
            vec3 c10 = texture2D(uLut, vec2(x0, y1)).rgb;
            vec3 c11 = texture2D(uLut, vec2(x1, y1)).rgb;
            vec3 c20 = texture2D(uLut, vec2(x2, y0)).rgb;
            vec3 c21 = texture2D(uLut, vec2(x3, y0)).rgb;
            vec3 c30 = texture2D(uLut, vec2(x2, y1)).rgb;
            vec3 c31 = texture2D(uLut, vec2(x3, y1)).rgb;
            vec3 ca = mix(mix(c00, c01, fr), mix(c10, c11, fr), fg);
            vec3 cb = mix(mix(c20, c21, fr), mix(c30, c31, fr), fg);
            return mix(ca, cb, fb);
        }

        void main() {
            vec3 col = texture2D(sTexture, vTexCoord).rgb;
            float lum = dot(col, vec3(0.299, 0.587, 0.114));
            vec3 outCol = col;

            if (uLutOn == 1) outCol = sampleLut(outCol);

            if (uFalseColor == 1) {
                vec3 fc;
                if      (lum < 0.02) fc = vec3(0.16, 0.06, 0.62);
                else if (lum < 0.09) fc = vec3(0.16, 0.16, 0.80);
                else if (lum < 0.18) fc = vec3(0.03, 0.23, 0.90);
                else if (lum < 0.27) fc = vec3(0.02, 0.50, 0.80);
                else if (lum < 0.36) fc = vec3(0.00, 0.65, 0.60);
                else if (lum < 0.45) fc = vec3(0.15, 0.72, 0.30);
                else if (lum < 0.54) fc = vec3(0.20, 0.75, 0.10);
                else if (lum < 0.63) fc = vec3(0.40, 0.70, 0.00);
                else if (lum < 0.72) fc = vec3(0.60, 0.60, 0.00);
                else if (lum < 0.82) fc = vec3(0.75, 0.45, 0.00);
                else if (lum < 0.90) fc = vec3(0.80, 0.30, 0.00);
                else if (lum < 0.97) fc = vec3(0.85, 0.15, 0.00);
                else                 fc = vec3(1.00, 0.05, 0.05);
                outCol = fc;
            }
            if (uZebra == 1 && lum > uZebraLevel) {
                float stripe = mod(floor(gl_FragCoord.y / 4.0), 2.0);
                if (stripe < 0.5) outCol = vec3(0.95, 0.15, 0.15);
            }
            if (uPeak == 1) {
                float gx = luma(vTexCoord + vec2(uTexel.x, 0.0)) - luma(vTexCoord - vec2(uTexel.x, 0.0));
                float gy = luma(vTexCoord + vec2(0.0, uTexel.y)) - luma(vTexCoord - vec2(0.0, uTexel.y));
                float g = abs(gx) + abs(gy);
                if (g > 0.055) outCol = mix(outCol, vec3(0.85, 0.1, 0.1), 0.85);
            }
            // DIAGNOSTIC: 24px green square bottom-left = GL pipeline renders
            if (gl_FragCoord.x < 24.0 && gl_FragCoord.y < 24.0) outCol = vec3(0.2, 1.0, 0.3);
            gl_FragColor = vec4(outCol, 1.0);
        }
    """

    private fun compile(type: Int, src: String): Int {
        val sh = GLES20.glCreateShader(type)
        GLES20.glShaderSource(sh, src)
        GLES20.glCompileShader(sh)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(sh, GLES20.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) {
            val info = GLES20.glGetShaderInfoLog(sh)
            android.util.Log.e("CineCamGL", "shader: $info")
            onDebug?.invoke("GL shader compile FAILED: $info")
            return 0
        }
        return sh
    }

    override fun onSurfaceCreated(gl: GL10?, config: KConfig?) {
        program = GLES20.glCreateProgram()
        val vs = compile(GLES20.GL_VERTEX_SHADER, VERT)
        val fs = compile(GLES20.GL_FRAGMENT_SHADER, FRAG)
        GLES20.glAttachShader(program, vs)
        GLES20.glAttachShader(program, fs)
        GLES20.glLinkProgram(program)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) {
            val info = GLES20.glGetProgramInfoLog(program)
            android.util.Log.e("CineCamGL", "link: $info")
            onDebug?.invoke("GL link FAILED: $info")
        }

        aPos = GLES20.glGetAttribLocation(program, "aPosition")
        aUV = GLES20.glGetAttribLocation(program, "aTexCoord")
        uSTMatrix = GLES20.glGetUniformLocation(program, "uSTMatrix")
        uPeak = GLES20.glGetUniformLocation(program, "uPeak")
        uZebra = GLES20.glGetUniformLocation(program, "uZebra")
        uFalseColor = GLES20.glGetUniformLocation(program, "uFalseColor")
        uLutOn = GLES20.glGetUniformLocation(program, "uLutOn")
        uLutSampler = GLES20.glGetUniformLocation(program, "uLut")
        uZebraLvl = GLES20.glGetUniformLocation(program, "uZebraLevel")
        uTexel = GLES20.glGetUniformLocation(program, "uTexel")

        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        texId = tex[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        // LUT atlas texture (1089x33 RGBA8)
        val lut = IntArray(1)
        GLES20.glGenTextures(1, lut, 0)
        lutTexId = lut[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lutTexId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        lutUploadedVersion = -1

        GLMatrix.setIdentityM(textureTransform, 0)

        // IMPORTANT: if the EGL context is recreated (surface resize etc.), the camera
        // is still streaming into the OLD SurfaceTexture. We must NOT release it here —
        // the activity closes the camera first, then releases the old texture.
        val old = surfaceTexture
        surfaceTexture = SurfaceTexture(texId)
        surfaceTexture!!.setDefaultBufferSize(1920, 1080)
        onDebug?.invoke(if (old == null) "GL ctx created, texture ready"
        else "GL ctx RECREATED — rewire camera")
        onTextureReady?.invoke(surfaceTexture!!, old)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        glSurfaceW = width
        glSurfaceH = height
        onDebug?.invoke("GL surface ${width}x${height}")
    }

    private fun uploadLutIfDirty() {
        if (lutUploadedVersion == lutVersion) return
        lutUploadedVersion = lutVersion
        val data = lutData
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lutTexId)
        if (data == null) {
            val zero = ByteArray(1089 * 33 * 4)
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, 1089, 33, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, ByteBuffer.wrap(zero))
            return
        }
        // RGB -> RGBA (LUT data is packed RGB)
        val rgba = ByteArray(1089 * 33 * 4)
        var o = 0; var i = 0
        while (o < rgba.size) {
            rgba[o++] = data[i++]
            rgba[o++] = data[i++]
            rgba[o++] = data[i++]
            rgba[o++] = -1
        }
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, 1089, 33, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, ByteBuffer.wrap(rgba))
    }

    override fun onDrawFrame(gl: GL10?) {
        val st = surfaceTexture ?: return
        st.updateTexImage()
        st.getTransformMatrix(textureTransform)
        val frameTs = st.timestamp
        uploadLutIfDirty()

        GLES20.glClearColor(0f, 0.004f, 0.008f, 1f)   // very dark blue — distinguishes
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)    // GL-rendering from pure-black dead GL
        GLES20.glUseProgram(program)

        // static upright mapping (see quadLandscape/quadPortrait) — no rotation math
        val q = if (portraitMode) quadPortrait else quadLandscape
        vb.position(0)
        vb.put(q)
        vb.position(0)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, vb)
        GLES20.glEnableVertexAttribArray(aUV)
        vb.position(2)
        GLES20.glVertexAttribPointer(aUV, 2, GLES20.GL_FLOAT, false, 16, vb)

        GLES20.glUniformMatrix4fv(uSTMatrix, 1, false, textureTransform, 0)
        GLES20.glUniform1i(uPeak, if (peakOn) 1 else 0)
        GLES20.glUniform1i(uZebra, if (zebraOn) 1 else 0)
        GLES20.glUniform1i(uFalseColor, if (falseColorOn) 1 else 0)
        GLES20.glUniform1i(uLutOn, if (lutOn && lutData != null) 1 else 0)
        GLES20.glUniform1f(uZebraLvl, zebraLevel)
        GLES20.glUniform2f(uTexel, 1f / glSurfaceW, 1f / glSurfaceH)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "sTexture"), 0)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lutTexId)
        GLES20.glUniform1i(uLutSampler, 1)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPos)
        GLES20.glDisableVertexAttribArray(aUV)

        // ---- encoder pass: same geometry, pre-rotated frames into MediaCodec ----
        manageEncoderEgl()
        if (encEglSurface != null) drawEncoderFrame(frameTs)

        // DIAGNOSTIC: report whether frames actually reach the SurfaceTexture
        frameCount++
        if (frameCount % 150L == 1L) {
            val ts = st.timestamp
            if (ts != lastTsLogged) {
                lastTsLogged = ts
                onDebug?.invoke("GL frames flowing n=$frameCount ts=$ts")
            } else {
                onDebug?.invoke("GL ts STALLED n=$frameCount ts=$ts")
            }
        }
        // DIAGNOSTIC: read back actual pixels (center 8x8) so we can see if the
        // texture carries real image data or black frames — fast cadence so the
        // sensor self-test window (ISO max) gets sampled
        if (frameCount % 30L == 2L) {
            try {
                val buf = ByteBuffer.allocateDirect(8 * 8 * 4)
                GLES20.glReadPixels(glSurfaceW / 2 - 4, glSurfaceH / 2 - 4, 8, 8,
                    GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
                var r = 0; var g = 0; var b = 0
                while (buf.hasRemaining()) {
                    r += buf.get().toInt() and 0xFF
                    g += buf.get().toInt() and 0xFF
                    b += buf.get().toInt() and 0xFF
                    buf.get()
                }
                val n = 64
                lastCenterRgb = intArrayOf(r / n, g / n, b / n)
                if (frameCount % 300L == 2L) {
                    onDebug?.invoke("pix(center)=${r / n},${g / n},${b / n}")
                }
            } catch (e: Exception) {
                onDebug?.invoke("pix readback failed: ${e.message}")
            }
        }
    }

    // ---------------- encoder EGL pass ----------------
    // Renders each fresh camera frame into the MediaCodec input surface,
    // pre-rotated (same transform as the display, no monitoring overlays),
    // with real PTS via eglPresentationTimeANDROID. Runs on the GL thread
    // inside onDrawFrame, sharing the renderer's own context by re-binding.

    private var encEglSurface: EGLSurface? = null
    private var eglDisp: EGLDisplay? = null
    private var eglCtx: EGLContext? = null
    private var encW = 0
    private var encH = 0
    @Volatile private var pendingEncoder: Surface? = null
    @Volatile private var encDetachRequested = false

    /** Attach a MediaCodec input surface. Called from the UI thread. */
    fun attachEncoder(surface: Surface, w: Int, h: Int) {
        encW = w
        encH = h
        pendingEncoder = surface
        encDetachRequested = false
        onDebug?.invoke("encoder attach ${w}x${h}")
    }

    /** Stop feeding + destroy the encoder EGL surface (next GL frame). */
    fun detachEncoder() {
        pendingEncoder = null
        encDetachRequested = true
    }

    private fun manageEncoderEgl() {
        if (encDetachRequested && encEglSurface != null) {
            try {
                val disp = eglDisp
                if (disp != null) {
                    EGL14.eglMakeCurrent(disp, encEglSurface, encEglSurface, eglCtx)
                    EGL14.eglDestroySurface(disp, encEglSurface)
                }
            } catch (_: Exception) {}
            encEglSurface = null
            encDetachRequested = false
            onDebug?.invoke("encoder EGL detached")
        }
        if (pendingEncoder != null && encEglSurface == null) {
            try {
                val disp = EGL14.eglGetCurrentDisplay()
                val ctx = EGL14.eglGetCurrentContext()
                if (disp != EGL14.EGL_NO_DISPLAY && ctx != EGL14.EGL_NO_CONTEXT) {
                    val cfgAttribs = intArrayOf(
                        EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
                        EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                        EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
                        EGL14.EGL_NONE)
                    val configs = arrayOfNulls<EGLConfig>(1)
                    val n = IntArray(1)
                    if (EGL14.eglChooseConfig(disp, cfgAttribs, 0, configs, 0, 1, n, 0) && n[0] > 0) {
                        val surf = EGL14.eglCreateWindowSurface(disp, configs[0],
                            pendingEncoder, intArrayOf(EGL14.EGL_NONE), 0)
                        if (surf != null && surf != EGL14.EGL_NO_SURFACE) {
                            encEglSurface = surf
                            eglDisp = disp
                            eglCtx = ctx
                            onDebug?.invoke("encoder EGL surface created")
                        } else {
                            onDebug?.invoke("encoder EGL createWindowSurface failed")
                        }
                    } else {
                        onDebug?.invoke("encoder EGL chooseConfig failed")
                    }
                }
            } catch (e: Exception) {
                onDebug?.invoke("encoder EGL init failed: ${e.message}")
            }
            pendingEncoder = null
        }
    }

    private fun drawEncoderFrame(tsNs: Long) {
        val surf = encEglSurface ?: return
        val disp = eglDisp ?: return
        val drawSurf = EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW)
        if (drawSurf == null || drawSurf == EGL14.EGL_NO_SURFACE) return
        if (!EGL14.eglMakeCurrent(disp, surf, surf, eglCtx)) return
        try {
            GLES20.glViewport(0, 0, encW, encH)
            GLES20.glUseProgram(program)

            val q = if (portraitMode) quadPortrait else quadLandscape
            vb.position(0)
            vb.put(q)
            vb.position(0)
            GLES20.glEnableVertexAttribArray(aPos)
            GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, vb)
            GLES20.glEnableVertexAttribArray(aUV)
            vb.position(2)
            GLES20.glVertexAttribPointer(aUV, 2, GLES20.GL_FLOAT, false, 16, vb)

            GLES20.glUniformMatrix4fv(uSTMatrix, 1, false, textureTransform, 0)
            GLES20.glUniform1i(uPeak, 0)      // monitoring overlays are preview-only
            GLES20.glUniform1i(uZebra, 0)
            GLES20.glUniform1i(uFalseColor, 0)
            GLES20.glUniform1i(uLutOn, 0)
            GLES20.glUniform1f(uZebraLvl, 1f)
            GLES20.glUniform2f(uTexel, 1f / encW, 1f / encH)

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "sTexture"), 0)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lutTexId)
            GLES20.glUniform1i(uLutSampler, 1)

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glDisableVertexAttribArray(aPos)
            GLES20.glDisableVertexAttribArray(aUV)

            EGLExt.eglPresentationTimeANDROID(disp, surf, tsNs)
            EGL14.eglSwapBuffers(disp, surf)
        } catch (e: Exception) {
            onDebug?.invoke("encoder draw failed: ${e.message}")
        }
        EGL14.eglMakeCurrent(disp, drawSurf, drawSurf, eglCtx)
        GLES20.glViewport(0, 0, glSurfaceW, glSurfaceH)
        val q = if (portraitMode) quadPortrait else quadLandscape
        vb.position(0)
        vb.put(q)
        vb.position(0)
    }
}
