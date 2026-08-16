package com.userexec.soneme.recorder

import kotlin.math.log10
import kotlin.math.sqrt

class RmsAccumulator(private val onWindow: (Float) -> Unit) {
    private var count = 0
    private var sumSquares = 0.0

    fun add(samples: ShortArray, length: Int) {
        for (i in 0 until length) {
            val v = samples[i].toDouble()
            sumSquares += v * v
            count++
            if (count == WINDOW_SAMPLES) emit()
        }
    }

    private fun emit() {
        val rms = sqrt(sumSquares / WINDOW_SAMPLES.toDouble())
        val db = if (rms <= 0.0) -60.0 else 20.0 * log10(rms / 32768.0)
        onWindow(db.coerceIn(-60.0, 0.0).toFloat())
        count = 0
        sumSquares = 0.0
    }

    fun reset() {
        count = 0
        sumSquares = 0.0
    }

    companion object { const val WINDOW_SAMPLES = 2048 }
}
