package com.userexec.soneme.recorder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent

class PlaybackService : Service() {
    inner class PlaybackBinder : Binder() { fun service(): PlaybackService = this@PlaybackService }

    private val binder = PlaybackBinder()
    private lateinit var audioManager: AudioManager
    private lateinit var focusRequest: AudioFocusRequest
    private lateinit var mediaSession: MediaSession
    private val handler = Handler(Looper.getMainLooper())

    private var player: MediaPlayer? = null
    private var prepared = false
    private var tracks: List<PlaybackTrack> = emptyList()
    private var index = -1
    private var pendingAutoplay = false
    private var repeat = RepeatMode.OFF
    private var speed = 1f
    private var rewindMs = 10_000L
    private var forwardMs = 10_000L
    private var sleepDeadlineElapsed: Long? = null
    private var resumeAfterFocusGain = false
    private var foregroundActive = false

    private val sleepRunnable = object : Runnable {
        override fun run() {
            val deadline = sleepDeadlineElapsed ?: return
            if (SystemClock.elapsedRealtime() >= deadline) {
                sleepDeadlineElapsed = null
                pauseInternal(true)
            } else handler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(AudioManager::class.java)
        focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(audioAttributes())
            .setOnAudioFocusChangeListener(::onAudioFocusChanged)
            .build()
        createNotificationChannel()
        setupMediaSession()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_KEEP_ALIVE -> ensureForeground()
            ACTION_PLAY_PAUSE -> togglePlayPause()
            ACTION_PREVIOUS -> previous()
            ACTION_NEXT -> next()
            ACTION_PAUSE -> pause()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        runCatching { player?.release() }
        player = null
        prepared = false
        mediaSession.release()
        audioManager.abandonAudioFocusRequest(focusRequest)
        super.onDestroy()
    }

    fun startSession(items: List<PlaybackTrack>, selectedIndex: Int, autoplay: Boolean = true) {
        destroyPlayerOnly()
        tracks = items.toList()
        index = selectedIndex.coerceIn(0, (tracks.size - 1).coerceAtLeast(0))
        repeat = RepeatMode.OFF
        speed = 1f
        rewindMs = 10_000L
        forwardMs = 10_000L
        sleepDeadlineElapsed = null
        if (tracks.isNotEmpty()) loadIndex(index, autoplay)
    }

    fun hasSession(): Boolean = tracks.isNotEmpty() && index in tracks.indices
    fun sessionTracks(): List<PlaybackTrack> = tracks.toList()
    fun currentIndex(): Int = index
    fun currentTrack(): PlaybackTrack? = tracks.getOrNull(index)

    fun destroySession() {
        handler.removeCallbacks(sleepRunnable)
        sleepDeadlineElapsed = null
        destroyPlayerOnly()
        tracks = emptyList()
        index = -1
        repeat = RepeatMode.OFF
        speed = 1f
        rewindMs = 10_000L
        forwardMs = 10_000L
        stopForeground(STOP_FOREGROUND_REMOVE)
        foregroundActive = false
        updatePlaybackState(PlaybackState.STATE_NONE)
        stopSelf()
    }

    private fun destroyPlayerOnly() {
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        prepared = false
        pendingAutoplay = false
    }

    private fun loadIndex(target: Int, autoplay: Boolean) {
        if (target !in tracks.indices) return
        runCatching { player?.release() }
        player = null
        prepared = false
        index = target
        pendingAutoplay = autoplay
        val track = tracks[target]
        val newPlayer = MediaPlayer()
        player = newPlayer
        try {
            newPlayer.setAudioAttributes(audioAttributes())
            newPlayer.setDataSource(this, Uri.parse(track.uri))
            newPlayer.setOnPreparedListener {
                prepared = true
                applyPlaybackSpeed()
                updateSessionMetadata()
                updatePlaybackState()
                if (pendingAutoplay) play()
            }
            newPlayer.setOnCompletionListener { handleCompletion() }
            newPlayer.setOnErrorListener { _, _, _ ->
                prepared = false
                updatePlaybackState(PlaybackState.STATE_ERROR)
                true
            }
            newPlayer.prepareAsync()
        } catch (_: Throwable) {
            runCatching { newPlayer.release() }
            if (player === newPlayer) player = null
            prepared = false
            pendingAutoplay = false
            updatePlaybackState(PlaybackState.STATE_ERROR)
        }
    }

    fun play() {
        if (!prepared) { pendingAutoplay = true; return }
        if (audioManager.requestAudioFocus(focusRequest) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return
        startService(Intent(this, PlaybackService::class.java).setAction(ACTION_KEEP_ALIVE))
        ensureForeground()
        player?.start()
        updatePlaybackState()
        refreshNotificationIfPresent()
    }

    fun pause() = pauseInternal(true)

    private fun pauseInternal(abandonFocus: Boolean) {
        if (prepared && player?.isPlaying == true) player?.pause()
        if (abandonFocus) audioManager.abandonAudioFocusRequest(focusRequest)
        updatePlaybackState()
        refreshNotificationIfPresent()
    }

    fun togglePlayPause() { if (isPlaying()) pause() else play() }

    fun seekRelative(deltaMs: Long) { seekTo(positionMs() + deltaMs) }

    fun seekTo(positionMs: Long) {
        if (!prepared) return
        val end = durationMs().coerceAtLeast(0L)
        player?.seekTo(positionMs.coerceIn(0L, end).toInt())
        updatePlaybackState()
    }

    fun previous() {
        if (tracks.size <= 1) return
        loadIndex((index - 1 + tracks.size) % tracks.size, true)
    }

    fun next() {
        if (tracks.size <= 1) return
        loadIndex((index + 1) % tracks.size, true)
    }

    private fun handleCompletion() {
        when (repeat) {
            RepeatMode.ONE -> loadIndex(index, true)
            RepeatMode.ALL -> if (tracks.isNotEmpty()) loadIndex((index + 1) % tracks.size, true)
            RepeatMode.OFF -> {
                if (index in 0 until tracks.lastIndex) loadIndex(index + 1, true)
                else {
                    updatePlaybackState(PlaybackState.STATE_STOPPED)
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    foregroundActive = false
                }
            }
        }
    }

    fun isPlaying(): Boolean = prepared && runCatching { player?.isPlaying == true }.getOrDefault(false)
    fun positionMs(): Long = if (prepared) runCatching { player?.currentPosition?.toLong() ?: 0L }.getOrDefault(0L) else 0L
    fun durationMs(): Long = if (prepared) runCatching { player?.duration?.toLong() ?: 0L }.getOrDefault(0L) else currentTrack()?.durationMs ?: 0L
    fun queueSize(): Int = tracks.size

    fun repeatMode(): RepeatMode = repeat
    fun setRepeatMode(mode: RepeatMode) { repeat = mode; updatePlaybackState() }
    fun cycleRepeat(): RepeatMode {
        repeat = when (repeat) { RepeatMode.OFF -> RepeatMode.ONE; RepeatMode.ONE -> RepeatMode.ALL; RepeatMode.ALL -> RepeatMode.OFF }
        updatePlaybackState()
        return repeat
    }

    fun playbackSpeed(): Float = speed
    fun setPlaybackSpeed(value: Float) { speed = value; applyPlaybackSpeed(); updatePlaybackState() }
    private fun applyPlaybackSpeed() { if (prepared) runCatching { player?.playbackParams = PlaybackParams().setSpeed(speed) } }

    fun rewindIntervalMs(): Long = rewindMs
    fun forwardIntervalMs(): Long = forwardMs
    fun setRewindIntervalMs(ms: Long) { rewindMs = ms }
    fun setForwardIntervalMs(ms: Long) { forwardMs = ms }

    fun setSleepMinutes(minutes: Int) {
        handler.removeCallbacks(sleepRunnable)
        if (minutes <= 0) { sleepDeadlineElapsed = null; return }
        sleepDeadlineElapsed = SystemClock.elapsedRealtime() + minutes * 60_000L
        handler.post(sleepRunnable)
    }

    fun addSleepMinutes(minutes: Int) {
        if (minutes <= 0) return
        val now = SystemClock.elapsedRealtime()
        val base = sleepDeadlineElapsed?.takeIf { it > now } ?: now
        sleepDeadlineElapsed = base + minutes * 60_000L
        handler.removeCallbacks(sleepRunnable)
        handler.post(sleepRunnable)
    }

    fun sleepRemainingMs(): Long = (sleepDeadlineElapsed?.minus(SystemClock.elapsedRealtime()) ?: 0L).coerceAtLeast(0L)

    private fun onAudioFocusChanged(change: Int) {
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> { resumeAfterFocusGain = false; pauseInternal(false) }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                resumeAfterFocusGain = isPlaying(); pauseInternal(false)
            }
            AudioManager.AUDIOFOCUS_GAIN -> if (resumeAfterFocusGain) { resumeAfterFocusGain = false; play() }
        }
    }

    private fun setupMediaSession() {
        mediaSession = MediaSession(this, "SonemeRecorderPlayback").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = play()
                override fun onPause() = pause()
                override fun onSkipToPrevious() = previous()
                override fun onSkipToNext() = next()
                override fun onSeekTo(pos: Long) = seekTo(pos)
                override fun onStop() = pause()
                override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                    @Suppress("DEPRECATION")
                    val event = mediaButtonIntent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
                        ?: return super.onMediaButtonEvent(mediaButtonIntent)
                    if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount != 0) return true
                    when (event.keyCode) {
                        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> togglePlayPause()
                        KeyEvent.KEYCODE_MEDIA_PLAY -> play()
                        KeyEvent.KEYCODE_MEDIA_PAUSE -> pause()
                        KeyEvent.KEYCODE_MEDIA_NEXT -> next()
                        KeyEvent.KEYCODE_MEDIA_PREVIOUS -> previous()
                        else -> return super.onMediaButtonEvent(mediaButtonIntent)
                    }
                    return true
                }
            })
            isActive = true
        }
        updatePlaybackState()
    }

    private fun updateSessionMetadata() {
        val track = currentTrack() ?: return
        mediaSession.setMetadata(MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, track.title)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, track.series)
            .putLong(MediaMetadata.METADATA_KEY_DURATION, durationMs().takeIf { it > 0 } ?: track.durationMs)
            .build())
    }

    private fun updatePlaybackState(forced: Int? = null) {
        val state = forced ?: when { isPlaying() -> PlaybackState.STATE_PLAYING; prepared -> PlaybackState.STATE_PAUSED; hasSession() -> PlaybackState.STATE_BUFFERING; else -> PlaybackState.STATE_NONE }
        val actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
            PlaybackState.ACTION_SEEK_TO or PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS
        mediaSession.setPlaybackState(PlaybackState.Builder().setActions(actions).setState(state, positionMs(), speed).build())
    }

    private fun audioAttributes() = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()

    private fun ensureForeground() {
        if (foregroundActive) return
        startForeground(NOTIFICATION_ID, buildNotification())
        foregroundActive = true
    }

    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Recording playback", NotificationManager.IMPORTANCE_LOW))
    }

    private fun buildNotification(): Notification {
        val track = currentTrack()
        val contentIntent = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        fun serviceIntent(action: String, requestCode: Int) = PendingIntent.getService(this, requestCode,
            Intent(this, PlaybackService::class.java).setAction(action), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(track?.title ?: "Soneme Recorder")
            .setContentText(track?.series ?: "")
            .setContentIntent(contentIntent)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .addAction(Notification.Action.Builder(android.R.drawable.ic_media_previous, "Previous", serviceIntent(ACTION_PREVIOUS, 1)).build())
            .addAction(Notification.Action.Builder(if (isPlaying()) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (isPlaying()) "Pause" else "Play", serviceIntent(ACTION_PLAY_PAUSE, 2)).build())
            .addAction(Notification.Action.Builder(android.R.drawable.ic_media_next, "Next", serviceIntent(ACTION_NEXT, 3)).build())
            .setStyle(Notification.MediaStyle().setMediaSession(mediaSession.sessionToken).setShowActionsInCompactView(0, 1, 2))
            .build()
    }

    private fun refreshNotificationIfPresent() {
        if (!foregroundActive || !hasSession()) return
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
    }

    companion object {
        private const val CHANNEL_ID = "playback"
        private const val NOTIFICATION_ID = 1002
        const val ACTION_KEEP_ALIVE = "com.userexec.soneme.recorder.PLAYBACK_KEEP_ALIVE"
        const val ACTION_PLAY_PAUSE = "com.userexec.soneme.recorder.PLAYBACK_PLAY_PAUSE"
        const val ACTION_PREVIOUS = "com.userexec.soneme.recorder.PLAYBACK_PREVIOUS"
        const val ACTION_NEXT = "com.userexec.soneme.recorder.PLAYBACK_NEXT"
        const val ACTION_PAUSE = "com.userexec.soneme.recorder.PLAYBACK_PAUSE"
    }
}
