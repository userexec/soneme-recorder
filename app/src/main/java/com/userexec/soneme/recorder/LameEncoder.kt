package com.userexec.soneme.recorder

class LameEncoder private constructor(private var handle: Long) : AutoCloseable {
    fun encode(samples: ShortArray, count: Int, output: ByteArray): Int {
        check(handle != 0L)
        return nativeEncode(handle, samples, count, output)
    }

    fun flush(output: ByteArray): Int {
        check(handle != 0L)
        return nativeFlush(handle, output)
    }

    override fun close() {
        if (handle != 0L) {
            nativeClose(handle)
            handle = 0L
        }
    }

    companion object {
        init { System.loadLibrary("soneme_lame") }

        fun create(): LameEncoder {
            val h = nativeCreate(AudioCaptureConfig.SAMPLE_RATE, 96)
            if (h == 0L) throw IllegalStateException("Could not initialize LAME")
            return LameEncoder(h)
        }

        @JvmStatic private external fun nativeCreate(sampleRate: Int, bitrateKbps: Int): Long
        @JvmStatic private external fun nativeEncode(handle: Long, samples: ShortArray, count: Int, output: ByteArray): Int
        @JvmStatic private external fun nativeFlush(handle: Long, output: ByteArray): Int
        @JvmStatic private external fun nativeClose(handle: Long)
    }
}
