package com.userexec.soneme.recorder

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AutomaticGainControl
import android.os.Process
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

object AudioCaptureConfig {
    const val SAMPLE_RATE = 48_000
    const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
    const val ENCODING = AudioFormat.ENCODING_PCM_16BIT

    @SuppressLint("MissingPermission")
    fun create(context: Context): AudioRecord {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("RECORD_AUDIO permission is not granted")
        }
        val min = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        if (min <= 0) throw IllegalStateException("Unsupported microphone format")
        val bytes = max(min, RmsAccumulator.WINDOW_SAMPLES * 2 * 4)
        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            CHANNEL,
            ENCODING,
            bytes,
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw IllegalStateException("Could not initialize microphone")
        }
        return record
    }

    fun tryEnableAgc(record: AudioRecord): AutomaticGainControl? {
        if (!AutomaticGainControl.isAvailable()) return null
        return try {
            AutomaticGainControl.create(record.audioSessionId)?.also { it.enabled = true }
        } catch (_: Throwable) { null }
    }
}

class CalibrationCapture(
    private val context: Context,
    private val onMeter: (MeterPoint) -> Unit,
    private val onFailure: (Throwable) -> Unit,
) {
    private val running = AtomicBoolean(false)
    private var thread: Thread? = null
    private var record: AudioRecord? = null
    private var agc: AutomaticGainControl? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        try {
            val audioRecord = AudioCaptureConfig.create(context)
            record = audioRecord
            agc = AudioCaptureConfig.tryEnableAgc(audioRecord)
            audioRecord.startRecording()
            thread = Thread {
                Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
                val accumulator = RmsAccumulator { db -> onMeter(MeterPoint(android.os.SystemClock.elapsedRealtime(), db)) }
                val buffer = ShortArray(4096)
                try {
                    while (running.get()) {
                        val n = audioRecord.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                        if (n < 0) throw IllegalStateException("Microphone read failed: $n")
                        if (n > 0) accumulator.add(buffer, n)
                    }
                } catch (t: Throwable) {
                    if (running.getAndSet(false)) onFailure(t)
                } finally {
                    runCatching { audioRecord.stop() }
                    agc?.release()
                    audioRecord.release()
                    agc = null
                    record = null
                }
            }.also { it.name = "SonemeCalibration"; it.start() }
        } catch (t: Throwable) {
            running.set(false)
            runCatching { agc?.release() }
            runCatching { record?.release() }
            agc = null
            record = null
            onFailure(t)
        }
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        runCatching { record?.stop() }
        thread?.join(1500)
        thread = null
    }

    fun isRunning(): Boolean = running.get()
}
