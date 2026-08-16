package com.userexec.soneme.recorder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioRecord
import android.media.audiofx.AutomaticGainControl
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.Process
import android.os.SystemClock
import android.provider.DocumentsContract
import java.io.OutputStream
import java.time.Instant
import java.time.ZoneId
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

class RecordingService : Service() {
    enum class Mode { IDLE, STARTING, RECORDING, PAUSED, FINISHING, FAILED, FINISHED }

    data class Snapshot(
        val mode: Mode,
        val seriesName: String?,
        val seriesDocumentId: String?,
        val treeUri: String?,
        val startEpochMs: Long,
        val elapsedMs: Long,
        val meter: List<MeterPoint>,
        val failure: String?,
    )

    inner class LocalBinder : Binder() {
        fun service(): RecordingService = this@RecordingService
    }

    private val binder = LocalBinder()
    private val lock = Any()
    private val shouldRun = AtomicBoolean(false)
    private var mode = Mode.IDLE
    private var seriesName: String? = null
    private var treeUri: Uri? = null
    private var seriesDocumentId: String? = null
    private var startEpochMs = 0L
    private var activeAccumulatedMs = 0L
    private var activeSegmentStartElapsed = 0L
    private val meter = ArrayDeque<MeterPoint>()
    private var failure: String? = null
    private var tempUri: Uri? = null
    private var tempName: String? = null
    private var audioRecord: AudioRecord? = null
    private var agc: AutomaticGainControl? = null
    private var encoder: LameEncoder? = null
    private var output: OutputStream? = null
    private var worker: Thread? = null
    private var stoppedLatch: CountDownLatch? = null
    @Volatile private var finishRequested = false

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_START && snapshot().mode == Mode.IDLE) {
            val request = RecordingRequest(
                intent.getStringExtra(EXTRA_TREE_URI) ?: return START_NOT_STICKY,
                intent.getStringExtra(EXTRA_SERIES_ID) ?: return START_NOT_STICKY,
                intent.getStringExtra(EXTRA_SERIES_NAME) ?: return START_NOT_STICKY,
                intent.getLongExtra(EXTRA_START_EPOCH, System.currentTimeMillis()),
            )
            startForegroundCompat(notification(request.seriesName))
            begin(request)
        }
        return START_NOT_STICKY
    }

    fun snapshot(): Snapshot = synchronized(lock) {
        val elapsed = when (mode) {
            Mode.RECORDING -> activeAccumulatedMs + (SystemClock.elapsedRealtime() - activeSegmentStartElapsed)
            else -> activeAccumulatedMs
        }
        Snapshot(mode, seriesName, seriesDocumentId, treeUri?.toString(), startEpochMs, elapsed, meter.toList(), failure)
    }

    fun pauseRecording() = synchronized(lock) {
        if (mode == Mode.RECORDING) {
            activeAccumulatedMs += SystemClock.elapsedRealtime() - activeSegmentStartElapsed
            mode = Mode.PAUSED
        }
    }

    fun resumeRecording() = synchronized(lock) {
        if (mode == Mode.PAUSED) {
            activeSegmentStartElapsed = SystemClock.elapsedRealtime()
            mode = Mode.RECORDING
        }
    }

    fun finishRecording(): RecordingResult? {
        val latch: CountDownLatch
        synchronized(lock) {
            if (mode !in setOf(Mode.RECORDING, Mode.PAUSED, Mode.STARTING)) return null
            if (mode == Mode.RECORDING) {
                activeAccumulatedMs += SystemClock.elapsedRealtime() - activeSegmentStartElapsed
            }
            mode = Mode.FINISHING
            finishRequested = true
            shouldRun.set(false)
            latch = stoppedLatch ?: CountDownLatch(0)
        }
        runCatching { audioRecord?.stop() }
        latch.await()
        val uri = synchronized(lock) { tempUri } ?: return null
        val scan = contentResolver.openInputStream(uri)?.use(Mp3FrameScanner::scan) ?: return null
        val interrupted: Boolean
        val failureMessage: String?
        synchronized(lock) {
            interrupted = mode == Mode.FAILED
            failureMessage = failure
            if (!interrupted) mode = Mode.FINISHED
        }
        return RecordingResult(
            uri,
            synchronized(lock) { tempName ?: "" },
            synchronized(lock) { seriesDocumentId ?: "" },
            synchronized(lock) { seriesName ?: "" },
            synchronized(lock) { startEpochMs },
            scan.completeBytes,
            scan.frameCount,
            interrupted,
            failureMessage,
        )
    }

    fun discardAndStop() {
        synchronized(lock) { tempUri }?.let { runCatching { DocumentsContract.deleteDocument(contentResolver, it) } }
        shutdown()
    }

    fun shutdown() {
        shouldRun.set(false)
        runCatching { audioRecord?.stop() }
        runCatching { stoppedLatch?.await() }
        stopForeground(STOP_FOREGROUND_REMOVE)
        synchronized(lock) {
            mode = Mode.IDLE
            meter.clear()
            failure = null
            seriesName = null
            treeUri = null
            seriesDocumentId = null
            startEpochMs = 0L
            activeAccumulatedMs = 0L
            activeSegmentStartElapsed = 0L
            tempUri = null
            tempName = null
        }
        stopSelf()
    }

    private fun begin(request: RecordingRequest) {
        synchronized(lock) {
            mode = Mode.STARTING
            seriesName = request.seriesName
            treeUri = Uri.parse(request.treeUri)
            seriesDocumentId = request.seriesDocumentId
            startEpochMs = request.startEpochMs
            activeAccumulatedMs = 0L
            failure = null
            meter.clear()
            finishRequested = false
            stoppedLatch = CountDownLatch(1)
        }
        shouldRun.set(true)
        worker = Thread {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            var localRecord: AudioRecord? = null
            var localAgc: AutomaticGainControl? = null
            var localEncoder: LameEncoder? = null
            var localOutput: OutputStream? = null
            try {
                val start = Instant.ofEpochMilli(request.startEpochMs).atZone(ZoneId.systemDefault()).toLocalDateTime()
                val name = RecorderNames.tempName(request.seriesName, start)
                val store = RecorderStore(applicationContext)
                val node = store.createFile(Uri.parse(request.treeUri), request.seriesDocumentId, "audio/mpeg", name)
                synchronized(lock) { tempUri = node.uri; tempName = node.name }
                localOutput = contentResolver.openOutputStream(node.uri, "w") ?: throw IllegalStateException("Could not open TEMP file")
                localEncoder = LameEncoder.create()
                localRecord = AudioCaptureConfig.create(this)
                localAgc = AudioCaptureConfig.tryEnableAgc(localRecord)
                synchronized(lock) {
                    output = localOutput; encoder = localEncoder; audioRecord = localRecord; agc = localAgc
                    activeSegmentStartElapsed = SystemClock.elapsedRealtime()
                    mode = Mode.RECORDING
                }
                val rms = RmsAccumulator { db ->
                    synchronized(lock) {
                        val now = SystemClock.elapsedRealtime()
                        meter.addLast(MeterPoint(now, db))
                        while (meter.isNotEmpty() && now - meter.first().elapsedRealtimeMs > 10_500L) meter.removeFirst()
                    }
                }
                val pcm = ShortArray(4096)
                val mp3 = ByteArray(16 * 1024)
                localRecord.startRecording()
                while (shouldRun.get()) {
                    val n = localRecord.read(pcm, 0, pcm.size, AudioRecord.READ_BLOCKING)
                    if (n < 0) {
                        // AudioRecord.stop() is used to unblock READ_BLOCKING when
                        // Finish/shutdown is requested. Treat that expected read
                        // error as loop termination, not as a recording failure.
                        if (!shouldRun.get()) break
                        throw IllegalStateException("Microphone read failed: $n")
                    }
                    if (n == 0) continue
                    rms.add(pcm, n)
                    val encode = synchronized(lock) { mode == Mode.RECORDING }
                    if (encode) {
                        val encoded = localEncoder.encode(pcm, n, mp3)
                        if (encoded < 0) throw IllegalStateException("LAME encode failed: $encoded")
                        if (encoded > 0) {
                            localOutput.write(mp3, 0, encoded)
                            localOutput.flush()
                        }
                    }
                }
                if (finishRequested) {
                    val flushed = localEncoder.flush(mp3)
                    if (flushed < 0) throw IllegalStateException("LAME flush failed: $flushed")
                    if (flushed > 0) localOutput.write(mp3, 0, flushed)
                    localOutput.flush()
                }
            } catch (t: Throwable) {
                synchronized(lock) {
                    if (!finishRequested) {
                        failure = t.message ?: t.javaClass.simpleName
                        mode = Mode.FAILED
                    } else if (mode == Mode.FINISHING) {
                        failure = t.message ?: t.javaClass.simpleName
                        mode = Mode.FAILED
                    }
                }
            } finally {
                shouldRun.set(false)
                runCatching { localRecord?.stop() }
                runCatching { localAgc?.release() }
                runCatching { localRecord?.release() }
                runCatching { localEncoder?.close() }
                runCatching { localOutput?.close() }
                synchronized(lock) { audioRecord = null; agc = null; encoder = null; output = null }
                stoppedLatch?.countDown()
                val failed = synchronized(lock) { mode == Mode.FAILED }
                if (failed) {
                    // A failed recording is recoverable only if it contains at least one
                    // complete MP3 frame. Empty/unusable TEMP files are discarded immediately.
                    synchronized(lock) { tempUri }?.let { uri ->
                        val scan = runCatching { contentResolver.openInputStream(uri)?.use(Mp3FrameScanner::scan) }.getOrNull()
                        if (scan == null || scan.frameCount == 0) {
                            runCatching { DocumentsContract.deleteDocument(contentResolver, uri) }
                            synchronized(lock) { tempUri = null; tempName = null }
                        }
                    }
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    sendBroadcast(Intent(ACTION_FAILURE).setPackage(packageName))
                    stopSelf()
                }
            }
        }.also { it.name = "SonemeRecorderAudio"; it.start() }
    }

    private fun notification(series: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Soneme Recorder")
            .setContentText("Recording: $series")
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else startForeground(NOTIFICATION_ID, notification)
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Recording", NotificationManager.IMPORTANCE_LOW))
    }

    companion object {
        const val ACTION_START = "com.userexec.soneme.recorder.START_RECORDING"
        const val ACTION_FAILURE = "com.userexec.soneme.recorder.RECORDING_FAILURE"
        const val EXTRA_TREE_URI = "tree_uri"
        const val EXTRA_SERIES_ID = "series_id"
        const val EXTRA_SERIES_NAME = "series_name"
        const val EXTRA_START_EPOCH = "start_epoch"
        private const val CHANNEL = "recording"
        private const val NOTIFICATION_ID = 1001
    }
}
