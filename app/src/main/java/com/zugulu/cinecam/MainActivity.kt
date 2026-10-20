package com.zugulu.cinecam

import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.params.DynamicRangeProfiles
import android.hardware.display.DisplayManager
import android.media.MediaCodecList
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import android.view.Display
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

class MainActivity : Activity(), SensorEventListener {

    private lateinit var glView: AspectGLSurfaceView
    private lateinit var renderer: PreviewRenderer
    private lateinit var gridOverlay: GridOverlay
    private lateinit var histogramView: HistogramView
    private lateinit var levelView: LevelView
    private lateinit var audioMeter: AudioMeterView
    private lateinit var statusLabel: TextView
    private lateinit var timecodeLabel: TextView
    private lateinit var debugLabel: TextView
    private lateinit var resChip: TextView
    private lateinit var fpsChip: TextView
    private lateinit var bitrateChip: TextView
    private lateinit var profileRow: android.widget.LinearLayout
    private lateinit var manualPanel: View
    private lateinit var recBtn: TextView

    private lateinit var controller: CameraController

    private var mp4: Mp4Recorder? = null
    private var fallbackRec: MediaRecorder? = null
    private var pendingUri: Uri? = null
    private var recordingStart = 0L
    private var isRecording = false
    private var recorderCodec = "H264"

    private val main = Handler(Looper.getMainLooper())
    private lateinit var ticker: Runnable
    private lateinit var meterPoller: Runnable
    private lateinit var drainPoller: Runnable

    // sensor
    private var sensorManager: SensorManager? = null
    private var accel: Sensor? = null
    private var levelOn = false

    // sliders
    private lateinit var isoSeek: SeekBar
    private lateinit var shutSeek: SeekBar
    private lateinit var wbSeek: SeekBar
    private lateinit var focusSeek: SeekBar
    private lateinit var zoomSeek: SeekBar
    private lateinit var isoLabel: TextView
    private lateinit var shutLabel: TextView
    private lateinit var wbLabel: TextView
    private lateinit var focusLabel: TextView
    private lateinit var zoomLabel: TextView
    private var syncingUi = false

    // assists state
    private var peakOn = false
    private var zebraOn = false
    private var histOn = false
    private var falseColorOn = false
    private var retOn = false
    private var hdrOn = false
    private var lutIdx = 0   // 0=off, 1=profile LUT, 2=T&O, 3=NOIR, 4=PASTL
    private var oriIdx = 0   // 0..7 rotation variants, 8 = raw diagnostic

    private lateinit var reticleView: ReticleView

    private var sizeIdx = 0
    private var fpsIdx = 0
    private val fpsChoices = mutableListOf<Int>()

    private val fmtHms = SimpleDateFormat("HH:mm:ss", Locale.US)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()

        glView = findViewById(R.id.glPreview)
        gridOverlay = findViewById(R.id.gridOverlay)
        histogramView = findViewById(R.id.histogramView)
        levelView = findViewById(R.id.levelView)
        audioMeter = findViewById(R.id.audioMeter)
        reticleView = findViewById(R.id.reticleView)
        statusLabel = findViewById(R.id.statusLabel)
        timecodeLabel = findViewById(R.id.timecodeLabel)
        debugLabel = findViewById(R.id.debugLabel)
        resChip = findViewById(R.id.resChip)
        fpsChip = findViewById(R.id.fpsChip)
        bitrateChip = findViewById(R.id.bitrateChip)
        profileRow = findViewById(R.id.profileRow)
        manualPanel = findViewById(R.id.manualPanel)
        recBtn = findViewById(R.id.recBtn)

        isoSeek = findViewById(R.id.isoSeek)
        shutSeek = findViewById(R.id.shutSeek)
        wbSeek = findViewById(R.id.wbSeek)
        focusSeek = findViewById(R.id.focusSeek)
        zoomSeek = findViewById(R.id.zoomSeek)
        isoLabel = findViewById(R.id.isoLabel)
        shutLabel = findViewById(R.id.shutLabel)
        wbLabel = findViewById(R.id.wbLabel)
        focusLabel = findViewById(R.id.focusLabel)
        zoomLabel = findViewById(R.id.zoomLabel)

        glView.setEGLContextClientVersion(2)
        glView.preserveEGLContextOnPause = true

        renderer = PreviewRenderer(this)
        controller = CameraController(this) { st -> onStateChanged(st) }
        controller.onCapabilities = {
            runOnUiThread { syncAllRanges() }
        }
        // sensor self-test must run AFTER the repeating request actually starts
        // (onCapabilities fires before the session exists — state changes were no-ops!)
        controller.onPreviewStarted = {
            runOnUiThread {
                if (!sensorTestRan) {
                    sensorTestRan = true
                    sensorSelfTest()
                }
            }
        }
        controller.onSessionError = { msg ->
            runOnUiThread {
                Toast.makeText(this, "Session failed: $msg", Toast.LENGTH_LONG).show()
            }
        }
        controller.debugLog = { msg -> appendDebug(msg) }
        // assign callbacks BEFORE setRenderer — the GL thread may run onSurfaceCreated
        // before the rest of onCreate finishes (missed-callback race = no camera open)
        renderer.onDebug = { msg -> appendDebug(msg) }
        renderer.onTextureReady = { st, oldSt ->
            runOnUiThread {
                appendDebug("texture ready — rewiring camera")
                controller.setTexture(st)
                controller.close()          // stop streaming to the old surface first
                oldSt?.release()            // now safe to free
                startCamera()               // full reopen on the new texture
            }
        }
        glView.setRenderer(renderer)
        glView.renderMode = android.opengl.GLSurfaceView.RENDERMODE_CONTINUOUSLY

        debugLabel.setOnClickListener {
            debugLabel.maxLines = if (debugLabel.maxLines == 1) 8 else 1
        }

        buildProfileChips()
        wireAssists()
        wireSliders()
        wireChips()
        wireGestures()
        wireRecording()

        oriIdx = getSharedPreferences("cinecam", MODE_PRIVATE).getInt("oriIdx_v23", 0)
        applyOri()

        ticker = object : Runnable {
            override fun run() {
                if (isRecording) {
                    val elapsed = System.currentTimeMillis() - recordingStart
                    val fps = controller.state.fps
                    val frames = (elapsed * fps / 1000).toInt()
                    timecodeLabel.text = String.format(Locale.US, "%02d:%02d:%02d:%02d",
                        frames / fps / 3600, frames / fps / 60 % 60, frames / fps % 60, frames % fps)
                    main.postDelayed(this, 33)
                }
            }
        }
        meterPoller = object : Runnable {
            override fun run() {
                if (isRecording) {
                    try {
                        val amp = mp4?.maxAmplitude ?: fallbackRec?.maxAmplitude ?: 0
                        val db = if (amp <= 0) -60f else (20 * Math.log10(amp / 32767.0)).toFloat()
                        audioMeter.level = (db + 60f) / 60f
                    } catch (_: Exception) {}
                    main.postDelayed(this, 100)
                }
            }
        }
        drainPoller = object : Runnable {
            override fun run() {
                if (isRecording) {
                    mp4?.drainVideo(false)
                    main.postDelayed(this, 40)
                }
            }
        }

        requestPermissionsIfNeeded()
    }

    private fun appendDebug(msg: String) {
        runOnUiThread {
            val lines = (debugLabel.text.toString() + "\n" + msg).lines().takeLast(8)
            debugLabel.text = lines.joinToString("\n")
        }
    }

    private var sensorTestRunning = false
    private var sensorTestRan = false
    private var lastTest = ""

    /** Force max ISO + 1/10s shutter for 8 seconds — a live sensor always shows
     *  something then, even in a dark room. Results go to the STATUS LABEL so they
     *  can't scroll out of the debug window. Then restore auto. */
    private fun sensorSelfTest() {
        if (sensorTestRunning) return
        sensorTestRunning = true
        val cap = controller.capabilities ?: run { sensorTestRunning = false; return }
        appendDebug("SENSOR TEST: ISO ${cap.isoMax} 1/10s")
        controller.setState { s ->
            s.aeAuto = false
            s.iso = cap.isoMax
            s.shutterNs = 100_000_000L
        }
        val testPoll = object : Runnable {
            override fun run() {
                if (!sensorTestRunning) return
                val px = renderer.lastCenterRgb
                statusLabel.text = "TEST ${px[0]},${px[1]},${px[2]} — ISO ${cap.isoMax} 1/10s"
                main.postDelayed(this, 500)
            }
        }
        main.postDelayed(testPoll, 500)
        main.postDelayed({
            sensorTestRunning = false
            controller.setState { s -> s.aeAuto = true }
            val px = renderer.lastCenterRgb
            lastTest = "test=${px[0]},${px[1]},${px[2]}"
            appendDebug("SENSOR TEST done — $lastTest (iso=${cap.isoMax},1/10s)")
            updateStatusLabel()
        }, 8000)
    }

    private fun hideSystemBars() {
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }

    private fun requestPermissionsIfNeeded() {
        val missing = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
            missing.add(Manifest.permission.CAMERA)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            missing.add(Manifest.permission.RECORD_AUDIO)
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), 100)
        } else {
            startCamera()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            Toast.makeText(this, "Camera permission required", Toast.LENGTH_LONG).show()
        }
    }

    private fun startCamera() {
        if (controller.capabilities != null) return
        val cams = controller.enumerate()
        if (cams.isEmpty()) {
            Toast.makeText(this, "No back camera found", Toast.LENGTH_LONG).show()
            return
        }
        appendDebug("startCamera: ${cams.size} back cam(s)")
        controller.open()
    }

    private fun onStateChanged(st: CamState) {
        if (syncingUi) return
        runOnUiThread { syncUiFromState() }
    }

    private fun syncUiFromState() {
        val st = controller.state
        val cap = controller.capabilities ?: return
        syncingUi = true
        isoSeek.max = cap.isoMax - cap.isoMin
        isoSeek.progress = st.iso - cap.isoMin
        isoLabel.text = "ISO ${st.iso}"
        (findViewById<TextView>(R.id.isoAuto)).setBackgroundResource(
            if (st.aeAuto) R.drawable.auto_btn_bg else R.drawable.auto_btn_off)

        val t = shutterToSlider(st.shutterNs, cap)
        shutSeek.progress = t
        shutLabel.text = shutterLabel(st.shutterNs)
        (findViewById<TextView>(R.id.shutAuto)).setBackgroundResource(
            if (st.shutterAuto) R.drawable.auto_btn_bg else R.drawable.auto_btn_off)

        wbSeek.progress = st.kelvin - 2500
        wbLabel.text = "${st.kelvin}K"
        (findViewById<TextView>(R.id.wbAuto)).setBackgroundResource(
            if (st.wbAuto) R.drawable.auto_btn_bg else R.drawable.auto_btn_off)

        val f = if (cap.minFocusDist > 0f)
            ((1f - st.focusDistance / cap.minFocusDist) * 1000).toInt() else 0
        focusSeek.progress = f
        focusLabel.text = focusLabel(st.focusDistance)
        (findViewById<TextView>(R.id.focusAuto)).setBackgroundResource(
            if (st.afAuto) R.drawable.auto_btn_bg else R.drawable.auto_btn_off)

        zoomSeek.progress = ((st.zoom - 1f) * 1000).toInt().coerceIn(0, zoomSeek.max)
        zoomLabel.text = String.format(Locale.US, "%.1fx", st.zoom)

        // profile chips highlight
        for (i in 0 until profileRow.childCount) {
            val tv = profileRow.getChildAt(i) as TextView
            val selected = i == st.profileIdx
            tv.setTextColor(if (selected) Color.rgb(20, 20, 24) else Color.WHITE)
            tv.setBackgroundColor(if (selected) Color.rgb(255, 200, 77) else Color.argb(30, 255, 255, 255))
        }

        updateStatusLabel()
        syncingUi = false
    }

    private fun updateStatusLabel() {
        val st = controller.state
        val cap = controller.capabilities
        val size = cap?.sizes?.getOrNull(sizeIdx)
        val resTxt = resLabel(size)
        val prof = Profiles.ALL[st.profileIdx].short
        val enc = recorderCodec
        val mbps = bitrateFor(size) / 1_000_000
        val stab = if (cap?.stabilizationSupported == true && st.stabilization &&
            (size?.height ?: 0) <= 1080) " STAB" else ""
        val hdr = if (hdrOn && controller.hdrOn) " HDR" else ""
        statusLabel.text = "$resTxt ${st.fps} • $prof • $enc ${mbps}M$stab$hdr"
        if (lastTest.isNotEmpty()) statusLabel.text = "${statusLabel.text} | $lastTest"
    }

    // ---------------- UI wiring ----------------

    private fun buildProfileChips() {
        profileRow.removeAllViews()
        for (i in Profiles.ALL.indices) {
            val tv = TextView(this)
            tv.text = Profiles.ALL[i].short
            tv.textSize = 11f
            tv.setTextColor(Color.WHITE)
            tv.setBackgroundColor(Color.argb(30, 255, 255, 255))
            tv.setPadding(22, 18, 22, 18)
            tv.setOnClickListener {
                controller.setState { s -> s.profileIdx = i }
                if (lutIdx == 1) applyLut()   // profile LUT follows the selected profile
            }
            profileRow.addView(tv)
        }
        syncUiFromState()
    }

    private fun setAssistBtn(viewId: Int, active: Boolean) {
        val tv = findViewById<TextView>(viewId)
        tv.setTextColor(if (active) Color.rgb(20, 20, 24) else Color.argb(153, 255, 255, 255))
        tv.setBackgroundColor(if (active) Color.rgb(255, 200, 77) else Color.argb(30, 255, 255, 255))
    }

    private fun wireAssists() {
        findViewById<TextView>(R.id.gridBtn).setOnClickListener {
            val show = gridOverlay.visibility != View.VISIBLE
            gridOverlay.visibility = if (show) View.VISIBLE else View.GONE
            setAssistBtn(R.id.gridBtn, show)
        }
        findViewById<TextView>(R.id.peakBtn).setOnClickListener {
            peakOn = !peakOn
            renderer.peakOn = peakOn
            setAssistBtn(R.id.peakBtn, peakOn)
        }
        findViewById<TextView>(R.id.zebraBtn).setOnClickListener {
            zebraOn = !zebraOn
            renderer.zebraOn = zebraOn
            setAssistBtn(R.id.zebraBtn, zebraOn)
        }
        findViewById<TextView>(R.id.histBtn).setOnClickListener {
            histOn = !histOn
            histogramView.visibility = if (histOn) View.VISIBLE else View.GONE
            setAssistBtn(R.id.histBtn, histOn)
            controller.setHistogramListener(if (histOn) { bins ->
                runOnUiThread { histogramView.update(bins) }
            } else null)
            if (histOn) controller.unblockHistogram()
            if (controller.capabilities != null) controller.createPreviewSession()
        }
        findViewById<TextView>(R.id.lvlBtn).setOnClickListener {
            levelOn = !levelOn
            levelView.visibility = if (levelOn) View.VISIBLE else View.GONE
            setAssistBtn(R.id.lvlBtn, levelOn)
            toggleAccelerometer(levelOn)
        }
        findViewById<TextView>(R.id.stabBtn).setOnClickListener {
            val v = !controller.state.stabilization
            controller.setState { s -> s.stabilization = v }
            setAssistBtn(R.id.stabBtn, v)
        }
        findViewById<TextView>(R.id.camBtn).setOnClickListener {
            if (isRecording) return@setOnClickListener
            controller.switchCamera()
            syncAllRanges()
        }
        findViewById<TextView>(R.id.manualBtn).setOnClickListener {
            val show = manualPanel.visibility != View.VISIBLE
            manualPanel.visibility = if (show) View.VISIBLE else View.GONE
            setAssistBtn(R.id.manualBtn, show)
        }
        findViewById<TextView>(R.id.testBtn).setOnClickListener {
            sensorSelfTest()
        }
        findViewById<TextView>(R.id.safeBtn).setOnClickListener {
            controller.safeLevel = (controller.safeLevel + 1) % 4
            controller.update()
            val lvl = controller.safeLevel
            findViewById<TextView>(R.id.safeBtn).text = "SAFE$lvl"
            setAssistBtn(R.id.safeBtn, lvl != 0)
            appendDebug("safeLevel=$lvl " + when (lvl) {
                0 -> "(AE only)"
                1 -> "(+fps)"
                2 -> "(+stab)"
                else -> "(full profiles)"
            })
        }
        findViewById<TextView>(R.id.oriBtn).setOnClickListener {
            oriIdx = (oriIdx + 1) % 9
            applyOri()
        }
        findViewById<TextView>(R.id.fcBtn).setOnClickListener {
            falseColorOn = !falseColorOn
            renderer.falseColorOn = falseColorOn
            setAssistBtn(R.id.fcBtn, falseColorOn)
        }
        findViewById<TextView>(R.id.lutBtn).setOnClickListener {
            lutIdx = (lutIdx + 1) % 5
            applyLut()
            setAssistBtn(R.id.lutBtn, lutIdx != 0)
        }
        findViewById<TextView>(R.id.retBtn).setOnClickListener {
            retOn = !retOn
            reticleView.visibility = if (retOn) View.VISIBLE else View.GONE
            setAssistBtn(R.id.retBtn, retOn)
        }
        findViewById<TextView>(R.id.hdrBtn).setOnClickListener {
            hdrOn = !hdrOn
            controller.hdrOn = hdrOn
            setAssistBtn(R.id.hdrBtn, hdrOn)
            updateStatusLabel()
        }
        reticleView.onChanged = { ax, ay, fx, fy, split ->
            if (!isRecording) {
                controller.setRegions(
                    controller.normToMeteringRect(ax, ay),
                    controller.normToMeteringRect(fx, fy),
                    true)
            }
        }
    }

    private fun applyOri() {
        // idx0 = auto-correct (pure rotation, no flip) — verified math:
        // scene top -> screen top, scene right -> screen right (back camera)
        // variants 1..7 cover deviant HALs, 8 = RAW diagnostic
        val f = arrayOf(false, true, false, true, false, true, false, true, false)[oriIdx]
        val r = arrayOf(0f, 0f, 90f, 90f, 180f, 180f, 270f, 270f, 0f)[oriIdx]
        renderer.rawMode = oriIdx == 8
        renderer.flipMode = f
        renderer.rotOffset = r
        findViewById<TextView>(R.id.oriBtn).text = "ORI${oriIdx + 1}"
        setAssistBtn(R.id.oriBtn, oriIdx != 0)
        appendDebug("ori=${oriIdx + 1} flip=$f rot=$r${if (oriIdx == 8) " RAW" else ""}")
        getSharedPreferences("cinecam", MODE_PRIVATE).edit()
            .putInt("oriIdx_v23", oriIdx).apply()
    }

    private fun applyLut() {
        val st = controller.state
        when (lutIdx) {
            0 -> { renderer.lutOn = false }
            1 -> {
                renderer.setLut(Profiles.buildLut(Profiles.ALL[st.profileIdx]))
                renderer.lutOn = true
            }
            2 -> { renderer.setLut(Profiles.buildLut(Profiles.ALL[3])); renderer.lutOn = true } // T&O
            3 -> { renderer.setLut(Profiles.buildLut(Profiles.ALL[4])); renderer.lutOn = true } // NOIR
            4 -> { renderer.setLut(Profiles.buildLut(Profiles.ALL[5])); renderer.lutOn = true } // PASTL
        }
        val label = arrayOf("LUT:OFF", "LUT:PROF", "LUT:T&O", "LUT:NOIR", "LUT:PASTL")[lutIdx]
        findViewById<TextView>(R.id.lutBtn).text = label
    }

    private fun wireSliders() {
        isoSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                if (!fromUser || syncingUi) return
                val cap = controller.capabilities ?: return
                val iso = (p + cap.isoMin) / 50 * 50
                controller.setState { s ->
                    s.aeAuto = false
                    s.iso = iso
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {
                if (!controller.state.aeAuto) return
                val cap = controller.capabilities ?: return
                sb?.max = cap.isoMax - cap.isoMin
            }
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        shutSeek.max = 1000
        shutSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                if (!fromUser || syncingUi) return
                val cap = controller.capabilities ?: return
                controller.setState { s ->
                    s.shutterAuto = false
                    s.shutterNs = sliderToShutter(p, cap)
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        wbSeek.max = 9000 - 2500
        wbSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                if (!fromUser || syncingUi) return
                controller.setState { s ->
                    s.wbAuto = false
                    s.kelvin = (p + 2500) / 50 * 50
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        focusSeek.max = 1000
        focusSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                if (!fromUser || syncingUi) return
                val cap = controller.capabilities ?: return
                controller.setState { s ->
                    s.afAuto = false
                    s.focusDistance = cap.minFocusDist * (1f - p / 1000f)
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        zoomSeek.max = 7000
        zoomSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                if (!fromUser || syncingUi) return
                controller.setState { s -> s.zoom = 1f + p / 1000f }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        findViewById<TextView>(R.id.isoAuto).setOnClickListener {
            controller.setState { s -> s.aeAuto = true }
        }
        findViewById<TextView>(R.id.shutAuto).setOnClickListener {
            controller.setState { s -> s.shutterAuto = true }
        }
        findViewById<TextView>(R.id.wbAuto).setOnClickListener {
            controller.setState { s -> s.wbAuto = true }
        }
        findViewById<TextView>(R.id.focusAuto).setOnClickListener {
            controller.setState { s -> s.afAuto = true }
        }
    }

    private fun wireChips() {
        resChip.setOnClickListener {
            val cap = controller.capabilities ?: return@setOnClickListener
            sizeIdx = (sizeIdx + 1) % cap.sizes.size
            val sz = cap.sizes[sizeIdx]
            controller.videoHeight = sz.height   // recording size only — preview stays safe
            glView.setAspect(sz.width, sz.height)
            resChip.text = resLabel(sz)
            updateStatusLabel()
        }
        fpsChip.setOnClickListener {
            if (fpsChoices.isEmpty()) return@setOnClickListener
            fpsIdx = (fpsIdx + 1) % fpsChoices.size
            controller.setState { s -> s.fps = fpsChoices[fpsIdx] }
            fpsChip.text = fpsChoices[fpsIdx].toString()
        }
        bitrateChip.setOnClickListener {
            val st = controller.state
            st.bitrateIdx = (st.bitrateIdx + 1) % 3
            bitrateChip.text = arrayOf("STD", "HIGH", "ULTRA")[st.bitrateIdx]
            updateStatusLabel()
        }
    }

    private fun wireGestures() {
        val tap = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                if (!isRecording) {
                    val c = findViewById<View>(R.id.previewContainer)
                    controller.tapFocus(e.x, e.y, c.width.toFloat(), c.height.toFloat())
                }
                return true
            }
        })
        val scale = ScaleGestureDetector(this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    val f = detector.scaleFactor
                    controller.setState { s ->
                        s.zoom = (s.zoom * f).coerceIn(1f, controller.capabilities?.maxZoom ?: 4f)
                    }
                    return true
                }
            })
        findViewById<View>(R.id.previewContainer).setOnTouchListener { v, ev ->
            scale.onTouchEvent(ev)
            tap.onTouchEvent(ev)
            true
        }
    }

    private fun wireRecording() {
        recBtn.setOnClickListener {
            if (isRecording) stopRecording() else startRecording()
        }
    }

    // ---------------- recording ----------------

    private fun bitrateFor(size: Size?): Int {
        val h = size?.height ?: 1080
        return when (controller.state.bitrateIdx) {
            0 -> if (h >= 2000) 60_000_000 else if (h >= 1000) 24_000_000 else 12_000_000
            2 -> if (h >= 2000) 160_000_000 else if (h >= 1000) 60_000_000 else 28_000_000
            else -> if (h >= 2000) 100_000_000 else if (h >= 1000) 40_000_000 else 18_000_000
        }
    }

    private fun prepareRecorder(size: Size): MediaRecorder? {
        var rec: MediaRecorder? = null
        try {
            rec = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else MediaRecorder()
            rec.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            rec.setAudioSource(MediaRecorder.AudioSource.MIC)
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            var codecSet = false
            try {
                rec.setVideoEncoder(MediaRecorder.VideoEncoder.HEVC)
                codecSet = true
                recorderCodec = "HEVC"
            } catch (_: Exception) {
                rec.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                recorderCodec = "H264"
            }
            rec.setVideoSize(size.width, size.height)
            rec.setVideoFrameRate(controller.state.fps)
            rec.setVideoEncodingBitRate(bitrateFor(size))
            rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            rec.setAudioEncodingBitRate(256_000)
            rec.setAudioSamplingRate(48_000)
            rec.setOrientationHint(controller.totalRotation)

            val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, "CIN_$ts.mp4")
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH,
                        Environment.DIRECTORY_MOVIES + "/CineCamPro")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
                val uri = contentResolver.insert(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                pendingUri = uri
                val pfd = contentResolver.openFileDescriptor(uri!!, "rw")
                rec.setOutputFile(pfd!!.fileDescriptor)
            } else {
                val dir = getExternalFilesDir(Environment.DIRECTORY_MOVIES)
                val f = File(dir, "CIN_$ts.mp4")
                rec.setOutputFile(f.absolutePath)
            }
            rec.prepare()
        } catch (e: Exception) {
            Log.e("CineCam", "prepareRecorder", e)
            try { rec?.release() } catch (_: Exception) {}
            pendingUri = null
            // fallback: safe 1080p30 H264
            return try {
                rec = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else MediaRecorder()
                rec.setVideoSource(MediaRecorder.VideoSource.SURFACE)
                rec.setAudioSource(MediaRecorder.AudioSource.MIC)
                rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                rec.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                recorderCodec = "H264"
                rec.setVideoSize(1920, 1080)
                rec.setVideoFrameRate(30)
                rec.setVideoEncodingBitRate(30_000_000)
                rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                rec.setAudioEncodingBitRate(256_000)
                rec.setAudioSamplingRate(48_000)
                rec.setOrientationHint(controller.totalRotation)
                val ts2 = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                if (Build.VERSION.SDK_INT >= 29) {
                    val values = ContentValues().apply {
                        put(MediaStore.Video.Media.DISPLAY_NAME, "CIN_$ts2.mp4")
                        put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                        put(MediaStore.Video.Media.RELATIVE_PATH,
                            Environment.DIRECTORY_MOVIES + "/CineCamPro")
                        put(MediaStore.Video.Media.IS_PENDING, 1)
                    }
                    val uri = contentResolver.insert(
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                    pendingUri = uri
                    val pfd = contentResolver.openFileDescriptor(uri!!, "rw")
                    rec.setOutputFile(pfd!!.fileDescriptor)
                } else {
                    val dir = getExternalFilesDir(Environment.DIRECTORY_MOVIES)
                    rec.setOutputFile(File(dir, "CIN_$ts2.mp4").absolutePath)
                }
                rec.prepare()
                rec
            } catch (e2: Exception) {
                Log.e("CineCam", "fallback prepare failed", e2)
                try { rec?.release() } catch (_: Exception) {}
                null
            }
        }
        return rec
    }

    private fun startRecording() {
        val cap = controller.capabilities ?: return
        val size = cap.sizes.getOrElse(sizeIdx) { Size(1920, 1080) }
        val bitrate = bitrateFor(size)
        val hdr = hdrOn && Build.VERSION.SDK_INT >= 33 &&
            cap.dynamicRange.contains(DynamicRangeProfiles.HLG10)

        var surface: Surface? = null
        // Path 1: MediaCodec + MediaMuxer (zero-copy, HDR-capable, extreme bitrates)
        try {
            val r = Mp4Recorder(this)
            surface = r.prepare(size, controller.state.fps, bitrate, hdr,
                controller.totalRotation) { m -> appendDebug(m) }
            mp4 = r
            recorderCodec = r.codecName
        } catch (e: Exception) {
            appendDebug("mp4 prepare failed: ${e.message}")
            // Path 2: MediaRecorder fallback (SDR only)
            try {
                val rec = prepareRecorder(size)
                fallbackRec = rec
                surface = rec?.surface
            } catch (e2: Exception) {
                appendDebug("recorder fallback failed: ${e2.message}")
            }
        }
        val surf = surface
        if (surf == null) {
            Toast.makeText(this, "Recorder init failed", Toast.LENGTH_SHORT).show()
            mp4?.abort(); mp4 = null
            fallbackRec?.release(); fallbackRec = null
            return
        }

        controller.startRecording(surf,
            onReady = {
                runOnUiThread {
                    isRecording = true
                    recordingStart = System.currentTimeMillis()
                    recBtn.text = "■"
                    recBtn.setTextColor(Color.WHITE)
                    timecodeLabel.setTextColor(Color.rgb(255, 80, 80))
                    timecodeLabel.text = "00:00:00:00"
                    try { mp4?.start() } catch (e: Exception) {
                        appendDebug("mp4 start failed: ${e.message}")
                    }
                    try { fallbackRec?.start() } catch (e: Exception) {
                        appendDebug("recorder start failed: ${e.message}")
                    }
                    main.post(ticker)
                    main.post(meterPoller)
                    main.post(drainPoller)
                    updateStatusLabel()
                }
            },
            onError = { msg ->
                runOnUiThread {
                    Toast.makeText(this, "Record error: $msg", Toast.LENGTH_SHORT).show()
                    mp4?.abort(); mp4 = null
                    try { fallbackRec?.release() } catch (_: Exception) {}
                    fallbackRec = null
                    finalizeMediaStore()
                }
            })
    }

    private fun stopRecording() {
        isRecording = false
        main.removeCallbacks(ticker)
        main.removeCallbacks(meterPoller)
        main.removeCallbacks(drainPoller)
        recBtn.text = "●"
        recBtn.setTextColor(Color.rgb(255, 59, 48))
        timecodeLabel.setTextColor(Color.WHITE)
        val m = mp4
        val fb = fallbackRec
        mp4 = null
        fallbackRec = null
        try { fb?.stop() } catch (e: Exception) { Log.e("CineCam", "recorder.stop", e) }
        try { fb?.release() } catch (_: Exception) {}
        finalizeMediaStore()
        controller.stopRecording {
            runOnUiThread {
                if (m != null) {
                    // session is closed: signal EOS, drain, finalize the MP4
                    Thread {
                        m.stop { runOnUiThread { updateStatusLabel() } }
                    }.start()
                } else {
                    updateStatusLabel()
                }
                Toast.makeText(this, "Saved to Movies/CineCamPro", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun finalizeMediaStore() {
        try {
            if (Build.VERSION.SDK_INT >= 29 && pendingUri != null) {
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.IS_PENDING, 0)
                }
                contentResolver.update(pendingUri!!, values, null, null)
            }
        } catch (_: Exception) {}
        pendingUri = null
    }

    // ---------------- helpers ----------------

    private fun shutterToSlider(ns: Long, cap: Capabilities): Int {
        val lo = cap.expoMinNs.toDouble()
        val hi = cap.expoMaxNs.toDouble()
        val v = ns.toDouble().coerceIn(lo, hi)
        val t = ln(v / lo) / ln(hi / lo)
        return (t * 1000).toInt().coerceIn(0, 1000)
    }

    private fun sliderToShutter(p: Int, cap: Capabilities): Long {
        val lo = cap.expoMinNs.toDouble()
        val hi = cap.expoMaxNs.toDouble()
        val t = p / 1000.0
        val ns = lo * (hi / lo).pow(t)
        return ns.toLong().coerceIn(cap.expoMinNs, cap.expoMaxNs)
    }

    private fun shutterLabel(ns: Long): String {
        if (ns <= 0) return "1/50"
        return if (ns >= 1_000_000_000L)
            String.format(Locale.US, "%.1fs", ns / 1e9)
        else "1/${(1_000_000_000.0 / ns).roundToInt()}"
    }

    private fun focusLabel(diop: Float): String {
        if (diop <= 0.01f) return "∞"
        val cm = 100f / diop
        return if (cm >= 100) "${(cm / 100).roundToInt()}m" else "${cm.roundToInt()}cm"
    }

    private fun resLabel(sz: Size?): String {
        if (sz == null) return "4K"
        return when {
            sz.height >= 3000 -> "8K"
            sz.height >= 2000 -> "4K"
            sz.height >= 1000 -> "1080P"
            sz.height >= 700 -> "720P"
            else -> "SD"
        }
    }

    private fun syncAllRanges() {
        val cap = controller.capabilities ?: return
        val st = controller.state
        isoSeek.max = cap.isoMax - cap.isoMin
        shutSeek.max = 1000
        wbSeek.max = 9000 - 2500
        focusSeek.max = 1000
        zoomSeek.max = ((cap.maxZoom - 1f) * 1000).toInt()

        fpsChoices.clear()
        for (f in listOf(24, 25, 30, 48, 50, 60)) {
            if (cap.fpsSupported.contains(f)) fpsChoices.add(f)
        }
        if (fpsChoices.isEmpty()) fpsChoices.add(30)
        if (!fpsChoices.contains(st.fps)) {
            st.fps = fpsChoices[0]
            fpsIdx = 0
        }
        fpsChip.text = st.fps.toString()
        if (st.iso < cap.isoMin || st.iso > cap.isoMax) st.iso = cap.isoMin
        sizeIdx = 0
        val sz = cap.sizes.first()
        controller.videoHeight = sz.height
        glView.setAspect(sz.width, sz.height)
        resChip.text = resLabel(sz)
        findViewById<TextView>(R.id.camBtn).visibility =
            if (controller.enumerate().size > 1) View.VISIBLE else View.GONE
        // HDR only when the device advertises HLG10 (Android 13+)
        val hdrSupported = Build.VERSION.SDK_INT >= 33 &&
            cap.dynamicRange.contains(DynamicRangeProfiles.HLG10)
        findViewById<TextView>(R.id.hdrBtn).visibility =
            if (hdrSupported) View.VISIBLE else View.GONE
        if (!hdrSupported) { hdrOn = false; controller.hdrOn = false }
        setAssistBtn(R.id.stabBtn, st.stabilization)
        syncUiFromState()
    }

    // ---------------- accelerometer ----------------

    private fun toggleAccelerometer(on: Boolean) {
        if (on) {
            sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
            accel = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            sensorManager?.registerListener(this, accel, SensorManager.SENSOR_DELAY_UI)
        } else {
            sensorManager?.unregisterListener(this)
            sensorManager = null
        }
    }

    private var axF = 0f; private var ayF = 0f

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
        axF = axF * 0.9f + event.values[0] * 0.1f
        ayF = ayF * 0.9f + event.values[1] * 0.1f
        val roll = Math.toDegrees(atan2(-axF.toDouble(), ayF.toDouble())).toFloat()
        levelView.setRoll(roll)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // ---------------- rotation & lifecycle ----------------

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) { updateRotation() }
    }

    private fun updateRotation() {
        val rot = windowManager.defaultDisplay.rotation
        val deg = when (rot) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        controller.deviceRotation = deg
        renderer.rotationDegrees = controller.totalRotation.toFloat()
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        glView.onResume()
        (getSystemService(Context.DISPLAY_SERVICE) as DisplayManager)
            .registerDisplayListener(displayListener, main)
        updateRotation()
        if (controller.capabilities != null) {
            controller.open()
        } else {
            startCamera()
        }
        // belt & braces: if nothing opened within 800ms (thread-ordering races), retry
        main.postDelayed({
            if (controller.capabilities == null) {
                appendDebug("retry open (no caps yet)")
                startCamera()
            }
        }, 800)
    }

    override fun onPause() {
        super.onPause()
        if (isRecording) stopRecording()
        (getSystemService(Context.DISPLAY_SERVICE) as DisplayManager)
            .unregisterDisplayListener(displayListener)
        toggleAccelerometer(false)
        levelOn = false
        controller.close()
        glView.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        toggleAccelerometer(false)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateRotation()
        hideSystemBars()
    }
}
