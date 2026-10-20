package com.zugulu.cinecam

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
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
    @Volatile var rotationDegrees = 0f  // total preview rotation (sensor - device)
    @Volatile var rotSign = 1f          // flip to -1f if preview appears rotated the wrong way
    @Volatile var rawMode = true        // DIAGNOSTIC: raw sampling (no transform)

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

    private val vtx = floatArrayOf(
        // x, y, u, v
        -1f, -1f, 0f, 0f,
         1f, -1f, 1f, 0f,
        -1f,  1f, 0f, 1f,
         1f,  1f, 1f, 1f
    )
    private val vb: FloatBuffer = ByteBuffer.allocateDirect(vtx.size * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(vtx); position(0) }

    private var program = 0
    private var aPos = 0
    private var aUV = 0
    private var uSTMatrix = 0
    private var uRot = 0
    private var uRaw = 0
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

    private val VERT = """
        uniform mat4 uSTMatrix;
        uniform float uRot;
        uniform int uRaw;
        attribute vec4 aPosition;
        attribute vec2 aTexCoord;
        varying vec2 vTexCoord;
        void main() {
            if (uRaw == 1) {
                vTexCoord = aTexCoord;                 // DIAGNOSTIC: raw sampling
            } else {
                vec2 uv = aTexCoord;
                uv.y = 1.0 - uv.y;                     // GL buffer origin fix (sensor top = v0)
                float rad = radians(uRot);
                float c = cos(rad), s = sin(rad);
                uv = mat2(c, -s, s, c) * (uv - 0.5) + 0.5;
                vTexCoord = (uSTMatrix * vec4(uv, 0.0, 1.0)).xy;
            }
            gl_Position = aPosition;
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

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
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
        uRot = GLES20.glGetUniformLocation(program, "uRot")
        uRaw = GLES20.glGetUniformLocation(program, "uRaw")
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

        Matrix.setIdentityM(textureTransform, 0)

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
        uploadLutIfDirty()

        GLES20.glClearColor(0f, 0.004f, 0.008f, 1f)   // very dark blue — distinguishes
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)    // GL-rendering from pure-black dead GL
        GLES20.glUseProgram(program)

        val rot = rotSign * rotationDegrees % 360f

        vb.position(0)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, vb)
        GLES20.glEnableVertexAttribArray(aUV)
        vb.position(2)
        GLES20.glVertexAttribPointer(aUV, 2, GLES20.GL_FLOAT, false, 16, vb)

        GLES20.glUniformMatrix4fv(uSTMatrix, 1, false, textureTransform, 0)
        GLES20.glUniform1f(uRot, rot)
        GLES20.glUniform1i(uRaw, if (rawMode) 1 else 0)
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

    object GLES11Ext {
        const val GL_TEXTURE_EXTERNAL_OES = 0x8D65
    }
}
