package com.zugulu.cinecam

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.DynamicRangeProfiles
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import java.util.concurrent.Semaphore

/** All manual camera settings in one place. */
data class CamState(
    var aeAuto: Boolean = true,
    var iso: Int = 100,
    var shutterAuto: Boolean = true,
    var shutterNs: Long = 33_333_333L,
    var afAuto: Boolean = true,
    var focusDistance: Float = 0f,       // diopters
    var wbAuto: Boolean = true,
    var kelvin: Int = 5600,
    var tint: Float = 1f,
    var zoom: Float = 1f,
    var profileIdx: Int = 0,
    var fps: Int = 30,
    var bitrateIdx: Int = 1,             // 0 STD, 1 HIGH, 2 ULTRA
    var stabilization: Boolean = true,
    var camIdx: Int = 0
)

data class Capabilities(
    val isoMin: Int, val isoMax: Int,
    val expoMinNs: Long, val expoMaxNs: Long,
    val minFocusDist: Float,
    val maxZoom: Float,
    val gainsMin: Float, val gainsMax: Float,
    val sensorOrientation: Int,
    val dynamicRange: Set<Long>,         // DynamicRangeProfile values (API 33+), e.g. HLG10
    val activeArray: Rect,
    val sizes: List<Size>,               // sorted desc, 16:9 preferred
    val fpsSupported: Set<Int>,
    val stabilizationSupported: Boolean,
    val hardwareLevel: Int
)

class CameraController(
    private val context: Context,
    private val onStateChanged: (CamState) -> Unit
) {
    companion object { private const val TAG = "CineCam" }

    val state = CamState()
    var capabilities: Capabilities? = null
        private set

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var device: CameraDevice? = null
    private var opening = false
    private var session: CameraCaptureSession? = null
    private var previewBuilder: CaptureRequest.Builder? = null
    private var previewSurface: Surface? = null
    private var recordSurface: Surface? = null
    private var histogramReader: ImageReader? = null
    private val thread = HandlerThread("cinecam").apply { start() }
    private val handler = Handler(thread.looper)
    private val lock = Semaphore(1)

    private val cameras = mutableListOf<String>()

    var previewSize: Size = Size(1920, 1080)

    /** Recording target height (for EIS gating). Set by the UI on res change. */
    @Volatile var videoHeight = 1080

    /** HLG10 recording when the device supports it (API 33+). */
    @Volatile var hdrOn = false

    // AE/AF reticle regions (sensor-space MeteringRectangles, set by the UI)
    private var aeRegion: android.hardware.camera2.params.MeteringRectangle? = null
    private var afRegion: android.hardware.camera2.params.MeteringRectangle? = null
    @Volatile private var afTriggerPending = false

    private var texture: SurfaceTexture? = null

    fun setTexture(t: SurfaceTexture) {
        texture = t
    }
    var recording = false
        private set

    // histogram plumbing
    private var histListener: ((IntArray) -> Unit)? = null
    private var lastHistTime = 0L

    /** Invoked (on camera handler thread) after capabilities are (re)read. */
    var onCapabilities: (() -> Unit)? = null

    /** Invoked when a session could not be configured even after fallbacks. */
    var onSessionError: ((String) -> Unit)? = null

    /** Invoked (camera handler thread) when the repeating preview request is running. */
    var onPreviewStarted: (() -> Unit)? = null

    /** Bisection knob: 0 = AE/exposure only, 1 = +fps/regions, 2 = +stab, 3 = full profiles. */
    @Volatile var safeLevel = 0

    /** On-screen debug log sink (thread-safe enough for our purposes). */
    var debugLog: ((String) -> Unit)? = null

    private fun d(msg: String) {
        Log.i(TAG, msg)
        debugLog?.invoke(msg)
    }

    private var histBlocked = false

    var deviceRotation = 0

    val totalRotation: Int
        get() {
            val c = capabilities ?: return 0
            return (c.sensorOrientation - deviceRotation + 360) % 360
        }

    fun setHistogramListener(l: ((IntArray) -> Unit)?) { histListener = l }

    fun enumerate(): List<String> {
        cameras.clear()
        try {
            var bestArea = 0L
            var bestId: String? = null
            for (id in cameraManager.cameraIdList) {
                val ch = cameraManager.getCameraCharacteristics(id)
                val facing = ch.get(CameraCharacteristics.LENS_FACING) ?: continue
                val arr = ch.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
                val area = if (arr != null) arr.width().toLong() * arr.height() else 0L
                val fl = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                    ?.firstOrNull() ?: 0f
                if (facing == CameraCharacteristics.LENS_FACING_BACK) {
                    cameras.add(id)
                    d("cam$id BACK arr=${arr?.width()}x${arr?.height()} focal=$fl")
                    if (area > bestArea) {
                        bestArea = area
                        bestId = id
                    }
                } else {
                    d("cam$id facing=$facing (skipped)")
                }
            }
            // default to the LARGEST back sensor (the real main camera) — budget
            // devices expose the depth/aux sensor as "0" which delivers BLACK frames
            if (bestId != null) {
                val idx = cameras.indexOf(bestId)
                if (idx >= 0) state.camIdx = idx
            }
            if (cameras.isNotEmpty()) {
                d("using cam${cameras[state.camIdx]} (largest sensor)")
            }
        } catch (e: Exception) {
            d("enumerate failed: ${e.message}")
        }
        return cameras
    }

    fun open() {
        val id = cameras.getOrNull(state.camIdx) ?: run { d("open: no camera id"); return }
        if (texture == null) { d("open: no texture yet"); return }
        if (opening || device != null) { d("open: already open/opening"); return }
        if (context.checkSelfPermission(Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) { d("open: no permission"); return }
        opening = true
        d("open requested: $id")
        try {
            cameraManager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(cam: CameraDevice) {
                    opening = false
                    device = cam
                    d("camera opened: $id")
                    capabilities = readCapabilities(id)
                    onCapabilities?.invoke()
                    createPreviewSession()
                }
                override fun onDisconnected(cam: CameraDevice) {
                    opening = false; d("camera disconnected"); cam.close(); device = null
                }
                override fun onError(cam: CameraDevice, err: Int) {
                    opening = false; d("camera error $err"); cam.close(); device = null
                }
            }, handler)
        } catch (e: Exception) { opening = false; d("open failed: ${e.message}") }
    }

    fun close() {
        try { session?.close() } catch (_: Exception) {}
        session = null
        try { device?.close() } catch (_: Exception) {}
        device = null
        histogramReader?.close()
        histogramReader = null
        capabilities = null
        previewBuilder = null
    }

    fun switchCamera() {
        if (cameras.size < 2) return
        state.camIdx = (state.camIdx + 1) % cameras.size
        close()
        open()
        onStateChanged(state)
    }

    private fun readCapabilities(id: String): Capabilities? {
        val ch = cameraManager.getCameraCharacteristics(id)
        val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return null
        val level = ch.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL) ?: 2

        val isoRange = ch.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE) ?: Range(100, 3200)
        val expoRange = ch.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE) ?: Range(1_000_000L, 100_000_000L)
        val minFocus = ch.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
        val maxZoom = ch.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 4f
        // NOTE: there is no public COLOR_CORRECTION_GAINS range key — use the standard
        // heuristic (1.0..4.0) like Open Camera does.
        val gainsMin = 1f
        val gainsMax = 4f
        val orientation = ch.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        val active = ch.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: Rect(0, 0, 4000, 3000)

        val allSizes = map.getOutputSizes(MediaRecorder::class.java).toList()
        val sizes = allSizes
            .filter { it.width in 640..4096 && it.height in 480..2304 }
            .sortedWith(compareByDescending<Size> { it.height }.thenByDescending { it.width })
        val ratio16_9 = sizes.filter { kotlin.math.abs(it.width.toFloat() / it.height - 16f / 9f) < 0.02f }
        val chosenSizes = (if (ratio16_9.size >= 3) ratio16_9 else sizes).distinctBy { it.height }

        // 🚨 The preview surface must use a SurfaceTexture-class size, NOT a
        // MediaRecorder-class size — some devices (MIUI) advertise 4096x2304 for the
        // encoder but silently deliver nothing to a SurfaceTexture of that size (BLACK
        // preview while the whole pipeline looks healthy). Preview: largest 16:9 ≤1080p.
        val texSizes = map.getOutputSizes(SurfaceTexture::class.java).toList()
            .sortedWith(compareByDescending<Size> { it.height }.thenByDescending { it.width })
        val tex16_9 = texSizes.filter {
            kotlin.math.abs(it.width.toFloat() / it.height - 16f / 9f) < 0.02f
        }
        val texPool = if (tex16_9.size >= 2) tex16_9 else texSizes
        val previewPick = texPool.filter { it.height <= 1080 }.firstOrNull()
            ?: texSizes.minByOrNull { it.height }
            ?: Size(1920, 1080)
        previewSize = previewPick

        val fpsRanges = ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: emptyArray()
        val fpsSet = mutableSetOf<Int>()
        for (f in listOf(24, 25, 30, 48, 50, 60)) {
            if (fpsRanges.any { it.lower == f && it.upper == f }) fpsSet.add(f)
        }
        if (!fpsSet.contains(state.fps)) {
            state.fps = fpsSet.firstOrNull() ?: 30
        }
        val stabModes = ch.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES) ?: intArrayOf()
        val stab = stabModes.contains(CameraCharacteristics.CONTROL_VIDEO_STABILIZATION_MODE_ON)

        // dynamic range profiles (Android 13+): HLG10 / HDR10 / HDR10+
        val dynRange: Set<Long> = if (Build.VERSION.SDK_INT >= 33) {
            try {
                ch.get(CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES)
                    ?.supportedProfiles ?: emptySet()
            } catch (e: Exception) {
                d("dynrange probe failed: ${e.message}")
                emptySet()
            }
        } else emptySet()

        val cap = Capabilities(isoRange.lower, isoRange.upper, expoRange.lower, expoRange.upper,
            minFocus, maxZoom, gainsMin, gainsMax, orientation, dynRange, active,
            chosenSizes.take(6), fpsSet, stab, level)
        d("caps: level=$level iso=$isoRange fps=$fpsSet stab=$stab hdr=$dynRange")
        d("rec size0=${chosenSizes.firstOrNull()} preview=$previewPick")

        // sanity defaults
        state.iso = ((isoRange.lower + 99) / 100 * 100).coerceAtMost(isoRange.upper)
        state.shutterNs = 33_333_333L
        return cap
    }

    fun pickSize(index: Int): Size {
        val cap = capabilities ?: return previewSize
        return cap.sizes.getOrElse(index) { cap.sizes.first() }
    }

    fun isFpsSupported(f: Int): Boolean = capabilities?.fpsSupported?.contains(f) == true

    fun createPreviewSession() {
        val cam = device ?: return
        val cap = capabilities ?: return
        val tex = texture ?: return
        try { lock.acquire() } catch (_: InterruptedException) { return }
        try {
            session?.close()
            tex.setDefaultBufferSize(previewSize.width, previewSize.height)
            val texSurface = Surface(tex)
            previewSurface = texSurface

            val targets = mutableListOf<Surface>(texSurface)
            if (histListener != null && !histBlocked) {
                histogramReader?.close()
                histogramReader = ImageReader.newInstance(176, 132, ImageFormat.YUV_420_888, 2)
                histogramReader?.setOnImageAvailableListener({ r ->
                    val img = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                    val now = System.currentTimeMillis()
                    if (now - lastHistTime > 66) {
                        lastHistTime = now
                        val hist = computeHistogram(img)
                        histListener?.invoke(hist)
                    }
                    img.close()
                }, handler)
                targets.add(histogramReader!!.surface)
            }

            cam.createCaptureSession(targets, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    session = s
                    d("session configured (${targets.size} surfaces)")
                    startRepeating()
                }
                override fun onConfigureFailed(s: CameraCaptureSession) {
                    d("session configure FAILED (histBlocked=$histBlocked)")
                    // retry ladder: drop the histogram surface first, then give up loudly
                    if (histListener != null && !histBlocked) {
                        histBlocked = true
                        histogramReader?.close()
                        histogramReader = null
                        createPreviewSession()
                    } else {
                        onSessionError?.invoke("camera session configure failed")
                    }
                }
            }, handler)
        } catch (e: Exception) {
            Log.e(TAG, "createPreviewSession", e)
        } finally {
            lock.release()
        }
    }

    private fun computeHistogram(img: android.media.Image): IntArray {
        val bins = IntArray(64)
        try {
            val plane = img.planes[0]
            val buf = plane.buffer
            val rowStride = plane.rowStride
            val pixStride = plane.pixelStride
            val w = img.width
            val h = img.height
            val row = ByteArray(rowStride)
            for (y in 0 until h step 2) {
                buf.position(y * rowStride)
                if (buf.remaining() < rowStride) break
                buf.get(row, 0, rowStride)
                for (x in 0 until w step 2) {
                    val v = row[x * pixStride].toInt() and 0xFF
                    bins[v * 64 / 256]++
                }
            }
        } catch (_: Exception) {}
        return bins
    }

    /** Build the capture request from current state. Every setting is fail-soft:
     *  a rejected set() must never kill the preview (black screen).
     *  Exposure/focus/zoom are ALWAYS applied (essential manual controls);
     *  fps/stab/regions need safeLevel >= 1; profile tonemap/color need >= 2. */
    private fun applyState(b: CaptureRequest.Builder) {
        val cap = capabilities ?: return
        val p = Profiles.ALL[state.profileIdx]
        val lvl = safeLevel

        // exposure
        try {
            if (state.aeAuto) {
                b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            } else {
                b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                b.set(CaptureRequest.SENSOR_SENSITIVITY, state.iso)
                b.set(CaptureRequest.SENSOR_EXPOSURE_TIME,
                    state.shutterNs.coerceIn(cap.expoMinNs, cap.expoMaxNs))
            }
            if (lvl >= 1) aeRegion?.let { b.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(it)) }
        } catch (e: Exception) { d("set FAILED ae: ${e.message}") }

        // focus
        try {
            if (state.afAuto) {
                val afR = afRegion
                if (afR != null) {
                    // region-based one-shot AF (reticle mode)
                    b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
                    b.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(afR))
                    b.set(CaptureRequest.CONTROL_AF_TRIGGER,
                        if (afTriggerPending) CaptureRequest.CONTROL_AF_TRIGGER_START
                        else CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
                } else {
                    b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                }
            } else {
                b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                b.set(CaptureRequest.LENS_FOCUS_DISTANCE, state.focusDistance.coerceIn(0f, cap.minFocusDist))
            }
        } catch (e: Exception) { d("set FAILED af: ${e.message}") }

        // white balance
        try {
            if (state.wbAuto) {
                b.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
            } else {
                b.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF)
                val wb = Profiles.kelvinToGains(state.kelvin.toFloat())
                val tinted = floatArrayOf(
                    (wb[0] * state.tint).coerceIn(cap.gainsMin, cap.gainsMax),
                    1f,
                    1f,
                    (wb[3] / state.tint).coerceIn(cap.gainsMin, cap.gainsMax)
                )
                b.set(CaptureRequest.COLOR_CORRECTION_GAINS, android.hardware.camera2.params.RggbChannelVector(
                    tinted[0] * p.wbGains[0], tinted[1] * p.wbGains[1], tinted[2] * p.wbGains[2], tinted[3] * p.wbGains[3]))
            }
        } catch (e: Exception) { d("set FAILED wb: ${e.message}") }

        // profile: tonemap + saturation (some devices reject custom curves -> skip on error)
        if (lvl >= 3) {
            try {
                b.set(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_CONTRAST_CURVE)
                b.set(CaptureRequest.TONEMAP_CURVE,
                    android.hardware.camera2.params.TonemapCurve(p.red, p.green, p.blue))
            } catch (e: Exception) { d("set FAILED tonemap: ${e.message}") }
            try {
                b.set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
                b.set(CaptureRequest.COLOR_CORRECTION_TRANSFORM, Profiles.saturationMatrix(p.saturation))
            } catch (e: Exception) { d("set FAILED transform: ${e.message}") }
        }

        // fps
        if (lvl >= 1 && cap.fpsSupported.contains(state.fps)) {
            try { b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(state.fps, state.fps)) }
            catch (e: Exception) { d("set FAILED fps: ${e.message}") }
        }

        // stabilization — only for <=1080p VIDEO (EIS@4K is rejected by many devices and
        // historically caused the BLACK SCREEN bug)
        if (lvl >= 2 && cap.stabilizationSupported && state.stabilization && videoHeight <= 1080) {
            try {
                b.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                    CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON)
            } catch (e: Exception) { d("set FAILED stab: ${e.message}") }
        } else if (lvl < 2) {
            // leave stabilization untouched at low safe levels
        } else {
            try { b.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF) }
            catch (e: Exception) {}
        }

        // zoom via crop region
        try {
            if (state.zoom > 1.001f) {
                val z = state.zoom.coerceAtMost(cap.maxZoom)
                val w = (cap.activeArray.width() / z).toInt()
                val h = (cap.activeArray.height() / z).toInt()
                val cx = cap.activeArray.centerX()
                val cy = cap.activeArray.centerY()
                val rect = Rect(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
                b.set(CaptureRequest.SCALER_CROP_REGION, rect)
            } else {
                b.set(CaptureRequest.SCALER_CROP_REGION, cap.activeArray)
            }
        } catch (e: Exception) { d("set FAILED crop: ${e.message}") }
    }

    private fun startRepeating() {
        val s = session ?: return
        val cam = device ?: return
        try {
            // PREVIEW template for preview-only sessions (RECORD template can misbehave
            // on some devices when no encoder surface is attached)
            val template = if (recordSurface == null)
                CameraDevice.TEMPLATE_PREVIEW else CameraDevice.TEMPLATE_RECORD
            val b = cam.createCaptureRequest(template)
            b.addTarget(previewSurface!!)
            if (recordSurface != null) b.addTarget(recordSurface!!)
            histogramReader?.let { b.addTarget(it.surface) }
            applyState(b)
            previewBuilder = b
            s.setRepeatingRequest(b.build(), null, handler)
            d("repeating STARTED (${if (recordSurface == null) "preview" else "record"} template, safe=$safeLevel)")
            onPreviewStarted?.invoke()
        } catch (e: Exception) {
            d("repeating FAILED: ${e.message}")
            Log.e(TAG, "startRepeating", e)
        }
    }

    /** Push updated settings into the live repeating request (no session rebuild). */
    fun update() {
        val b = previewBuilder ?: return
        applyState(b)
        try { session?.setRepeatingRequest(b.build(), null, handler) } catch (e: Exception) {
            Log.e(TAG, "update", e)
        }
        onStateChanged(state)
    }

    /** Re-enable the histogram surface after a toggle off/on. */
    fun unblockHistogram() {
        histBlocked = false
    }

    fun setState(mutate: (CamState) -> Unit) {
        mutate(state)
        update()
    }

    /** Set AE/AF reticle regions in sensor space. triggerAf fires a one-shot AF run. */
    fun setRegions(ae: android.hardware.camera2.params.MeteringRectangle?,
                   af: android.hardware.camera2.params.MeteringRectangle?,
                   triggerAf: Boolean) {
        aeRegion = ae
        afRegion = af
        if (triggerAf && af != null) afTriggerPending = true
        update()
        // clear the AF trigger on a follow-up request (one-shot semantics)
        handler.postDelayed({
            if (afTriggerPending) {
                afTriggerPending = false
                update()
            }
        }, 700)
    }

    /** Map normalized preview coords to a sensor-space metering rect. */
    fun normToMeteringRect(nx: Float, ny: Float): android.hardware.camera2.params.MeteringRectangle? {
        val cap = capabilities ?: return null
        val sx = (nx * cap.activeArray.width() + cap.activeArray.left).toInt()
        val sy = (ny * cap.activeArray.height() + cap.activeArray.top).toInt()
        val rw = (cap.activeArray.width() * 0.12f).toInt()
        val rh = (cap.activeArray.height() * 0.12f).toInt()
        val r = Rect((sx - rw / 2).coerceAtLeast(cap.activeArray.left),
            (sy - rh / 2).coerceAtLeast(cap.activeArray.top),
            (sx + rw / 2).coerceAtMost(cap.activeArray.right),
            (sy + rh / 2).coerceAtMost(cap.activeArray.bottom))
        return android.hardware.camera2.params.MeteringRectangle(r, 1000)
    }

    fun tapFocus(x: Float, y: Float, w: Float, h: Float) {
        val b = previewBuilder ?: return
        val cap = capabilities ?: return
        // map preview coords to sensor coords (preview shows full active array when zoom 1)
        val sx = (x / w * cap.activeArray.width() + cap.activeArray.left).toInt()
        val sy = (y / h * cap.activeArray.height() + cap.activeArray.top).toInt()
        val rw = (cap.activeArray.width() * 0.12f).toInt()
        val rh = (cap.activeArray.height() * 0.12f).toInt()
        val r = Rect(sx - rw / 2, sy - rh / 2, sx + rw / 2, sy + rh / 2)
        b.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(android.hardware.camera2.params.MeteringRectangle(r, 1000)))
        b.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(android.hardware.camera2.params.MeteringRectangle(r, 1000)))
        if (state.afAuto) {
            b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
            b.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
            try { session?.setRepeatingRequest(b.build(), null, handler) } catch (_: Exception) {}
            b.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
        }
        try { session?.setRepeatingRequest(b.build(), null, handler) } catch (_: Exception) {}
    }

    // ---------------- recording ----------------

    /**
     * Start recording: rebuilds the session with the given encoder input surface
     * (MediaCodec EGL surface or MediaRecorder surface — same contract).
     */
    fun startRecording(surface: Surface, addToSession: Boolean = true,
                       onReady: () -> Unit, onError: (String) -> Unit) {
        val cam = device ?: return
        val s = session
        try {
            lock.acquire()
        } catch (_: InterruptedException) { return }
        try {
            if (recording) return
            val texSurface = previewSurface!!
            // GL-driven encoding: the renderer draws into the encoder surface via EGL,
            // so the camera must NOT target it directly.
            recordSurface = if (addToSession) surface else null

            if (Build.VERSION.SDK_INT >= 28) {
                val outputs = mutableListOf<OutputConfiguration>()
                outputs.add(OutputConfiguration(texSurface))
                histogramReader?.let { outputs.add(OutputConfiguration(it.surface)) }
                recordSurface?.let { rs ->
                    val oc = OutputConfiguration(rs)
                    if (Build.VERSION.SDK_INT >= 33 && hdrOn &&
                        capabilities?.dynamicRange?.contains(DynamicRangeProfiles.HLG10) == true) {
                        try { oc.setDynamicRangeProfile(DynamicRangeProfiles.HLG10) }
                        catch (e: Exception) { d("HDR profile set failed: ${e.message}") }
                    }
                    outputs.add(oc)
                }

                s?.close()
                cam.createCaptureSession(
                    SessionConfiguration(SessionConfiguration.SESSION_REGULAR,
                        outputs, { handler.post(it) }, object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(newS: CameraCaptureSession) {
                                onRecordSessionReady(cam, newS, texSurface, onReady, onError)
                            }
                            override fun onConfigureFailed(s: CameraCaptureSession) {
                                recording = false
                                onError("session failed")
                            }
                        }))
            } else {
                val surfaces = mutableListOf<Surface>(texSurface)
                recordSurface?.let { surfaces.add(it) }
                histogramReader?.let { surfaces.add(it.surface) }
                s?.close()
                cam.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(newS: CameraCaptureSession) {
                        onRecordSessionReady(cam, newS, texSurface, onReady, onError)
                    }
                    override fun onConfigureFailed(s: CameraCaptureSession) {
                        recording = false
                        onError("session failed")
                    }
                }, handler)
            }
        } catch (e: Exception) {
            recording = false
            onError(e.message ?: "record error")
        } finally {
            lock.release()
        }
    }

    private fun onRecordSessionReady(cam: CameraDevice, newS: CameraCaptureSession,
                                     texSurface: Surface,
                                     onReady: () -> Unit, onError: (String) -> Unit) {
        session = newS
        try {
            val b = cam.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
            b.addTarget(texSurface)
            recordSurface?.let { b.addTarget(it) }
            histogramReader?.let { b.addTarget(it.surface) }
            applyState(b)
            previewBuilder = b
            newS.setRepeatingRequest(b.build(), null, handler)
            recording = true
            d("record session configured (hdr=$hdrOn)")
            onReady()
        } catch (e: Exception) {
            Log.e(TAG, "rec start", e)
            recording = false
            onError(e.message ?: "record start failed")
        }
    }

    fun stopRecording(onStopped: () -> Unit) {
        recording = false
        try {
            session?.close()
            session = null
        } catch (_: Exception) {}
        recordSurface = null
        onStopped()
        createPreviewSession()
    }
}
