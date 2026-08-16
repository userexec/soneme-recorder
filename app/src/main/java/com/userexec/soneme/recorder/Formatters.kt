package com.userexec.soneme.recorder

object Formatters {
    fun clock(ms: Long): String {
        val total = (ms.coerceAtLeast(0L) / 1000L)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    fun longClock(ms: Long): String {
        val total = (ms.coerceAtLeast(0L) / 1000L)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return "%d:%02d:%02d".format(h, m, s)
    }

    fun words(ms: Long): String {
        val totalMinutes = (ms.coerceAtLeast(0L) / 60_000L)
        val h = totalMinutes / 60
        val m = totalMinutes % 60
        return when {
            h > 0 && m > 0 -> "${h}h ${m}m"
            h > 0 -> "${h}h"
            else -> "${m}m"
        }
    }

    fun sleep(ms: Long): String {
        val minutes = ((ms.coerceAtLeast(0L) + 59_999L) / 60_000L)
        val h = minutes / 60
        val m = minutes % 60
        return "$h:${m.toString().padStart(2, '0')}"
    }
}
