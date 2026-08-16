package com.userexec.soneme.recorder

import android.content.ContentResolver
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import java.io.FileNotFoundException
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

class RecorderStore(private val context: Context) {
    private val resolver: ContentResolver = context.contentResolver
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    data class RootAccess(val treeUri: Uri, val rootDocumentId: String)

    fun savedAccess(): RootAccess? {
        val tree = prefs.getString(KEY_TREE_URI, null) ?: return null
        val rootId = prefs.getString(KEY_ROOT_ID, null) ?: return null
        return RootAccess(Uri.parse(tree), rootId)
    }

    fun clearSavedAccess() {
        prefs.edit().remove(KEY_TREE_URI).remove(KEY_ROOT_ID).apply()
    }

    fun resolveAndPersist(selectedTree: Uri): RootAccess {
        val selectedId = DocumentsContract.getTreeDocumentId(selectedTree)
        val selected = nodeForId(selectedTree, selectedId)
            ?: throw FileNotFoundException("Selected storage folder is unavailable")
        val root = when {
            selected.name.equals(ROOT_NAME, ignoreCase = true) -> selected
            else -> listChildren(selectedTree, selected.documentId)
                .firstOrNull { it.isDirectory && it.name.equals(ROOT_NAME, ignoreCase = true) }
                ?: createDirectory(selectedTree, selected.documentId, ROOT_NAME)
        }
        prefs.edit().putString(KEY_TREE_URI, selectedTree.toString()).putString(KEY_ROOT_ID, root.documentId).apply()
        return RootAccess(selectedTree, root.documentId)
    }

    fun verify(access: RootAccess): Boolean = try {
        nodeForId(access.treeUri, access.rootDocumentId)?.isDirectory == true
    } catch (_: Exception) { false }

    fun rootNode(access: RootAccess): DocumentNode = nodeForId(access.treeUri, access.rootDocumentId)
        ?: throw FileNotFoundException("SonemeRecorder folder is unavailable")

    fun ensureMiscellaneous(access: RootAccess): DocumentNode {
        val existing = listSeriesNodes(access).firstOrNull { it.name.equals(MISC, ignoreCase = true) }
        return existing ?: createDirectory(access.treeUri, access.rootDocumentId, MISC)
    }

    fun listSeries(access: RootAccess): List<SeriesItem> {
        val nodes = listSeriesNodes(access)
        return nodes.map { series ->
            val recordings = listRecordings(access, series)
            SeriesItem(series, recordings.size, recordings.maxOfOrNull { it.timestamp })
        }.sortedWith(compareBy<SeriesItem> { it.miscellaneous }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    }

    fun listSeriesNodes(access: RootAccess): List<DocumentNode> =
        listChildren(access.treeUri, access.rootDocumentId).filter { it.isDirectory }

    fun listRecordings(access: RootAccess, series: DocumentNode): List<RecordingItem> {
        return listChildren(access.treeUri, series.documentId).mapNotNull { node ->
            if (node.isDirectory) return@mapNotNull null
            val parsed = RecorderNames.parseFinished(node.name) ?: return@mapNotNull null
            val duration = mediaDuration(node.uri) ?: return@mapNotNull null
            RecordingItem(node, series.name, parsed.title, parsed.timestamp, duration)
        }.sortedByDescending { it.timestamp }
    }

    fun cleanupStaging(access: RootAccess) {
        listSeriesNodes(access).forEach { series ->
            listChildren(access.treeUri, series.documentId).forEach { child ->
                if (!child.isDirectory && RecorderNames.isStaging(child.name)) {
                    runCatching { DocumentsContract.deleteDocument(resolver, child.uri) }
                }
            }
        }
    }

    fun findInterrupted(access: RootAccess): List<TempRecording> {
        val out = mutableListOf<TempRecording>()
        listSeriesNodes(access).forEach { series ->
            listChildren(access.treeUri, series.documentId).forEach { child ->
                if (child.isDirectory) return@forEach
                val parsed = RecorderNames.parseTemp(child.name) ?: return@forEach
                val scan = resolver.openInputStream(child.uri)?.use(Mp3FrameScanner::scan) ?: return@forEach
                out += TempRecording(child, series, parsed.timestamp, scan.completeBytes, scan.frameCount)
            }
        }
        return out.sortedBy { it.timestamp }
    }

    fun createSeries(access: RootAccess, name: String): DocumentNode {
        if (listSeriesNodes(access).any { it.name.equals(name, ignoreCase = true) }) {
            throw IllegalStateException("A series with that name already exists")
        }
        return createDirectory(access.treeUri, access.rootDocumentId, name)
    }

    fun renameSeries(access: RootAccess, series: DocumentNode, newName: String): DocumentNode {
        if (listSeriesNodes(access).any {
                it.documentId != series.documentId && it.name.equals(newName, ignoreCase = true)
            }) {
            throw IllegalStateException("A series with that name already exists")
        }
        val renamedUri = DocumentsContract.renameDocument(resolver, series.uri, newName)
            ?: throw IllegalStateException("Provider refused rename")
        val renamed = nodeForUri(access.treeUri, renamedUri)
            ?: throw IllegalStateException("Renamed series is unavailable")
        if (renamed.name != newName) {
            // Some providers silently uniquify names. That violates Recorder's
            // filesystem-as-database grammar, so make a best effort to undo it.
            runCatching { DocumentsContract.renameDocument(resolver, renamed.uri, series.name) }
            throw IllegalStateException("Provider changed the requested series name")
        }
        return renamed
    }

    fun deleteRecursively(access: RootAccess, node: DocumentNode) {
        if (node.isDirectory) {
            listChildren(access.treeUri, node.documentId).forEach { deleteRecursively(access, it) }
        }
        if (!DocumentsContract.deleteDocument(resolver, node.uri)) throw IllegalStateException("Delete failed: ${node.name}")
    }

    fun deleteFile(node: DocumentNode) {
        if (!DocumentsContract.deleteDocument(resolver, node.uri)) throw IllegalStateException("Delete failed")
    }

    fun finalSave(
        access: RootAccess,
        series: DocumentNode,
        temp: DocumentNode,
        completeBytes: Long,
        start: LocalDateTime,
        rawTitle: String,
    ): DocumentNode {
        require(completeBytes > 0)
        val title = RecorderNames.titleForSave(rawTitle)
        val finalName = RecorderNames.finalName(series.name, start, title)
        val stagingName = RecorderNames.stagingName()
        val stage = createFile(access.treeUri, series.documentId, "audio/mpeg", stagingName)
        try {
            resolver.openOutputStream(stage.uri, "w")!!.use { output ->
                Id3Writer.write(output, series.name, title, start)
                resolver.openInputStream(temp.uri)!!.use { input ->
                    copyLimited(input, output, completeBytes)
                }
                output.flush()
            }
            listChildren(access.treeUri, series.documentId)
                .firstOrNull { !it.isDirectory && it.name == finalName && it.documentId != stage.documentId }
                ?.let { DocumentsContract.deleteDocument(resolver, it.uri) }
            val renamedUri = DocumentsContract.renameDocument(resolver, stage.uri, finalName)
                ?: throw IllegalStateException("Could not commit saved recording")
            val renamed = nodeForUri(access.treeUri, renamedUri)
                ?: throw IllegalStateException("Committed recording is unavailable")
            if (renamed.name != finalName) {
                // A provider may silently uniquify a collision. Never allow such a
                // file to masquerade as a successfully committed Recorder entry.
                runCatching { DocumentsContract.deleteDocument(resolver, renamed.uri) }
                throw IllegalStateException("Provider changed the final recording name")
            }
            if (!DocumentsContract.deleteDocument(resolver, temp.uri)) {
                throw IllegalStateException("Recording was saved, but TEMP cleanup failed")
            }
            return renamed
        } catch (t: Throwable) {
            // TEMP intentionally survives. Exact staging grammar makes any orphan safe to clean on next startup.
            throw t
        }
    }

    fun nodeForId(treeUri: Uri, documentId: String): DocumentNode? {
        val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
        return querySingle(uri)
    }

    fun childByName(treeUri: Uri, parentDocumentId: String, name: String): DocumentNode? =
        listChildren(treeUri, parentDocumentId).firstOrNull { it.name == name }

    fun createFile(treeUri: Uri, parentDocumentId: String, mime: String, name: String): DocumentNode {
        val parent = DocumentsContract.buildDocumentUriUsingTree(treeUri, parentDocumentId)
        val uri = DocumentsContract.createDocument(resolver, parent, mime, name)
            ?: throw IllegalStateException("Could not create $name")
        val node = nodeForUri(treeUri, uri) ?: throw IllegalStateException("Created file is unavailable")
        if (node.name != name) {
            runCatching { DocumentsContract.deleteDocument(resolver, node.uri) }
            throw IllegalStateException("A file named $name already exists")
        }
        return node
    }

    fun listChildren(treeUri: Uri, parentDocumentId: String): List<DocumentNode> {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocumentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        val result = mutableListOf<DocumentNode>()
        resolver.query(children, projection, null, null, null)?.use { c ->
            val idCol = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameCol = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val typeCol = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            while (c.moveToNext()) {
                val id = c.getString(idCol)
                result += DocumentNode(
                    c.getString(nameCol) ?: "",
                    id,
                    DocumentsContract.buildDocumentUriUsingTree(treeUri, id),
                    c.getString(typeCol) ?: "application/octet-stream",
                )
            }
        }
        return result
    }

    private fun createDirectory(treeUri: Uri, parentDocumentId: String, name: String): DocumentNode =
        createFile(treeUri, parentDocumentId, DocumentsContract.Document.MIME_TYPE_DIR, name)

    private fun querySingle(uri: Uri): DocumentNode? {
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        resolver.query(uri, projection, null, null, null)?.use { c ->
            if (!c.moveToFirst()) return null
            return DocumentNode(
                c.getString(c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)) ?: "",
                c.getString(c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)),
                uri,
                c.getString(c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)) ?: "application/octet-stream",
            )
        }
        return null
    }

    private fun nodeForUri(treeUri: Uri, uri: Uri): DocumentNode? = querySingle(uri)?.let { node ->
        val id = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull()
        if (id == null) node else node.copy(documentId = id, uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id))
    }

    private fun mediaDuration(uri: Uri): Long? = try {
        val mmr = MediaMetadataRetriever()
        try {
            mmr.setDataSource(context, uri)
            val hasAudio = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO)
            val duration = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            duration?.takeIf { it > 0L && (hasAudio == null || hasAudio.equals("yes", true)) }
        } finally { mmr.release() }
    } catch (_: Exception) { null }

    private fun copyLimited(input: java.io.InputStream, output: java.io.OutputStream, count: Long) {
        var remaining = count
        val buffer = ByteArray(32 * 1024)
        while (remaining > 0) {
            val n = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (n < 0) throw java.io.EOFException("TEMP ended before complete frame boundary")
            output.write(buffer, 0, n)
            remaining -= n
        }
    }

    private val DocumentNode.isDirectory: Boolean
        get() = mimeType == DocumentsContract.Document.MIME_TYPE_DIR

    companion object {
        const val ROOT_NAME = "SonemeRecorder"
        const val MISC = "Miscellaneous"
        private const val PREFS = "recorder_storage"
        private const val KEY_TREE_URI = "tree_uri"
        private const val KEY_ROOT_ID = "root_document_id"
    }
}
