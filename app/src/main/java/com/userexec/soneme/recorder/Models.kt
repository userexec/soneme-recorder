package com.userexec.soneme.recorder

import android.net.Uri
import java.time.LocalDateTime

data class DocumentNode(
    val name: String,
    val documentId: String,
    val uri: Uri,
    val mimeType: String,
)

data class SeriesItem(
    val node: DocumentNode,
    val recordingCount: Int,
    val newest: LocalDateTime?,
) {
    val name: String get() = node.name
    val miscellaneous: Boolean get() = name.equals("Miscellaneous", ignoreCase = true)
}

data class RecordingItem(
    val node: DocumentNode,
    val seriesName: String,
    val title: String,
    val timestamp: LocalDateTime,
    val durationMs: Long,
)

data class TempRecording(
    val node: DocumentNode,
    val series: DocumentNode,
    val timestamp: LocalDateTime,
    val completeBytes: Long,
    val frameCount: Int,
)

data class MeterPoint(val elapsedRealtimeMs: Long, val dbfs: Float)

data class RecordingRequest(
    val treeUri: String,
    val seriesDocumentId: String,
    val seriesName: String,
    val startEpochMs: Long,
)

data class RecordingResult(
    val tempUri: Uri,
    val tempName: String,
    val seriesDocumentId: String,
    val seriesName: String,
    val startEpochMs: Long,
    val completeBytes: Long,
    val frameCount: Int,
    val interrupted: Boolean = false,
    val failure: String? = null,
)

data class PlaybackTrack(
    val uri: String,
    val title: String,
    val series: String,
    val durationMs: Long,
)

enum class RepeatMode { OFF, ONE, ALL }
