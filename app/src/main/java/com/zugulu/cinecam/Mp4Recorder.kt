package com.zugulu.cinecam

import android.content.ContentValues
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import android.view.Surface
import java.io.File
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * High-bitrate MP4 recorder — the FiLMiC-style path:
 *  - Video: MediaCodec HEVC/H264 (MAIN10 for HDR) fed by an EGL input surface
 *    (zero CPU-GPU copy, camera HAL writes directly into the encoder).
 *  - Audio: AudioRecord PCM 48kHz -> MediaCodec AAC -> same muxer.
 *  - Output: MediaStore (Movies/CineCamPro) on API 29+, app dir below.
 *
 * MainActivity falls back to MediaRecorder if prepare() throws.
 */
class Mp4Recorder(private val context: Context) {

    companion object {
        private const val TAG = "CineCamMux"
        private const val SAMPLE_RATE = 48_000
        private const val AAC_BITRATE = 256_000
        private const val CHANNEL_COUNT = 2
        private val tsFmt = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

        /** (hevc, hevcMain10) encoder availability. */
        fun probeCodecs(): Pair<Boolean, Boolean> {
            var hevc = false
            var main10 = false
            try {
                for (info in MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos) {
                    if (!info.isEncoder) continue
                    if (!info.supportedTypes.contains(MediaFormat.MIMETYPE_VIDEO_HEVC)) continue
                    hevc = true
                    val cap = info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_HEVC)
                    if (cap.profileLevels?.any {
                            it.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10
                        } == true) main10 = true
                }
            } catch (e: Exception) {
                Log.e(TAG, "probeCodecs", e)
            }
            return hevc to main10
        }
    }

    private var muxer: MediaMuxer? = null
    private var videoCodec: MediaCodec? = null
    private var audioCodec: MediaCodec? = null
    private var audioThread: AudioRecordThread? = null
    private var videoTrack = -1
    private var audioTrack = -1
    private var muxerStarted = false
    private var muxerStartDeadline = 0L
    private var inputSurface: Surface? = null
    private var outputUri: Uri? = null
    private var outputPath: String? = null
    private var fps = 30
    private var syntheticPtsUs = 0L
    private var sawEndOfStream = false

    var maxAmplitude: Int = 0
        private set

    /** Human-readable codec name chosen at prepare() ("HEVC10"/"HEVC"/"H264"). */
    var codecName = "H264"
        private set

    /**
     * Configure codecs + muxer. Returns the encoder input surface to add to the
     * capture session. Throws on any failure (caller falls back to MediaRecorder).
     */
    @Throws(Exception::class)
    fun prepare(size: Size, fps: Int, bitrate: Int, hdrMain10: Boolean,
                orientationHint: Int, onDebug: (String) -> Unit): Surface {
        this.fps = fps
        syntheticPtsUs = 0L
        sawEndOfStream = false
        muxerStarted = false
        videoTrack = -1
        audioTrack = -1
        maxAmplitude = 0

        val useHevc = if (hdrMain10) true else probeCodecs().first
        val mime = if (useHevc) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC

        val vfmt = MediaFormat.createVideoFormat(mime, size.width, size.height).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            if (mime == MediaFormat.MIMETYPE_VIDEO_HEVC) {
                setInteger(MediaFormat.KEY_PROFILE,
                    if (hdrMain10) MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10
                    else MediaCodecInfo.CodecProfileLevel.HEVCProfileMain)
            } else {
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh)
                setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel52)
            }
            if (hdrMain10 && Build.VERSION.SDK_INT >= 33) {
                setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020)
                setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_HLG)
            }
        }

        videoCodec = MediaCodec.createEncoderByType(mime)
        videoCodec!!.configure(vfmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = videoCodec!!.createInputSurface()
        videoCodec!!.start()
        codecName = when {
            hdrMain10 -> "HEVC10"
            useHevc -> "HEVC"
            else -> "H264"
        }
        onDebug("encoder: $mime${if (hdrMain10) " MAIN10" else ""} ${bitrate / 1_000_000}Mbps")

        // muxer + MediaStore output
        val ts = tsFmt.format(Date())
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, "CIN_$ts.mp4")
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/CineCamPro")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            outputUri = context.contentResolver.insert(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("MediaStore insert failed")
            val pfd = context.contentResolver.openFileDescriptor(outputUri!!, "rw")
                ?: throw IllegalStateException("openFileDescriptor failed")
            muxer = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        } else {
            val dir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
            val f = File(dir, "CIN_$ts.mp4")
            outputPath = f.absolutePath
            muxer = MediaMuxer(f.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        }
        muxer!!.setOrientationHint(orientationHint)

        // audio encoder (configured now, started on start())
        val afmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC,
            SAMPLE_RATE, CHANNEL_COUNT).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, AAC_BITRATE)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }
        audioCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        audioCodec!!.configure(afmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        audioCodec!!.start()

        muxerStartDeadline = System.currentTimeMillis() + 2500
        return inputSurface!!
    }

    private fun maybeStartMuxer() {
        if (muxerStarted) return
        val both = videoTrack != -1 && audioTrack != -1
        val videoOnlyTimeout = videoTrack != -1 &&
            System.currentTimeMillis() > muxerStartDeadline
        if (both || videoOnlyTimeout) {
            muxer!!.start()
            muxerStarted = true
        }
    }

    fun start() {
        if (!muxerStarted) {
            // give the drain loop a moment to add tracks, then force-start
            Thread {
                val deadline = System.currentTimeMillis() + 2000
                while (!muxerStarted && System.currentTimeMillis() < deadline) {
                    maybeStartMuxer()
                    if (muxerStarted) break
                    Thread.sleep(50)
                }
                maybeStartMuxer()
            }.start()
        }
        audioThread = AudioRecordThread()
        audioThread!!.start()
    }

    fun stop(onDone: () -> Unit) {
        try {
            videoCodec?.signalEndOfInputStream()
        } catch (_: Exception) {}
        audioThread?.let {
            it.stopRecording()
            try { it.join(3000) } catch (_: Exception) {}
        }
        // drain video until EOS or timeout
        val deadline = System.currentTimeMillis() + 2000
        while (!sawEndOfStream && System.currentTimeMillis() < deadline) {
            drainVideo(false)
            if (!sawEndOfStream) Thread.sleep(20)
        }
        try {
            if (muxerStarted) muxer?.stop()
        } catch (_: Exception) {}
        try { muxer?.release() } catch (_: Exception) {}
        try { videoCodec?.stop() } catch (_: Exception) {}
        try { videoCodec?.release() } catch (_: Exception) {}
        try { audioCodec?.stop() } catch (_: Exception) {}
        try { audioCodec?.release() } catch (_: Exception) {}
        try {
            if (Build.VERSION.SDK_INT >= 29 && outputUri != null) {
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.IS_PENDING, 0)
                }
                context.contentResolver.update(outputUri!!, values, null, null)
            }
        } catch (_: Exception) {}
        outputUri = null
        muxer = null
        videoCodec = null
        audioCodec = null
        inputSurface = null
        onDone()
    }

    fun abort() {
        try { videoCodec?.signalEndOfInputStream() } catch (_: Exception) {}
        audioThread?.let { it.stopRecording(); try { it.join(1000) } catch (_: Exception) {} }
        try { muxer?.release() } catch (_: Exception) {}
        try { videoCodec?.stop() } catch (_: Exception) {}
        try { videoCodec?.release() } catch (_: Exception) {}
        try { audioCodec?.stop() } catch (_: Exception) {}
        try { audioCodec?.release() } catch (_: Exception) {}
        try {
            if (Build.VERSION.SDK_INT >= 29 && outputUri != null) {
                context.contentResolver.delete(outputUri!!, null, null)
            } else if (outputPath != null) {
                File(outputPath!!).delete()
            }
        } catch (_: Exception) {}
        outputUri = null
        outputPath = null
        muxer = null
        videoCodec = null
        audioCodec = null
        inputSurface = null
    }

    /** Called from the camera session thread repeatedly during recording. */
    fun drainVideo(blocking: Boolean) {
        val codec = videoCodec ?: return
        val info = MediaCodec.BufferInfo()
        try {
            while (true) {
                val idx = codec.dequeueOutputBuffer(info, if (blocking) 10_000 else 0)
                if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) return
                if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    videoTrack = muxer!!.addTrack(codec.outputFormat)
                    maybeStartMuxer()
                    continue
                }
                if (idx < 0) return
                val buf = codec.getOutputBuffer(idx)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                    sawEndOfStream = true
                    codec.releaseOutputBuffer(idx, false)
                    return
                }
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                    codec.releaseOutputBuffer(idx, false)
                    continue
                }
                if (info.size > 0 && muxerStarted && videoTrack >= 0 && buf != null) {
                    var pts = info.presentationTimeUs
                    if (pts <= 0) {
                        pts = syntheticPtsUs
                        syntheticPtsUs += 1_000_000L / fps
                    }
                    info.presentationTimeUs = pts
                    muxer!!.writeSampleData(videoTrack, buf, info)
                }
                codec.releaseOutputBuffer(idx, false)
            }
        } catch (e: Exception) {
            Log.e(TAG, "drainVideo", e)
        }
    }

    private inner class AudioRecordThread : Thread("cinecam-audio") {
        @Volatile private var recording = true

        fun stopRecording() {
            recording = false
        }

        override fun run() {
            var rec: AudioRecord? = null
            try {
                // RECORD_AUDIO is a runtime permission checked at activity start
                val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
                @Suppress("MissingPermission")
                rec = AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(minBuf * 4, 8192))
                rec.startRecording()
                val buf = ByteArray(4096)
                val shorts = ShortArray(buf.size / 2)
                var pts = 0L
                val info = MediaCodec.BufferInfo()
                val codec = audioCodec!!

                fun drain() {
                    try {
                        while (true) {
                            val idx = codec.dequeueOutputBuffer(info, 0)
                            if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) return
                            if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                                if (muxer != null) {
                                    audioTrack = muxer!!.addTrack(codec.outputFormat)
                                    maybeStartMuxer()
                                }
                                continue
                            }
                            if (idx < 0) return
                            val b = codec.getOutputBuffer(idx)
                            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                                codec.releaseOutputBuffer(idx, false)
                                continue
                            }
                            if (info.size > 0 && muxerStarted && audioTrack >= 0 && b != null) {
                                muxer!!.writeSampleData(audioTrack, b, info)
                            }
                            codec.releaseOutputBuffer(idx, false)
                        }
                    } catch (_: Exception) {}
                }

                while (recording) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    // RMS peak for the vu-meter
                    ByteBuffer.wrap(buf, 0, n).asShortBuffer().get(shorts, 0, n / 2)
                    var peak = 0
                    for (i in 0 until n / 2) {
                        val v = kotlin.math.abs(shorts[i].toInt())
                        if (v > peak) peak = v
                    }
                    if (peak > maxAmplitude) maxAmplitude = peak
                    drain()
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val ib = codec.getInputBuffer(inIdx) ?: continue
                        ib.clear()
                        ib.put(buf, 0, n)
                        codec.queueInputBuffer(inIdx, 0, n, pts, 0)
                        pts += (n / 4).toLong() * 1_000_000L / SAMPLE_RATE
                    }
                }
                // EOS + final drain
                val inIdx = codec.dequeueInputBuffer(10_000)
                if (inIdx >= 0) {
                    codec.queueInputBuffer(inIdx, 0, 0, pts,
                        MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                }
                val deadline = System.currentTimeMillis() + 1000
                while (System.currentTimeMillis() < deadline) {
                    drain()
                    Thread.sleep(10)
                }
            } catch (e: Exception) {
                Log.e(TAG, "audio thread", e)
            } finally {
                try { rec?.stop() } catch (_: Exception) {}
                try { rec?.release() } catch (_: Exception) {}
            }
        }
    }
}
