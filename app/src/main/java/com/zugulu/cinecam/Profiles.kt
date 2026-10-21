package com.zugulu.cinecam

import android.hardware.camera2.params.ColorSpaceTransform
import android.util.Rational
import kotlin.math.pow

/**
 * Cinematic image profiles. Each profile shapes contrast via tonemap curves,
 * saturation via a 3x3 color transform matrix and white-balance bias via gains.
 * The same parameters feed a procedural 33x33x33 LUT for GPU preview monitoring.
 */
data class Profile(
    val name: String,
    val short: String,
    val desc: String,
    val red: FloatArray,     // tonemap curve P,Q pairs (0..1)
    val green: FloatArray,
    val blue: FloatArray,
    val saturation: Float,   // 1.0 = neutral
    val wbGains: FloatArray  // {R, G_even, G_odd, B} multipliers (1=neutral)
)

object Profiles {

    private val LINEAR = floatArrayOf(0f, 0f, 0.25f, 0.25f, 0.5f, 0.5f, 0.75f, 0.75f, 1f, 1f)

    // Flat / log-style: lifted blacks (~13%), rolled highlights (~93%), gentle S.
    private val FLAT_CURVE = floatArrayOf(
        0.0f, 0.13f, 0.18f, 0.33f, 0.36f, 0.50f, 0.55f, 0.65f,
        0.74f, 0.78f, 0.90f, 0.88f, 1.0f, 0.93f
    )

    // Mild S-curve for a punchier film look.
    private val CINEMA_S = floatArrayOf(
        0.0f, 0.03f, 0.20f, 0.17f, 0.40f, 0.34f, 0.60f, 0.53f,
        0.80f, 0.72f, 0.92f, 0.88f, 1.0f, 0.99f
    )

    // Harder S for vivid.
    private val VIVID_S = floatArrayOf(
        0.0f, 0.02f, 0.20f, 0.13f, 0.40f, 0.29f, 0.60f, 0.50f,
        0.80f, 0.73f, 0.92f, 0.90f, 1.0f, 1.0f
    )

    // High contrast B&W
    private val MONO_S = floatArrayOf(
        0.0f, 0.0f, 0.22f, 0.14f, 0.44f, 0.34f, 0.66f, 0.62f,
        0.86f, 0.86f, 1.0f, 1.0f
    )

    // Film noir: crushed blacks, hard shoulder
    private val NOIR_S = floatArrayOf(
        0.0f, 0.0f, 0.18f, 0.08f, 0.38f, 0.24f, 0.58f, 0.46f,
        0.78f, 0.72f, 0.90f, 0.88f, 1.0f, 1.0f
    )

    // Pastel: airy, lifted everything
    private val PASTEL_CURVE = floatArrayOf(
        0.0f, 0.06f, 0.22f, 0.30f, 0.44f, 0.52f, 0.66f, 0.72f,
        0.86f, 0.88f, 1.0f, 0.98f
    )

    // Ultra-flat: blacks lifted ~18%, highlights rolled ~85% — max grading latitude
    private val ULTRA_FLAT = floatArrayOf(
        0.0f, 0.18f, 0.20f, 0.38f, 0.40f, 0.52f, 0.60f, 0.64f,
        0.80f, 0.75f, 0.92f, 0.83f, 1.0f, 0.88f
    )

    val ALL: List<Profile> = listOf(
        Profile(
            "Natural", "NAT", "Clean, true-to-life colors",
            LINEAR, LINEAR, LINEAR, 1.0f,
            floatArrayOf(1f, 1f, 1f, 1f)
        ),
        Profile(
            "Zugulu LOG", "LOG", "Flat log-style curve for color grading",
            FLAT_CURVE, FLAT_CURVE, FLAT_CURVE, 0.82f,
            floatArrayOf(1f, 1f, 1f, 1f)
        ),
        Profile(
            "Cinema", "CIN", "Soft filmic S-curve, warm skin tones",
            CINEMA_S, CINEMA_S, CINEMA_S, 1.12f,
            floatArrayOf(1.06f, 1.0f, 1.0f, 0.97f)
        ),
        Profile(
            "Teal & Orange", "T&O", "Blockbuster look: teal shadows, amber skin",
            CINEMA_S, CINEMA_S, FLAT_CURVE, 0.98f,
            floatArrayOf(1.10f, 1.0f, 1.0f, 0.88f)
        ),
        Profile(
            "Noir", "NOIR", "Crushed blacks, cold steel blues",
            NOIR_S, NOIR_S, NOIR_S, 0.18f,
            floatArrayOf(1.03f, 1.0f, 1.0f, 0.93f)
        ),
        Profile(
            "Pastel", "PASTL", "Airy lifted pastel tones",
            PASTEL_CURVE, PASTEL_CURVE, PASTEL_CURVE, 0.78f,
            floatArrayOf(0.99f, 1.0f, 1.0f, 1.05f)
        ),
        Profile(
            "Vivid", "VIV", "Punchy colors, strong contrast",
            VIVID_S, VIVID_S, VIVID_S, 1.35f,
            floatArrayOf(1.02f, 1.0f, 1.0f, 1.02f)
        ),
        Profile(
            "Mono", "MON", "High-contrast black & white",
            MONO_S, MONO_S, MONO_S, 0.0f,
            floatArrayOf(1f, 1f, 1f, 1f)
        ),
        Profile(
            "Flat", "FLAT", "Ultra-flat: maximum grading latitude, minimal contrast",
            ULTRA_FLAT, ULTRA_FLAT, ULTRA_FLAT, 0.72f,
            floatArrayOf(1f, 1f, 1f, 1f)
        )
    )

    /**
     * Build the 3x3 saturation matrix (as CameraMetadata.Rationals, row-major)
     * mixing towards luminance by factor (1-s).
     */
    fun saturationMatrix(s: Float): ColorSpaceTransform {
        val lum = floatArrayOf(0.2126f, 0.7152f, 0.0722f)
        val m = FloatArray(9)
        var idx = 0
        for (row in 0 until 3) {
            for (col in 0 until 3) {
                val base = if (row == col) 1f else 0f
                m[idx] = s * base + (1f - s) * lum[col]
                idx++
            }
        }
        val rationals = Array(9) { i ->
            Rational((m[i] * 4096f).toInt(), 4096)
        }
        return ColorSpaceTransform(rationals)
    }

    private fun saturationMatrix9(s: Float): FloatArray {
        val lum = floatArrayOf(0.2126f, 0.7152f, 0.0722f)
        val m = FloatArray(9)
        var idx = 0
        for (row in 0 until 3) {
            for (col in 0 until 3) {
                val base = if (row == col) 1f else 0f
                m[idx] = s * base + (1f - s) * lum[col]
                idx++
            }
        }
        return m
    }

    /** Piecewise-linear evaluation of a P,Q curve at x in [0,1]. */
    fun curveAt(curve: FloatArray, x: Float): Float {
        val xc = x.coerceIn(0f, 1f)
        if (xc <= curve[0]) return curve[1]
        var i = 2
        while (i < curve.size - 2 && xc > curve[i]) i += 2
        val p0 = curve[i - 2]; val q0 = curve[i - 1]
        val p1 = curve[i]; val q1 = curve[i + 1]
        if (p1 <= p0) return q1
        val t = (xc - p0) / (p1 - p0)
        return q0 + t * (q1 - q0)
    }

    /** Kelvin -> RGB gains (green-normalized). Tanner Helland approximation, valid 1000K-15000K. */
    fun kelvinToGains(kelvin: Float): FloatArray {
        val k = kelvin.coerceIn(1000f, 15000f)
        val t = k / 100f
        var r: Float; var g: Float; var b: Float
        if (t <= 66) {
            r = 255f
            g = (99.4708025861f * kotlin.math.ln(t) - 161.1195681661f).coerceIn(0f, 255f)
            b = if (t <= 19) 0f else (138.5177312231f * kotlin.math.ln(t - 10f) - 305.0447927307f).coerceIn(0f, 255f)
        } else {
            r = (329.698727446f * Math.pow((t - 60).toDouble(), -0.1332047592)).toFloat().coerceIn(0f, 255f)
            g = (288.1221695283f * Math.pow((t - 60).toDouble(), -0.0755148492)).toFloat().coerceIn(0f, 255f)
            b = 255f
        }
        return floatArrayOf(r / g, 1f, 1f, b / g)
    }

    /**
     * Procedural 33^3 RGB LUT for GPU preview monitoring (FiLMiC-style look overlay).
     * Pipeline: sRGB -> linear, gains, saturation matrix, per-channel tonemap curve,
     * linear -> sRGB. Layout: b-major atlas tiles of 33x33 (r,g) — see PreviewRenderer.
     */
    fun buildLut(p: Profile, size: Int = 33): ByteArray {
        val n = size * size * size
        val out = ByteArray(n * 3)
        val m = saturationMatrix9(p.saturation)
        var idx = 0
        for (b in 0 until size) {
            for (g in 0 until size) {
                for (r in 0 until size) {
                    val cr = (r / (size - 1f)).pow(2.2f)
                    val cg = (g / (size - 1f)).pow(2.2f)
                    val cb = (b / (size - 1f)).pow(2.2f)
                    val gr = cr * p.wbGains[0]
                    val gg = cg * p.wbGains[1]
                    val gb = cb * p.wbGains[3]
                    val sr = m[0] * gr + m[1] * gg + m[2] * gb
                    val sg = m[3] * gr + m[4] * gg + m[5] * gb
                    val sb = m[6] * gr + m[7] * gg + m[8] * gb
                    val or = curveAt(p.red, sr.coerceIn(0f, 1f))
                    val og = curveAt(p.green, sg.coerceIn(0f, 1f))
                    val ob = curveAt(p.blue, sb.coerceIn(0f, 1f))
                    out[idx++] = (or.coerceIn(0f, 1f).pow(1f / 2.2f) * 255f).toInt().toByte()
                    out[idx++] = (og.coerceIn(0f, 1f).pow(1f / 2.2f) * 255f).toInt().toByte()
                    out[idx++] = (ob.coerceIn(0f, 1f).pow(1f / 2.2f) * 255f).toInt().toByte()
                }
            }
        }
        return out
    }
}
