package com.userexec.soneme.recorder

import java.io.BufferedInputStream
import java.io.InputStream

object Mp3FrameScanner {
    data class Result(val completeBytes: Long, val frameCount: Int)

    fun scan(input: InputStream): Result {
        val source = BufferedInputStream(input, 32 * 1024)
        var total = 0L
        var frames = 0
        while (true) {
            source.mark(4)
            val h = ByteArray(4)
            var got = 0
            while (got < 4) {
                val n = source.read(h, got, 4 - got)
                if (n < 0) return Result(total, frames)
                got += n
            }
            val frameLength = frameLength(h) ?: return Result(total, frames)
            var remaining = frameLength - 4
            val scratch = ByteArray(minOf(8192, remaining.coerceAtLeast(1)))
            while (remaining > 0) {
                val n = source.read(scratch, 0, minOf(scratch.size, remaining))
                if (n < 0) return Result(total, frames)
                remaining -= n
            }
            total += frameLength.toLong()
            frames++
        }
    }

    private fun frameLength(h: ByteArray): Int? {
        val b0 = h[0].toInt() and 0xff
        val b1 = h[1].toInt() and 0xff
        val b2 = h[2].toInt() and 0xff
        if (b0 != 0xff || (b1 and 0xe0) != 0xe0) return null
        val version = (b1 ushr 3) and 0x03
        val layer = (b1 ushr 1) and 0x03
        if (version != 0x03 || layer != 0x01) return null // MPEG-1 Layer III: Recorder's own TEMP format.
        val bitrateIndex = (b2 ushr 4) and 0x0f
        val sampleRateIndex = (b2 ushr 2) and 0x03
        if (bitrateIndex == 0 || bitrateIndex == 15 || sampleRateIndex == 3) return null
        val bitrates = intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320)
        val sampleRates = intArrayOf(44100, 48000, 32000)
        val bitrate = bitrates[bitrateIndex] * 1000
        val sampleRate = sampleRates[sampleRateIndex]
        val padding = (b2 ushr 1) and 1
        val length = (144L * bitrate / sampleRate + padding).toInt()
        return length.takeIf { it >= 24 }
    }
}
