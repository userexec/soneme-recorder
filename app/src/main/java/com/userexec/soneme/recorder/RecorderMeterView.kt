package com.userexec.soneme.recorder

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import kotlin.math.roundToInt

class RecorderMeterView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val graphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(0x4f, 0x6f, 0x8f)
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }
    private val graphFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(0x4f, 0x6f, 0x8f)
        style = Paint.Style.FILL
    }
    private val targetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = 1f
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(4f, 4f), 0f)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 15f * resources.displayMetrics.scaledDensity
    }
    private val quickBackground = Paint().apply { color = Color.rgb(0x99, 0x99, 0x99) }
    private val quickForeground = Paint()
    private val overlayPaint = Paint().apply { color = 0xaa000000.toInt() }
    private val overlayText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 15f * resources.displayMetrics.scaledDensity
        textAlign = Paint.Align.CENTER
    }

    var points: List<MeterPoint> = emptyList()
        set(value) { field = value; invalidate() }
    var paused: Boolean = false
        set(value) { field = value; invalidate() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.BLACK)
        val quickHeight = (72f * resources.displayMetrics.density).coerceAtMost(height * .64f)
        val graphBottom = height - quickHeight
        drawGraph(canvas, graphBottom)
        drawTarget(canvas, graphBottom, -20f, "Target Level")
        drawTarget(canvas, graphBottom, -8f, "Target Peak")
        drawQuick(canvas, graphBottom, quickHeight)
        if (paused) {
            val boxW = width * .65f
            val boxH = 38f * resources.displayMetrics.density
            val left = (width - boxW) / 2f
            val top = (graphBottom - boxH) / 2f
            canvas.drawRect(left, top, left + boxW, top + boxH, overlayPaint)
            val baseline = top + boxH / 2f - (overlayText.ascent() + overlayText.descent()) / 2f
            canvas.drawText("Not recording", width / 2f, baseline, overlayText)
        }
    }

    private fun drawGraph(canvas: Canvas, graphBottom: Float) {
        if (points.isEmpty()) return
        val now = points.last().elapsedRealtimeMs.coerceAtLeast(SystemClock.elapsedRealtime() - 100)
        val linePath = Path()
        var started = false
        var firstX = 0f
        var lastX = 0f
        points.forEach { p ->
            val age = (now - p.elapsedRealtimeMs).coerceAtLeast(0L)
            if (age > GRAPH_MS) return@forEach
            val x = width * (1f - age.toFloat() / GRAPH_MS.toFloat())
            val y = dbToY(p.dbfs, graphBottom)
            if (!started) {
                linePath.moveTo(x, y)
                firstX = x
                started = true
            } else {
                linePath.lineTo(x, y)
            }
            lastX = x
        }
        if (!started) return

        val fillPath = Path(linePath).apply {
            lineTo(lastX, graphBottom)
            lineTo(firstX, graphBottom)
            close()
        }
        canvas.drawPath(fillPath, graphFillPaint)
        canvas.drawPath(linePath, graphPaint)
    }

    private fun drawTarget(canvas: Canvas, graphBottom: Float, db: Float, label: String) {
        val y = dbToY(db, graphBottom)
        canvas.drawLine(0f, y, width.toFloat(), y, targetPaint)
        val gap = 3f * resources.displayMetrics.density
        val baseline = y - gap - labelPaint.fontMetrics.descent
        canvas.drawText(label, 3f * resources.displayMetrics.density, baseline, labelPaint)
    }

    private fun drawQuick(canvas: Canvas, top: Float, height: Float) {
        canvas.drawRect(0f, top, width.toFloat(), top + height, quickBackground)
        val recent = points.filter { points.lastOrNull()?.elapsedRealtimeMs?.minus(it.elapsedRealtimeMs) ?: Long.MAX_VALUE <= QUICK_MS }
        if (recent.isEmpty()) return
        val active = recent.count { it.dbfs > -40f }
        val alpha = ((active.toFloat() / recent.size.toFloat()) * 255f).roundToInt().coerceIn(0, 255)
        val average = recent.sumOf { maxOf(it.dbfs, -40f).toDouble() }.toFloat() / recent.size.toFloat()
        val rgb = indicatorColor(average)
        quickForeground.color = Color.argb(alpha, Color.red(rgb), Color.green(rgb), Color.blue(rgb))
        canvas.drawRect(0f, top, width.toFloat(), top + height, quickForeground)
    }

    private fun indicatorColor(db: Float): Int {
        fun linear(v: Float, low: Float, high: Float, from: Int, to: Int): Int {
            if (high == low) return to
            val t = ((v - low) / (high - low)).coerceIn(0f, 1f)
            return (from + (to - from) * t).roundToInt().coerceIn(0, 255)
        }
        val red = when { db <= -12f -> 0; db >= -8f -> 255; else -> linear(db, -12f, -8f, 0, 255) }
        val green = when {
            db <= -26f -> 0
            db < -20f -> linear(db, -26f, -20f, 0, 255)
            db >= -8f -> 0
            else -> linear(db, -20f, -8f, 255, 0)
        }
        val blue = when { db <= -26f -> 255; db >= -20f -> 0; else -> linear(db, -26f, -20f, 255, 0) }
        return Color.rgb(red, green, blue)
    }

    private fun dbToY(db: Float, graphBottom: Float): Float {
        val normalized = ((db.coerceIn(-60f, 0f) + 60f) / 60f)
        return graphBottom * (1f - normalized)
    }

    companion object {
        private const val GRAPH_MS = 10_000L
        private const val QUICK_MS = 5_000L
    }
}
