package com.userexec.soneme.recorder

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.DocumentsContract
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class MainActivity : Activity() {
    private enum class AppView { BOOT, STORAGE_SETUP, SERIES, SERIES_EDIT, RECORDINGS, RECORDER, RECORD_TITLE, RECOVERY, PLAYER }
    private enum class SoftKey { LEFT, CENTER, RIGHT }
    private enum class MicAction { CALIBRATE, RECORD }

    private lateinit var root: FrameLayout
    private lateinit var store: RecorderStore
    private val worker = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())

    private var view = AppView.BOOT
    private var access: RecorderStore.RootAccess? = null
    private var seriesItems: List<SeriesItem> = emptyList()
    private var selectedSeries: SeriesItem? = null
    private var recordingItems: List<RecordingItem> = emptyList()
    private var listView: ListView? = null
    private var seriesEditField: EditText? = null
    private var validationText: TextView? = null
    private var editingSeries: SeriesItem? = null
    private var pendingRecovery: MutableList<TempRecording> = mutableListOf()
    private var currentRecovery: TempRecording? = null
    private var pendingRecordingResult: RecordingResult? = null
    private var titleField: EditText? = null
    private var pendingMicAction: MicAction? = null

    private var calibration: CalibrationCapture? = null
    private val calibrationPoints = ArrayDeque<MeterPoint>()
    private var recorderMeter: RecorderMeterView? = null
    private var recorderHeader: TextView? = null
    private var recorderSubhead: TextView? = null
    private var recorderElapsed: TextView? = null
    private var recorderCurrentTime: TextView? = null

    private var recordingService: RecordingService? = null
    private var playbackService: PlaybackService? = null
    private var recordingBound = false
    private var playbackBound = false
    private var booted = false

    private var playerTitle: TextView? = null
    private var playerSeries: TextView? = null
    private var playerElapsed: TextView? = null
    private var playerRemaining: TextView? = null
    private var playerSummary: TextView? = null
    private var playerWiper: SeekBar? = null
    private var playerPlay: Button? = null
    private var playerPrevious: Button? = null
    private var playerNext: Button? = null
    private var playerRewind: Button? = null
    private var playerForward: Button? = null
    private var playerRepeat: Button? = null
    private var playerSpeed: Button? = null
    private var playerSleep: Button? = null
    private var wiperHoldRunnable: Runnable? = null
    private var wiperHoldActive = false
    private var wiperWasPlaying = false

    private var lastSoftkeys: Triple<String, String, String>? = null

    private val recordingConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            recordingService = (binder as RecordingService.LocalBinder).service()
            recordingBound = true
            maybeBoot()
        }
        override fun onServiceDisconnected(name: ComponentName?) { recordingBound = false; recordingService = null }
    }

    private val playbackConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            playbackService = (binder as PlaybackService.PlaybackBinder).service()
            playbackBound = true
            maybeBoot()
        }
        override fun onServiceDisconnected(name: ComponentName?) { playbackBound = false; playbackService = null }
    }

    private val recorderTicker = object : Runnable {
        override fun run() {
            if (view == AppView.RECORDER) updateRecorderUi()
            if (view == AppView.PLAYER) updatePlayerUi()
            ui.postDelayed(this, 250L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = RecorderStore(this)
        root = FrameLayout(this)
        setContentView(root)
        showBoot("Starting…")
        ui.post(recorderTicker)
    }

    override fun onResume() {
        super.onResume()
        // The XP3900 clears its native softkey labels while the flip is closed.
        // Re-issue the current labels every time the Activity returns to the foreground,
        // even when our cached label values themselves have not changed.
        updateSoftkeys(force = true)
    }

    override fun onStart() {
        super.onStart()
        bindService(Intent(this, RecordingService::class.java), recordingConnection, Context.BIND_AUTO_CREATE)
        bindService(Intent(this, PlaybackService::class.java), playbackConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        if (recordingBound) { unbindService(recordingConnection); recordingBound = false }
        if (playbackBound) { unbindService(playbackConnection); playbackBound = false }
        super.onStop()
    }

    override fun onPause() {
        if (calibration?.isRunning() == true) stopCalibration(clear = true)
        super.onPause()
    }

    override fun onDestroy() {
        ui.removeCallbacks(recorderTicker)
        calibration?.stop()
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun maybeBoot() {
        if (booted || !recordingBound || !playbackBound) return
        booted = true
        val rs = recordingService?.snapshot()
        if (rs != null && rs.mode in setOf(RecordingService.Mode.STARTING, RecordingService.Mode.RECORDING, RecordingService.Mode.PAUSED, RecordingService.Mode.FINISHING)) {
            restoreActiveRecording(rs)
            return
        }
        if (playbackService?.hasSession() == true) {
            showPlayer(reuseSession = true)
            return
        }
        initializeStorage()
    }

    private fun restoreActiveRecording(snapshot: RecordingService.Snapshot) {
        val tree = snapshot.treeUri?.let(Uri::parse)
        val seriesId = snapshot.seriesDocumentId
        if (tree == null || seriesId == null) { initializeStorage(); return }
        val saved = store.savedAccess()
        access = saved
        worker.execute {
            val node = runCatching { store.nodeForId(tree, seriesId) }.getOrNull()
            ui.post {
                if (node == null) initializeStorage() else {
                    selectedSeries = SeriesItem(node, 0, null)
                    showRecorder()
                }
            }
        }
    }

    private fun initializeStorage() {
        val saved = store.savedAccess()
        if (saved == null) { showStorageSetup(); return }
        showBoot("Reading recordings…")
        worker.execute {
            if (!store.verify(saved)) {
                ui.post { showUnavailableStorage() }
                return@execute
            }
            runCatching {
                store.ensureMiscellaneous(saved)
                store.cleanupStaging(saved)
                store.findInterrupted(saved)
            }.onSuccess { interrupted ->
                ui.post {
                    access = saved
                    pendingRecovery = interrupted.toMutableList()
                    if (pendingRecovery.isNotEmpty()) showNextRecovery() else showSeries()
                }
            }.onFailure { ui.post { showUnavailableStorage() } }
        }
    }

    private fun showStorageSetup() {
        view = AppView.STORAGE_SETUP
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        box.addView(TextView(this).apply {
            text = "Set up recording folder"
            textSize = 19f
            setTextColor(Color.BLACK)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(12))
        })
        box.addView(TextView(this).apply {
            text = "No SonemeRecorder folder is currently set up. Choose the location on this phone's storage where a previous SonemeRecorder folder exists, or where you would like a new one created."
            textSize = 15f
            setTextColor(Color.BLACK)
        })
        setRoot(box)
        updateSoftkeys(force = true)
    }

    private fun launchStoragePicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
        }
        startActivityForResult(intent, REQUEST_TREE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_TREE) return
        if (resultCode != RESULT_OK) {
            if (store.savedAccess() == null) showStorageSetup()
            return
        }
        val uri = data?.data ?: return
        val flags = (data.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION))
        runCatching { contentResolver.takePersistableUriPermission(uri, flags) }
        showBoot("Preparing storage…")
        worker.execute {
            runCatching {
                val resolved = store.resolveAndPersist(uri)
                store.ensureMiscellaneous(resolved)
                store.cleanupStaging(resolved)
                resolved to store.findInterrupted(resolved)
            }.onSuccess { (resolved, interrupted) ->
                ui.post {
                    access = resolved
                    pendingRecovery = interrupted.toMutableList()
                    if (pendingRecovery.isEmpty()) showSeries() else showNextRecovery()
                }
            }.onFailure { t -> ui.post { showError("Could not prepare SonemeRecorder folder.", t); launchStoragePicker() } }
        }
    }

    private fun showUnavailableStorage() {
        AlertDialog.Builder(this)
            .setTitle("Recording folder unavailable")
            .setMessage("The configured SonemeRecorder folder cannot be accessed.")
            .setPositiveButton("Choose folder") { _, _ -> launchStoragePicker() }
            .setNegativeButton("Exit") { _, _ -> finish() }
            .setCancelable(false)
            .show()
    }

    private fun showBoot(text: String) {
        view = AppView.BOOT
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(dp(12), dp(12), dp(12), dp(12)) }
        box.addView(ProgressBar(this))
        box.addView(TextView(this).apply { this.text = text; gravity = Gravity.CENTER; setPadding(0, dp(8), 0, 0) })
        setRoot(box)
        updateSoftkeys(force = true)
    }

    private fun showSeries(focusIndex: Int = 0) {
        val a = access ?: return
        view = AppView.SERIES
        showBoot("Reading series…")
        worker.execute {
            runCatching { store.listSeries(a) }.onSuccess { items ->
                ui.post {
                    view = AppView.SERIES
                    seriesItems = items
                    val list = ListView(this).apply {
                        adapter = SeriesAdapter(items)
                        choiceMode = ListView.CHOICE_MODE_SINGLE
                        isFocusable = true
                        setOnItemClickListener { _, _, pos, _ -> openRecordings(items[pos]) }
                        onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                            override fun onItemSelected(parent: android.widget.AdapterView<*>?, v: View?, position: Int, id: Long) { updateSoftkeys() }
                            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) { updateSoftkeys() }
                        }
                    }
                    val layout = vertical(listHeader("Series"), list)
                    setRoot(layout)
                    listView = list
                    if (items.isNotEmpty()) list.setSelection(focusIndex.coerceIn(0, items.lastIndex))
                    list.requestFocus()
                    updateSoftkeys(force = true)
                }
            }.onFailure { t -> ui.post { showError("Could not read series.", t) } }
        }
    }

    private fun openRecordings(series: SeriesItem) {
        selectedSeries = series
        showRecordings()
    }

    private fun showRecordings(focusIndex: Int = 0) {
        val a = access ?: return
        val series = selectedSeries ?: return
        view = AppView.RECORDINGS
        showBoot("Reading recordings…")
        worker.execute {
            runCatching { store.listRecordings(a, series.node) }.onSuccess { items ->
                ui.post {
                    view = AppView.RECORDINGS
                    recordingItems = items
                    val list = ListView(this).apply {
                        adapter = RecordingAdapter(items)
                        choiceMode = ListView.CHOICE_MODE_SINGLE
                        isFocusable = true
                        emptyView = TextView(this@MainActivity).apply { text = "No recordings"; gravity = Gravity.CENTER }
                        setOnItemClickListener { _, _, pos, _ -> startPlayer(pos) }
                        onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                            override fun onItemSelected(parent: android.widget.AdapterView<*>?, v: View?, position: Int, id: Long) { updateSoftkeys() }
                            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) { updateSoftkeys() }
                        }
                    }
                    val container = FrameLayout(this)
                    val empty = TextView(this).apply { text = "No recordings"; gravity = Gravity.CENTER }
                    container.addView(list, FrameLayout.LayoutParams(-1, -1))
                    container.addView(empty, FrameLayout.LayoutParams(-1, -1))
                    list.emptyView = empty
                    setRoot(vertical(listHeader(series.name), container))
                    listView = list
                    if (items.isNotEmpty()) list.setSelection(focusIndex.coerceIn(0, items.lastIndex))
                    list.requestFocus()
                    updateSoftkeys(force = true)
                }
            }.onFailure { t -> ui.post { showError("Could not read recordings.", t) } }
        }
    }

    private fun showSeriesEdit(existing: SeriesItem?) {
        view = AppView.SERIES_EDIT
        editingSeries = existing
        val field = EditText(this).apply {
            setSingleLine(true)
            textSize = 18f
            setText(existing?.name ?: "")
            setSelection(text.length)
        }
        val validation = TextView(this).apply { setTextColor(Color.rgb(170, 0, 0)); textSize = 12f }
        seriesEditField = field
        validationText = validation
        field.addTextChangedListener(simpleWatcher { updateSeriesEditValidation() })
        setRoot(vertical(header(if (existing == null) "New Series" else "Edit Series"), field, validation))
        field.requestFocus()
        updateSeriesEditValidation()
    }

    private fun updateSeriesEditValidation() {
        val field = seriesEditField ?: return
        val raw = field.text.toString()
        val existing = editingSeries
        var error = RecorderNames.validateSeries(raw)
        if (error == null && raw.equals(RecorderStore.MISC, true)) error = "Miscellaneous is reserved."
        if (error == null) {
            val collision = seriesItems.any { it.node.documentId != existing?.node?.documentId && it.name.equals(raw, true) }
            if (collision) error = "A series with that name already exists."
        }
        validationText?.text = error ?: ""
        updateSoftkeys(force = true)
    }

    private fun saveSeriesEdit() {
        val a = access ?: return
        val raw = seriesEditField?.text?.toString() ?: return
        if (!seriesEditValid()) return
        showBoot("Saving…")
        val existing = editingSeries
        worker.execute {
            runCatching {
                if (existing == null) store.createSeries(a, raw) else store.renameSeries(a, existing.node, raw)
            }.onSuccess { node ->
                ui.post {
                    selectedSeries = SeriesItem(node, existing?.recordingCount ?: 0, existing?.newest)
                    showSeries(seriesItems.indexOfFirst { it.node.documentId == existing?.node?.documentId }.coerceAtLeast(0))
                }
            }.onFailure { t ->
                ui.post {
                    showSeriesEdit(existing)
                    seriesEditField?.setText(raw); seriesEditField?.setSelection(raw.length)
                    validationText?.text = if (existing == null) "Could not create series." else "Could not rename series."
                    Toast.makeText(this, t.message ?: "Save failed", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun seriesEditValid(): Boolean {
        val raw = seriesEditField?.text?.toString() ?: return false
        val existing = editingSeries
        if (RecorderNames.validateSeries(raw) != null || raw.equals(RecorderStore.MISC, true)) return false
        if (seriesItems.any { it.node.documentId != existing?.node?.documentId && it.name.equals(raw, true) }) return false
        return existing == null || raw != existing.name
    }

    private fun deleteSelectedSeries() {
        val pos = selectedListPosition()
        val item = seriesItems.getOrNull(pos) ?: return
        if (item.miscellaneous) return
        AlertDialog.Builder(this)
            .setMessage("Deleting a series will also delete all files in its folder. Be sure anything you want to keep is transferred off the device before deleting.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ -> deleteSeries(item, pos) }
            .show()
    }

    private fun deleteEditingSeries() {
        val item = editingSeries ?: return
        AlertDialog.Builder(this)
            .setMessage("Deleting a series will also delete all files in its folder. Be sure anything you want to keep is transferred off the device before deleting.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                val pos = seriesItems.indexOfFirst { it.node.documentId == item.node.documentId }.coerceAtLeast(0)
                deleteSeries(item, pos)
            }.show()
    }

    private fun deleteSeries(item: SeriesItem, oldPos: Int) {
        val a = access ?: return
        showBoot("Deleting…")
        worker.execute {
            runCatching { store.deleteRecursively(a, item.node) }
                .onSuccess { ui.post { showSeries(oldPos) } }
                .onFailure { t -> ui.post { showError("Could not delete series.", t); showSeries(oldPos) } }
        }
    }

    private fun deleteSelectedRecording() {
        val pos = selectedListPosition()
        val item = recordingItems.getOrNull(pos) ?: return
        AlertDialog.Builder(this).setMessage("Delete ${item.title}?")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                worker.execute {
                    runCatching { store.deleteFile(item.node) }
                        .onSuccess { ui.post { showRecordings(pos) } }
                        .onFailure { t -> ui.post { showError("Could not delete recording.", t); showRecordings(pos) } }
                }
            }.show()
    }

    private fun showRecorder() {
        view = AppView.RECORDER
        calibrationPoints.clear()
        val headerRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(Color.WHITE); setPadding(dp(6), dp(3), dp(6), dp(3)) }
        val textBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        recorderHeader = TextView(this).apply { text = selectedSeries?.name ?: recordingService?.snapshot()?.seriesName ?: "Recorder"; textSize = 17f; isSingleLine = true; ellipsize = android.text.TextUtils.TruncateAt.MARQUEE; marqueeRepeatLimit = -1; isSelected = true }
        recorderSubhead = TextView(this).apply { textSize = 13f }
        textBox.addView(recorderHeader, LinearLayout.LayoutParams(-1, -2))
        textBox.addView(recorderSubhead, LinearLayout.LayoutParams(-1, -2))
        headerRow.addView(textBox, LinearLayout.LayoutParams(0, -2, 1f))

        recorderMeter = RecorderMeterView(this)

        val timeBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(6), dp(3), dp(2), dp(3))
        }
        recorderCurrentTime = TextView(this).apply { textSize = 14f; typeface = Typeface.create("sans-serif-condensed", Typeface.NORMAL); isSingleLine = true }
        recorderElapsed = TextView(this).apply { textSize = 14f; typeface = Typeface.create("sans-serif-condensed", Typeface.NORMAL); isSingleLine = true; gravity = Gravity.END }
        timeBar.addView(recorderCurrentTime, LinearLayout.LayoutParams(-2, -2))
        timeBar.addView(android.widget.Space(this), LinearLayout.LayoutParams(0, 0, 1f))
        timeBar.addView(recorderElapsed, LinearLayout.LayoutParams(-2, -2))

        setRoot(vertical(headerRow, recorderMeter!!, timeBar, weights = floatArrayOf(0f, 1f, 0f)))
        recorderMeter?.isFocusable = true
        recorderMeter?.requestFocus()
        updateRecorderUi()
        updateSoftkeys(force = true)
    }

    private fun ensureMicPermission(action: MicAction) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            if (action == MicAction.CALIBRATE) startCalibration() else startRecording()
        } else {
            pendingMicAction = action
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MIC)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_MIC) {
            val action = pendingMicAction
            pendingMicAction = null
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED && action != null) {
                if (action == MicAction.CALIBRATE) startCalibration() else startRecording()
            } else Toast.makeText(this, "Microphone permission is required.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startCalibration() {
        if (calibration?.isRunning() == true) return
        calibrationPoints.clear()
        calibration = CalibrationCapture(this,
            onMeter = { point ->
                synchronized(calibrationPoints) {
                    calibrationPoints.addLast(point)
                    while (calibrationPoints.isNotEmpty() && point.elapsedRealtimeMs - calibrationPoints.first().elapsedRealtimeMs > 10_500L) calibrationPoints.removeFirst()
                }
            },
            onFailure = { t -> ui.post { Toast.makeText(this, "Calibration failed: ${t.message}", Toast.LENGTH_LONG).show(); stopCalibration(true) } }
        ).also { it.start() }
        updateSoftkeys(force = true)
    }

    private fun stopCalibration(clear: Boolean) {
        calibration?.stop()
        calibration = null
        if (clear) synchronized(calibrationPoints) { calibrationPoints.clear() }
        recorderMeter?.points = emptyList()
        updateSoftkeys(force = true)
    }

    private fun startRecording() {
        if (calibration?.isRunning() == true) stopCalibration(true)
        val a = access ?: return
        val series = selectedSeries ?: return
        val startEpoch = System.currentTimeMillis()
        val intent = Intent(this, RecordingService::class.java).apply {
            action = RecordingService.ACTION_START
            putExtra(RecordingService.EXTRA_TREE_URI, a.treeUri.toString())
            putExtra(RecordingService.EXTRA_SERIES_ID, series.node.documentId)
            putExtra(RecordingService.EXTRA_SERIES_NAME, series.name)
            putExtra(RecordingService.EXTRA_START_EPOCH, startEpoch)
        }
        startForegroundService(intent)
        updateSoftkeys(force = true)
    }

    private fun finishRecording() {
        val svc = recordingService ?: return
        showBoot("Finishing recording…")
        worker.execute {
            val result = runCatching { svc.finishRecording() }.getOrNull()
            ui.post {
                if (result == null) {
                    Toast.makeText(this, "Recording was interrupted.", Toast.LENGTH_LONG).show()
                    svc.shutdown()
                    initializeStorage()
                    return@post
                }
                svc.shutdown()
                if (result.interrupted) {
                    val detail = result.failure?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: "."
                    Toast.makeText(this, "Recording was interrupted$detail", Toast.LENGTH_LONG).show()
                }
                if (result.frameCount <= 0 || result.completeBytes <= 0) {
                    runCatching { DocumentsContract.deleteDocument(contentResolver, result.tempUri) }
                    if (!result.interrupted) Toast.makeText(this, "No audio was recorded.", Toast.LENGTH_LONG).show()
                    showRecorder()
                } else {
                    pendingRecordingResult = result
                    showRecordingTitle()
                }
            }
        }
    }

    private fun updateRecorderUi() {
        if (view != AppView.RECORDER) return
        val snap = recordingService?.snapshot()
        val calibrationRunning = calibration?.isRunning() == true
        val points = if (snap?.mode in setOf(RecordingService.Mode.STARTING, RecordingService.Mode.RECORDING, RecordingService.Mode.PAUSED, RecordingService.Mode.FINISHING)) {
            snap?.meter ?: emptyList()
        } else synchronized(calibrationPoints) { calibrationPoints.toList() }
        recorderMeter?.points = points
        recorderMeter?.paused = snap?.mode == RecordingService.Mode.PAUSED
        val seriesName = snap?.seriesName ?: selectedSeries?.name ?: "Recorder"
        recorderHeader?.text = seriesName
        val start = if ((snap?.startEpochMs ?: 0L) > 0 && snap?.mode != RecordingService.Mode.IDLE) {
            Instant.ofEpochMilli(snap!!.startEpochMs).atZone(ZoneId.systemDefault()).toLocalDateTime()
        } else LocalDateTime.now()
        recorderSubhead?.text = if (snap?.mode in setOf(RecordingService.Mode.STARTING, RecordingService.Mode.RECORDING, RecordingService.Mode.PAUSED, RecordingService.Mode.FINISHING)) {
            RecorderNames.fullUiFormatter.format(start)
        } else RecorderNames.dateWithWeekdayFormatter.format(start)
        recorderCurrentTime?.text = "Current time: ${LocalDateTime.now().format(CLOCK_FORMATTER)}"
        recorderElapsed?.text = "Recorded: ${Formatters.longClock(snap?.elapsedMs ?: 0L)}\u00A0"
        if (snap?.mode == RecordingService.Mode.FAILED) {
            Toast.makeText(this, "Recording was interrupted${snap.failure?.let { ": $it" } ?: "."}", Toast.LENGTH_LONG).show()
            recordingService?.shutdown()
            initializeStorage()
            return
        }
        if (!calibrationRunning) updateSoftkeys()
    }

    private fun showRecordingTitle() {
        val result = pendingRecordingResult ?: return
        view = AppView.RECORD_TITLE
        val start = Instant.ofEpochMilli(result.startEpochMs).atZone(ZoneId.systemDefault()).toLocalDateTime()
        val field = EditText(this).apply { hint = "Title"; setSingleLine(true); textSize = 18f }
        val validation = TextView(this).apply { setTextColor(Color.rgb(170, 0, 0)); textSize = 12f }
        titleField = field; validationText = validation
        field.addTextChangedListener(simpleWatcher { updateTitleValidation(start, result.seriesName) })
        setRoot(vertical(header("Recording title"), field, validation))
        field.requestFocus()
        updateTitleValidation(start, result.seriesName)
    }

    private fun updateTitleValidation(start: LocalDateTime, series: String) {
        val raw = titleField?.text?.toString() ?: ""
        val error = RecorderNames.validateTitle(raw) ?: if (!RecorderNames.isFinalNameWithinLimit(series, start, raw)) "Title is too long." else null
        validationText?.text = error ?: ""
        updateSoftkeys(force = true)
    }

    private fun savePendingRecording() {
        val result = pendingRecordingResult ?: return
        val a = access ?: return
        val series = store.nodeForId(a.treeUri, result.seriesDocumentId) ?: return
        val title = titleField?.text?.toString() ?: ""
        val start = Instant.ofEpochMilli(result.startEpochMs).atZone(ZoneId.systemDefault()).toLocalDateTime()
        if (RecorderNames.validateTitle(title) != null || !RecorderNames.isFinalNameWithinLimit(series.name, start, title)) return
        val temp = DocumentNode(result.tempName, DocumentsContract.getDocumentId(result.tempUri), result.tempUri, "audio/mpeg")
        showBoot("Saving…")
        worker.execute {
            runCatching { store.finalSave(a, series, temp, result.completeBytes, start, title) }
                .onSuccess {
                    ui.post {
                        pendingRecordingResult = null
                        selectedSeries = selectedSeries?.copy(node = series) ?: SeriesItem(series, 0, null)
                        showRecordings(0)
                    }
                }
                .onFailure { t -> ui.post { showError("Could not save recording. The temporary recording was kept so you can retry.", t); pendingRecordingResult = result; showRecordingTitle() } }
        }
    }

    private fun discardPendingRecording() {
        val result = pendingRecordingResult ?: return
        runCatching { DocumentsContract.deleteDocument(contentResolver, result.tempUri) }
        pendingRecordingResult = null
        showRecordings()
    }

    private fun showNextRecovery() {
        if (pendingRecovery.isEmpty()) { currentRecovery = null; showSeries(); return }
        currentRecovery = pendingRecovery.removeAt(0)
        val rec = currentRecovery ?: return
        view = AppView.RECOVERY
        selectedSeries = SeriesItem(rec.series, 0, null)
        val message = TextView(this).apply {
            text = "Interrupted recording from ${RecorderNames.fullUiFormatter.format(rec.timestamp)} found in ${rec.series.name}." +
                if (rec.frameCount > 0) " Please enter a title." else " No complete audio frames were found."
            setPadding(dp(6), dp(8), dp(6), dp(8))
        }
        if (rec.frameCount <= 0) {
            titleField = null; validationText = null
            setRoot(vertical(header("Interrupted recording"), message))
        } else {
            val field = EditText(this).apply { hint = "Title"; setSingleLine(true); textSize = 18f }
            val validation = TextView(this).apply { setTextColor(Color.rgb(170, 0, 0)); textSize = 12f }
            titleField = field; validationText = validation
            field.addTextChangedListener(simpleWatcher { updateTitleValidation(rec.timestamp, rec.series.name) })
            setRoot(vertical(header("Interrupted recording"), message, field, validation))
            field.requestFocus()
            updateTitleValidation(rec.timestamp, rec.series.name)
        }
        updateSoftkeys(force = true)
    }

    private fun discardRecovery() {
        val rec = currentRecovery ?: return
        worker.execute {
            runCatching { store.deleteFile(rec.node) }
            ui.post { showNextRecovery() }
        }
    }

    private fun saveRecovery() {
        val rec = currentRecovery ?: return
        val a = access ?: return
        if (rec.frameCount <= 0) return
        val raw = titleField?.text?.toString() ?: ""
        if (RecorderNames.validateTitle(raw) != null || !RecorderNames.isFinalNameWithinLimit(rec.series.name, rec.timestamp, raw)) return
        showBoot("Saving…")
        worker.execute {
            runCatching { store.finalSave(a, rec.series, rec.node, rec.completeBytes, rec.timestamp, raw) }
                .onSuccess { ui.post { showNextRecovery() } }
                .onFailure { t -> ui.post { showError("Could not save interrupted recording. TEMP was kept.", t); pendingRecovery.add(0, rec); showNextRecovery() } }
        }
    }

    private fun startPlayer(index: Int) {
        val service = playbackService ?: return
        val tracks = recordingItems.map { PlaybackTrack(it.node.uri.toString(), it.title, it.seriesName, it.durationMs) }
        service.startSession(tracks, index, autoplay = true)
        showPlayer(reuseSession = true)
    }

    private fun showPlayer(reuseSession: Boolean) {
        val service = playbackService ?: return
        if (!reuseSession || !service.hasSession()) return
        view = AppView.PLAYER

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(5), dp(8), dp(4))
        }
        val title = TextView(this).apply {
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.MARQUEE
            marqueeRepeatLimit = -1
            isSelected = true
        }
        val series = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.DKGRAY)
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.MARQUEE
            marqueeRepeatLimit = -1
            isSelected = true
            setPadding(0, 0, 0, dp(4))
        }
        playerTitle = title
        playerSeries = series
        body.addView(title, LinearLayout.LayoutParams(-1, -2))
        body.addView(series, LinearLayout.LayoutParams(-1, -2))

        val topButtons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        playerSleep = compactButton("Sleep Off") { showSleepDialog() }
        playerRepeat = compactButton("Repeat Off") { showRepeatDialog() }
        playerSpeed = compactButton("1x") { showSpeedDialog() }
        topButtons.addView(playerSleep, playerRowWeight(dp(46), endMargin = 2))
        topButtons.addView(playerRepeat, playerRowWeight(dp(46), startMargin = 2, endMargin = 2))
        topButtons.addView(playerSpeed, playerRowWeight(dp(46), startMargin = 2))
        body.addView(topButtons, LinearLayout.LayoutParams(-1, dp(46)))

        val timeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(3), 0, 0)
        }
        playerElapsed = TextView(this).apply { gravity = Gravity.START; textSize = 11f }
        playerRemaining = TextView(this).apply { gravity = Gravity.END; textSize = 11f }
        timeRow.addView(playerElapsed, weight())
        timeRow.addView(playerRemaining, weight())
        body.addView(timeRow, LinearLayout.LayoutParams(-1, -2))

        playerWiper = SeekBar(this).apply {
            max = 1000
            isFocusable = true
            setPadding(dp(8), 0, dp(8), 0)
        }
        body.addView(playerWiper, LinearLayout.LayoutParams(-1, dp(32)).apply {
            topMargin = dp(4)
            marginStart = dp(4)
            marginEnd = dp(4)
        })

        playerSummary = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 13f
            setTextColor(Color.DKGRAY)
            setPadding(0, 0, 0, dp(4))
        }
        body.addView(playerSummary, LinearLayout.LayoutParams(-1, -2))

        playerPlay = compactButton("Play") { service.togglePlayPause(); updatePlayerUi() }.apply { textSize = 15f }
        body.addView(playerPlay, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(4) })

        playerPrevious = compactButton("Previous") { service.previous(); updatePlayerUi() }
        playerNext = compactButton("Next") { service.next(); updatePlayerUi() }
        val trackRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        trackRow.addView(playerPrevious, playerRowWeight(dp(44), endMargin = 2))
        trackRow.addView(playerNext, playerRowWeight(dp(44), startMargin = 2))
        body.addView(trackRow, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(3) })

        playerRewind = compactButton("◀ 10s") { service.seekRelative(-service.rewindIntervalMs()); updatePlayerUi() }.apply {
            setOnLongClickListener { showIntervalDialog(true); true }
        }
        playerForward = compactButton("10s ▶") { service.seekRelative(service.forwardIntervalMs()); updatePlayerUi() }.apply {
            setOnLongClickListener { showIntervalDialog(false); true }
        }
        val seekRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        seekRow.addView(playerRewind, playerRowWeight(dp(44), endMargin = 2))
        seekRow.addView(playerForward, playerRowWeight(dp(44), startMargin = 2))
        body.addView(seekRow, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(3) })

        setRoot(body)
        playerPlay?.requestFocus()
        updatePlayerUi()
        updateSoftkeys(force = true)
    }

    private fun updatePlayerUi() {
        val s = playbackService ?: return
        if (view != AppView.PLAYER || !s.hasSession()) return
        val track = s.currentTrack() ?: return
        val pos = s.positionMs(); val dur = s.durationMs().coerceAtLeast(track.durationMs)
        playerTitle?.text = track.title
        playerSeries?.text = track.series
        playerElapsed?.text = Formatters.longClock(pos)
        playerRemaining?.text = "-${Formatters.longClock((dur - pos).coerceAtLeast(0L))}"
        val pct = if (dur > 0) ((pos * 100L) / dur).coerceIn(0, 100) else 0
        playerSummary?.text = "${Formatters.words(dur)} - $pct%"
        playerWiper?.progress = if (dur > 0) ((pos * 1000L) / dur).toInt().coerceIn(0, 1000) else 0
        playerPlay?.text = if (s.isPlaying()) "Pause" else "Play"
        val enabled = s.queueSize() > 1
        playerPrevious?.isEnabled = enabled; playerNext?.isEnabled = enabled
        playerRewind?.text = "◀ ${shortInterval(s.rewindIntervalMs())}"
        playerForward?.text = "${shortInterval(s.forwardIntervalMs())} ▶"
        playerRepeat?.text = when (s.repeatMode()) { RepeatMode.OFF -> "Repeat Off"; RepeatMode.ONE -> "Repeat 1"; RepeatMode.ALL -> "Repeat All" }
        playerSpeed?.text = formatSpeed(s.playbackSpeed())
        val sleep = s.sleepRemainingMs()
        playerSleep?.text = if (sleep > 0) "Sleep ${Formatters.sleep(sleep)}" else "Sleep Off"
        updateSoftkeys()
    }

    private fun showControlsDialog() {
        val controls = "1  Rewind 10 seconds\n2  Previous recording\n3  Forward 10 seconds\n4  Rewind 1 minute\n5  Next recording\n6  Forward 1 minute\n7  Rewind 10 minutes\n8  Cycle repeat\n9  Forward 10 minutes\n*  Rewind 1 hour\n0  Sleep +10 minutes\n#  Forward 1 hour"
        AlertDialog.Builder(this).setTitle("Controls").setMessage(controls).setNegativeButton("Back", null).show()
    }

    private fun showSleepDialog() {
        val choices = arrayOf("Off", "10 minutes", "30 minutes", "1 hour", "2 hours", "3 hours", "4 hours", "8 hours", "12 hours")
        val minutes = intArrayOf(0, 10, 30, 60, 120, 180, 240, 480, 720)
        AlertDialog.Builder(this).setTitle("Sleep").setItems(choices) { _, which -> playbackService?.setSleepMinutes(minutes[which]); updatePlayerUi() }.setNegativeButton("Back", null).show()
    }

    private fun showRepeatDialog() {
        val choices = arrayOf("Off", "Repeat 1", "Repeat All")
        AlertDialog.Builder(this).setTitle("Repeat").setItems(choices) { _, which ->
            val mode = RepeatMode.entries[which]
            playbackService?.setRepeatMode(mode); vibrateRepeatMode(mode); updatePlayerUi()
        }.setNegativeButton("Back", null).show()
    }

    private fun showSpeedDialog() {
        val speeds = floatArrayOf(.5f, .75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 3f, 4f)
        val labels = speeds.map(::formatSpeed).toTypedArray()
        AlertDialog.Builder(this).setTitle("Playback speed").setItems(labels) { _, which -> playbackService?.setPlaybackSpeed(speeds[which]); updatePlayerUi() }.setNegativeButton("Back", null).show()
    }

    private fun showIntervalDialog(rewind: Boolean) {
        val values = longArrayOf(10_000L, 60_000L, 600_000L, 3_600_000L)
        val labels = arrayOf("10 seconds", "1 minute", "10 minutes", "1 hour")
        AlertDialog.Builder(this).setTitle(if (rewind) "Rewind interval" else "Fast-forward interval").setItems(labels) { _, which ->
            if (rewind) playbackService?.setRewindIntervalMs(values[which]) else playbackService?.setForwardIntervalMs(values[which])
            updatePlayerUi()
        }.setNegativeButton("Back", null).show()
    }

    private fun destroyPlayerAndReturn() {
        playbackService?.destroySession()
        showRecordings()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val keyName = KeyEvent.keyCodeToString(event.keyCode)
        if (keyName == "KEYCODE_MULTIFUNC_LEFT") return true
        val soft = when {
            event.keyCode == KeyEvent.KEYCODE_MENU || event.keyCode == KeyEvent.KEYCODE_SOFT_LEFT -> SoftKey.LEFT
            keyName == "KEYCODE_MULTIFUNC_CENTER" -> SoftKey.CENTER
            keyName == "KEYCODE_MULTIFUNC_RIGHT" -> SoftKey.RIGHT
            else -> null
        }
        if (soft != null) {
            if (event.action == KeyEvent.ACTION_UP) handleSoftkey(soft)
            return true
        }
        if (view == AppView.PLAYER && handlePlayerKey(event)) return true
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) handleBackKey()
            // Consume the matching key-up and held-repeat events too. Otherwise the
            // framework can perform a second Back action after Recorder has used
            // the initial key-down as text backspace/navigation.
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun handleBackKey(): Boolean {
        return when (view) {
            AppView.STORAGE_SETUP -> { finish(); true }
            AppView.SERIES -> { finish(); true }
            AppView.SERIES_EDIT -> {
                val f = seriesEditField
                if (f?.hasFocus() == true) { backspace(f); true } else { showSeries(); true }
            }
            AppView.RECORDINGS -> {
                val pos = seriesItems.indexOfFirst { it.node.documentId == selectedSeries?.node?.documentId }.coerceAtLeast(0)
                showSeries(pos); true
            }
            AppView.RECORDER -> {
                val active = calibration?.isRunning() == true || recordingService?.snapshot()?.mode in setOf(RecordingService.Mode.STARTING, RecordingService.Mode.RECORDING, RecordingService.Mode.PAUSED, RecordingService.Mode.FINISHING)
                if (!active) showRecordings()
                true
            }
            AppView.RECORD_TITLE, AppView.RECOVERY -> {
                val f = titleField
                if (f?.hasFocus() == true) backspace(f)
                true
            }
            AppView.PLAYER -> { destroyPlayerAndReturn(); true }
            else -> true
        }
    }

    private fun backspace(field: EditText) {
        val start = field.selectionStart
        val end = field.selectionEnd
        if (start != end) field.text.delete(minOf(start, end), maxOf(start, end))
        else if (start > 0) field.text.delete(start - 1, start)
    }

    private fun handleSoftkey(slot: SoftKey) {
        when (view) {
            AppView.STORAGE_SETUP -> when (slot) {
                SoftKey.LEFT -> finish()
                SoftKey.RIGHT -> launchStoragePicker()
                SoftKey.CENTER -> Unit
            }
            AppView.SERIES -> when (slot) {
                SoftKey.LEFT -> deleteSelectedSeries()
                SoftKey.CENTER -> selectedSeriesFromList()?.takeIf { !it.miscellaneous }?.let(::showSeriesEdit)
                SoftKey.RIGHT -> showSeriesEdit(null)
            }
            AppView.SERIES_EDIT -> when (slot) {
                SoftKey.LEFT -> if (editingSeries != null) deleteEditingSeries()
                SoftKey.CENTER -> showSeries(seriesItems.indexOfFirst { it.node.documentId == editingSeries?.node?.documentId }.coerceAtLeast(0))
                SoftKey.RIGHT -> saveSeriesEdit()
            }
            AppView.RECORDINGS -> when (slot) {
                SoftKey.LEFT -> deleteSelectedRecording()
                SoftKey.CENTER -> recordingItems.getOrNull(selectedListPosition())?.let { startPlayer(selectedListPosition()) }
                SoftKey.RIGHT -> showRecorder()
            }
            AppView.RECORDER -> handleRecorderSoftkey(slot)
            AppView.RECORD_TITLE -> when (slot) { SoftKey.LEFT -> discardPendingRecording(); SoftKey.RIGHT -> savePendingRecording(); else -> Unit }
            AppView.RECOVERY -> when (slot) { SoftKey.LEFT -> discardRecovery(); SoftKey.RIGHT -> saveRecovery(); else -> Unit }
            AppView.PLAYER -> when (slot) { SoftKey.LEFT -> showControlsDialog(); SoftKey.CENTER -> playbackService?.togglePlayPause(); SoftKey.RIGHT -> showSleepDialog() }
            else -> Unit
        }
        updateSoftkeys(force = true)
    }

    private fun handleRecorderSoftkey(slot: SoftKey) {
        val s = recordingService?.snapshot()
        val active = s?.mode in setOf(RecordingService.Mode.STARTING, RecordingService.Mode.RECORDING, RecordingService.Mode.PAUSED, RecordingService.Mode.FINISHING)
        when (slot) {
            SoftKey.LEFT -> if (!active && calibration?.isRunning() != true) showRecordings()
            SoftKey.CENTER -> when {
                active && s?.mode == RecordingService.Mode.RECORDING -> recordingService?.pauseRecording()
                active && s?.mode == RecordingService.Mode.PAUSED -> recordingService?.resumeRecording()
                active -> Unit
                calibration?.isRunning() == true -> stopCalibration(true)
                else -> ensureMicPermission(MicAction.CALIBRATE)
            }
            SoftKey.RIGHT -> if (active) finishRecording() else ensureMicPermission(MicAction.RECORD)
        }
    }

    private fun handlePlayerKey(event: KeyEvent): Boolean {
        if (playerWiper?.hasFocus() == true && (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT || event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)) return handleWiperKey(event)
        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount != 0) return false
        val s = playbackService ?: return false
        val target: View? = when (event.keyCode) {
            KeyEvent.KEYCODE_1 -> { s.seekRelative(-10_000L); playerRewind }
            KeyEvent.KEYCODE_2 -> { s.previous(); playerPrevious }
            KeyEvent.KEYCODE_3 -> { s.seekRelative(10_000L); playerForward }
            KeyEvent.KEYCODE_4 -> { s.seekRelative(-60_000L); playerRewind }
            KeyEvent.KEYCODE_5 -> { s.next(); playerNext }
            KeyEvent.KEYCODE_6 -> { s.seekRelative(60_000L); playerForward }
            KeyEvent.KEYCODE_7 -> { s.seekRelative(-600_000L); playerRewind }
            KeyEvent.KEYCODE_8 -> { vibrateRepeatMode(s.cycleRepeat()); playerRepeat }
            KeyEvent.KEYCODE_9 -> { s.seekRelative(600_000L); playerForward }
            KeyEvent.KEYCODE_STAR -> { s.seekRelative(-3_600_000L); playerRewind }
            KeyEvent.KEYCODE_0 -> { s.addSleepMinutes(10); vibrate(longArrayOf(0L, 80L)); playerSleep }
            KeyEvent.KEYCODE_POUND -> { s.seekRelative(3_600_000L); playerForward }
            else -> return false
        }
        updatePlayerUi(); if (target?.isEnabled == true && target.isFocusable) target.requestFocus(); return true
    }

    private fun handleWiperKey(event: KeyEvent): Boolean {
        val direction = if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount > 0) return true
                val s = playbackService ?: return true
                val step = if (direction < 0) s.rewindIntervalMs() else s.forwardIntervalMs()
                s.seekRelative(direction * step)
                wiperWasPlaying = s.isPlaying(); wiperHoldActive = false
                val r = object : Runnable {
                    override fun run() {
                        val current = playbackService ?: return
                        if (!wiperHoldActive) { wiperHoldActive = true; if (wiperWasPlaying) current.pause() }
                        val interval = if (direction < 0) current.rewindIntervalMs() else current.forwardIntervalMs()
                        current.seekRelative(direction * interval)
                        ui.postDelayed(this, 1000L)
                    }
                }
                wiperHoldRunnable = r; ui.postDelayed(r, 700L); return true
            }
            KeyEvent.ACTION_UP -> {
                wiperHoldRunnable?.let(ui::removeCallbacks); wiperHoldRunnable = null
                if (wiperHoldActive && wiperWasPlaying) playbackService?.play()
                wiperHoldActive = false; wiperWasPlaying = false; return true
            }
        }
        return true
    }

    private fun vibrateRepeatMode(mode: RepeatMode) {
        val p = when (mode) {
            RepeatMode.OFF -> longArrayOf(0L, 300L)
            RepeatMode.ONE -> longArrayOf(0L, 300L, 150L, 300L)
            RepeatMode.ALL -> longArrayOf(0L, 80L, 80L, 80L, 80L, 80L, 250L, 80L, 80L, 80L, 80L, 80L)
        }
        vibrate(p)
    }

    private fun vibrate(pattern: LongArray) {
        getSystemService(Vibrator::class.java)?.takeIf { it.hasVibrator() }?.vibrate(VibrationEffect.createWaveform(pattern, -1))
    }

    private fun softkeyLabels(): Triple<String, String, String> = when (view) {
        AppView.STORAGE_SETUP -> Triple("Exit", "", "Set up")
        AppView.SERIES -> {
            val item = selectedSeriesFromList()
            Triple(if (item != null && !item.miscellaneous) "Delete" else "", if (item != null && !item.miscellaneous) "Edit" else "", "New")
        }
        AppView.SERIES_EDIT -> Triple(if (editingSeries != null) "Delete" else "", "Cancel", if (seriesEditValid()) "Save" else "")
        AppView.RECORDINGS -> Triple(if (recordingItems.isNotEmpty()) "Delete" else "", if (recordingItems.isNotEmpty()) "Play" else "", "New")
        AppView.RECORDER -> {
            val s = recordingService?.snapshot()
            val active = s?.mode in setOf(RecordingService.Mode.STARTING, RecordingService.Mode.RECORDING, RecordingService.Mode.PAUSED, RecordingService.Mode.FINISHING)
            val left = if (active || calibration?.isRunning() == true) "" else "Cancel"
            val center = when {
                s?.mode == RecordingService.Mode.RECORDING -> "Pause"
                s?.mode == RecordingService.Mode.PAUSED -> "Resume"
                active -> ""
                calibration?.isRunning() == true -> "Done"
                else -> "Calibrate"
            }
            Triple(left, center, if (active) "Finish" else "Record")
        }
        AppView.RECORD_TITLE -> {
            val r = pendingRecordingResult
            val raw = titleField?.text?.toString() ?: ""
            val start = r?.let { Instant.ofEpochMilli(it.startEpochMs).atZone(ZoneId.systemDefault()).toLocalDateTime() }
            val valid = r != null && start != null && RecorderNames.validateTitle(raw) == null && RecorderNames.isFinalNameWithinLimit(r.seriesName, start, raw)
            Triple("Discard", "", if (valid) "Save" else "")
        }
        AppView.RECOVERY -> {
            val rec = currentRecovery
            val raw = titleField?.text?.toString() ?: ""
            val valid = rec != null && rec.frameCount > 0 && RecorderNames.validateTitle(raw) == null && RecorderNames.isFinalNameWithinLimit(rec.series.name, rec.timestamp, raw)
            Triple("Discard", "", if (valid) "Save" else "")
        }
        AppView.PLAYER -> Triple("Controls", if (playbackService?.isPlaying() == true) "Pause" else "Play", "Sleep")
        else -> Triple("", "", "")
    }

    private fun updateSoftkeys(force: Boolean = false) {
        val labels = softkeyLabels()
        if (!force && labels == lastSoftkeys) return
        lastSoftkeys = labels
        broadcastSoftkeys(labels)
    }

    private fun broadcastSoftkeys(labels: Triple<String, String, String>) {
        sendBroadcast(Intent(SONIM_SOFTKEY_ACTION).apply {
            putExtra("left", labels.first)
            putExtra("center", labels.second)
            putExtra("right", labels.third)
            putExtra("from_package", packageName)
        })
    }

    private fun selectedListPosition(): Int {
        val list = listView ?: return 0
        val pos = list.selectedItemPosition
        return if (pos >= 0) pos else 0
    }

    private fun selectedSeriesFromList(): SeriesItem? = seriesItems.getOrNull(selectedListPosition())

    private fun setRoot(child: View) {
        root.removeAllViews()
        root.addView(child, FrameLayout.LayoutParams(-1, -1))
        updateSoftkeys(force = true)
    }

    private fun header(text: String): TextView = TextView(this).apply {
        this.text = text; textSize = 19f; setTextColor(Color.BLACK); setBackgroundColor(Color.WHITE); gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(6), dp(4), dp(6), dp(4)); isSingleLine = true; ellipsize = android.text.TextUtils.TruncateAt.MARQUEE; marqueeRepeatLimit = -1; isSelected = true
    }

    private fun listHeader(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 19f
        setTextColor(Color.WHITE)
        setBackgroundColor(Color.rgb(0x4f, 0x6f, 0x8f))
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        minimumHeight = dp(48)
        setPadding(dp(12), 0, dp(12), 0)
        isSingleLine = true
        ellipsize = android.text.TextUtils.TruncateAt.MARQUEE
        marqueeRepeatLimit = -1
        isSelected = true
    }

    private fun vertical(vararg views: View, weights: FloatArray? = null): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        views.forEachIndexed { i, v ->
            val fillLast = weights == null && i == views.lastIndex &&
                (v is ListView || v is FrameLayout || v is RecorderMeterView)
            val itemWeight = weights?.getOrNull(i) ?: if (fillLast) 1f else 0f
            addView(v, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                if (itemWeight > 0f) 0 else LinearLayout.LayoutParams.WRAP_CONTENT,
                itemWeight,
            ))
        }
    }

    private fun compactButton(label: String, click: () -> Unit): Button = Button(this).apply {
        text = label; textSize = 11f; minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = dp(42); setPadding(dp(2), dp(7), dp(2), dp(7)); setOnClickListener { click() }
    }

    private fun weight(value: Float = 1f) = LinearLayout.LayoutParams(0, -2, value)
    private fun playerRowWeight(height: Int, startMargin: Int = 0, endMargin: Int = 0) =
        LinearLayout.LayoutParams(0, height, 1f).apply {
            marginStart = dp(startMargin)
            marginEnd = dp(endMargin)
        }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private fun simpleWatcher(action: () -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = action()
        override fun afterTextChanged(s: Editable?) = Unit
    }

    private fun shortInterval(ms: Long): String = when (ms) { 10_000L -> "10s"; 60_000L -> "1m"; 600_000L -> "10m"; 3_600_000L -> "1h"; else -> "${ms / 1000}s" }
    private fun formatSpeed(value: Float): String = if (value == value.toInt().toFloat()) "${value.toInt()}x" else "${value}x"

    private fun showError(message: String, t: Throwable? = null) {
        AlertDialog.Builder(this).setTitle("Soneme Recorder").setMessage(if (t?.message.isNullOrBlank()) message else "$message\n\n${t?.message}").setPositiveButton("OK", null).show()
    }

    private inner class SeriesAdapter(private val items: List<SeriesItem>) : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val item = items[position]
            return LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL; minimumHeight = dp(66); setPadding(dp(12), dp(8), dp(12), dp(8)); isFocusable = false
                addView(TextView(this@MainActivity).apply { text = item.name; textSize = 17f; isSingleLine = true; ellipsize = android.text.TextUtils.TruncateAt.MARQUEE })
                addView(TextView(this@MainActivity).apply {
                    val count = "${item.recordingCount} recording${if (item.recordingCount == 1) "" else "s"}"
                    text = item.newest?.let { "$count · ${RecorderNames.dateUiFormatter.format(it)}" } ?: count
                    textSize = 13f
                })
            }
        }
    }

    private inner class RecordingAdapter(private val items: List<RecordingItem>) : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val item = items[position]
            val row = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL; minimumHeight = dp(66); setPadding(dp(12), dp(8), dp(12), dp(8)) }
            val text = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
            text.addView(TextView(this@MainActivity).apply { this.text = item.title; textSize = 17f; isSingleLine = true; ellipsize = android.text.TextUtils.TruncateAt.MARQUEE })
            text.addView(TextView(this@MainActivity).apply { this.text = RecorderNames.fullUiFormatter.format(item.timestamp); textSize = 13f; isSingleLine = true })
            row.addView(text, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(TextView(this@MainActivity).apply { this.text = Formatters.longClock(item.durationMs); gravity = Gravity.END; textSize = 14f }, LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.CENTER_VERTICAL; marginStart = dp(8) })
            return row
        }
    }

    companion object {
        private val CLOCK_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
        private const val REQUEST_TREE = 100
        private const val REQUEST_MIC = 101
        private const val SONIM_SOFTKEY_ACTION = "android.intent.action.CHANGE_NAV_BAR"
    }
}
