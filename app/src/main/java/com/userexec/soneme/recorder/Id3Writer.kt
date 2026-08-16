package com.userexec.soneme.recorder

import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.time.LocalDateTime

object Id3Writer {
    fun write(output: OutputStream, series: String, title: String, start: LocalDateTime) {
        val frames = listOf(
            textFrame("TIT2", "$title - ${RecorderNames.dateUiFormatter.format(start)}"),
            textFrame("TPE1", series),
            textFrame("TALB", "Soneme Recorder"),
            textFrame("TDRC", RecorderNames.tdrcFormatter.format(start)),
        )
        val size = frames.sumOf { it.size }
        output.write(byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 4, 0, 0))
        output.write(synchsafe(size))
        frames.forEach(output::write)
    }

    private fun textFrame(id: String, value: String): ByteArray {
        val text = value.toByteArray(StandardCharsets.UTF_8)
        val payload = ByteArray(text.size + 1)
        payload[0] = 3 // UTF-8
        System.arraycopy(text, 0, payload, 1, text.size)
        val out = ByteArray(10 + payload.size)
        val idBytes = id.toByteArray(StandardCharsets.US_ASCII)
        System.arraycopy(idBytes, 0, out, 0, 4)
        val size = synchsafe(payload.size)
        System.arraycopy(size, 0, out, 4, 4)
        out[8] = 0
        out[9] = 0
        System.arraycopy(payload, 0, out, 10, payload.size)
        return out
    }

    private fun synchsafe(value: Int): ByteArray = byteArrayOf(
        ((value ushr 21) and 0x7f).toByte(),
        ((value ushr 14) and 0x7f).toByte(),
        ((value ushr 7) and 0x7f).toByte(),
        (value and 0x7f).toByte(),
    )
}
