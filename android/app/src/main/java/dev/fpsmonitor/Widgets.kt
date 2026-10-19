package dev.fpsmonitor

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

/*
 * Custom drawn widgets for the app screens. Display only: every number comes
 * from the Rust side. Paints and paths are created once and reused, so the
 * 0.5 s updates only change values and redraw.
 */

private fun View.dp(value: Float) = Theme.dp(context, value)

/** Arc gauge with the FPS in the middle; sweeps smoothly to each new value. */
class FpsGauge(context: Context, private val accent: Int) : View(context) {
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Theme.CARD_BORDER
    }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val number = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Theme.numberFont(context)
        color = Theme.TEXT
    }
    private val caption = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Theme.numberFont(context)
        color = Theme.TEXT_DIM
        letterSpacing = 0.15f
    }
    private val oval = RectF()
    private var shown = 0f
    private var value: Double? = null
    private var target: Double? = null
    private var animator: ValueAnimator? = null

    /** [fps] null = not measuring (shows "--"). */
    fun set(fps: Double?, target: Double?) {
        this.value = fps
        this.target = target
        animator?.cancel()
        animator = ValueAnimator.ofFloat(shown, (fps ?: 0.0).toFloat()).apply {
            duration = 300
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                shown = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val w = MeasureSpec.getSize(widthSpec)
        setMeasuredDimension(w, (w * 0.62f).toInt())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val stroke = h * 0.08f
        track.strokeWidth = stroke
        arc.strokeWidth = stroke
        val r = h * 0.78f
        val cx = w / 2f
        val cy = h * 0.88f
        oval.set(cx - r, cy - r, cx + r, cy + r)
        number.textSize = h * 0.42f
        caption.textSize = h * 0.09f
    }

    override fun onDraw(canvas: Canvas) {
        // 200° arc opening downwards; full sweep = the game's target FPS.
        val start = 170f
        val sweep = 200f
        canvas.drawArc(oval, start, sweep, false, track)
        val full = (target ?: 120.0).toFloat()
        val fraction = (shown / full).coerceIn(0f, 1f)
        val color = if (value == null) Theme.TEXT_DIM else Theme.fpsColor(shown.toDouble(), target)
        arc.color = color
        arc.setShadowLayer(arc.strokeWidth * 0.7f, 0f, 0f, Theme.withAlpha(color, 0xAA))
        if (fraction > 0f) canvas.drawArc(oval, start, sweep * fraction, false, arc)

        val cx = width / 2f
        val numberY = height * 0.72f
        canvas.drawText(if (value == null) "--" else shown.roundToInt().toString(), cx, numberY, number)
        caption.color = Theme.TEXT_DIM
        canvas.drawText("FPS", cx, numberY + caption.textSize * 1.3f, caption)
        caption.color = accent
        val targetText = target?.let { "TARGET ${it.roundToInt()}" } ?: "TARGET --"
        canvas.drawText(targetText, cx, numberY + caption.textSize * 2.6f, caption)
    }
}

/** Live FPS line for the last minute, with the game's target as a dashed line. */
class Graph(context: Context, private val accent: Int, private val heightDp: Float) : View(context) {
    private var avg = FloatArray(0)
    private var target: Float? = null

    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        strokeJoin = Paint.Join.ROUND
        color = accent
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val targetLine = dashedLine(context)
    private val empty = emptyText(context)
    private val path = Path()

    fun setLive(values: DoubleArray, target: Double?) {
        avg = FloatArray(values.size) { values[it].toFloat() }
        this.target = target?.toFloat()
        invalidate()
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthSpec), dp(heightDp).toInt())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        fill.shader = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            Theme.withAlpha(accent, 0x55), Theme.withAlpha(accent, 0x00), Shader.TileMode.CLAMP,
        )
    }

    override fun onDraw(canvas: Canvas) {
        val n = avg.size
        if (n < 2) {
            canvas.drawText("No data yet", width / 2f, height / 2f, empty)
            return
        }
        val top = dp(6f)
        val bottom = height - dp(6f)
        val scale = max((target ?: 0f) * 1.1f, avg.maxOrNull() ?: 1f).coerceAtLeast(1f)
        fun x(i: Int) = i * width / (n - 1f)
        fun y(v: Float) = bottom - (v / scale) * (bottom - top)

        path.reset()
        for (i in 0 until n) if (i == 0) path.moveTo(x(i), y(avg[i])) else path.lineTo(x(i), y(avg[i]))
        canvas.drawPath(path, line)
        path.lineTo(x(n - 1), bottom)
        path.lineTo(x(0), bottom)
        path.close()
        canvas.drawPath(path, fill)

        target?.let { canvas.drawLine(0f, y(it), width.toFloat(), y(it), targetLine) }
    }
}

private fun dashedLine(context: Context) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.STROKE
    strokeWidth = Theme.dp(context, 1f)
    color = Theme.TEXT_DIM
    pathEffect = DashPathEffect(floatArrayOf(Theme.dp(context, 4f), Theme.dp(context, 4f)), 0f)
}

private fun emptyText(context: Context) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = Theme.TEXT_DIM
    textAlign = Paint.Align.CENTER
    textSize = Theme.dp(context, 13f)
}

/**
 * Whole-match chart in two panels sharing one time axis: FPS on top (band =
 * lowest–highest per slice so short dips stay visible, average line, target,
 * red stutter dots) and battery / CPU / GPU temperatures below on one °C
 * scale fitted to the match. Drag a finger across it to read the values at a
 * moment. Only drawn on the Sessions tab, never during gameplay.
 */
class SessionChart(context: Context, private val accent: Int) : View(context) {
    private var n = 0
    private var time = FloatArray(0)
    private var low = FloatArray(0)
    private var avg = FloatArray(0)
    private var high = FloatArray(0)
    private var stutters = FloatArray(0)
    /** Battery, CPU, GPU; -1 = no reading in that slice. */
    private val temps = arrayOf(FloatArray(0), FloatArray(0), FloatArray(0))
    private var hasHeat = false
    /** °C range of the temperature panel, worked out once per session (not per frame). */
    private var tempLo = 0f
    private var tempHi = 1f
    private var target: Float? = null
    /** Slice under the finger while inspecting. */
    private var inspect: Int? = null
    /** Data came from a Rust library with a different point layout. */
    private var outdated = false

    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        strokeJoin = Paint.Join.ROUND
        color = accent
    }
    private val band = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Theme.withAlpha(accent, 0x33) }
    private val targetLine = dashedLine(context)
    private val mark = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Theme.BAD }
    private val tempLines = TEMP_COLORS.map { c ->
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(1.8f)
            strokeJoin = Paint.Join.ROUND
            color = c
        }
    }
    private val divider = Paint().apply { color = Theme.CARD_BORDER }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Theme.TEXT_DIM
        textSize = dp(10f)
        typeface = Theme.numberFont(context)
    }
    private val empty = emptyText(context)
    private val cursor = Paint().apply {
        color = Theme.TEXT
        strokeWidth = dp(1f)
    }
    private val bubble = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Theme.withAlpha(Theme.BG, 0xE6) }
    private val bubbleText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Theme.TEXT
        textSize = dp(11f)
        typeface = Theme.numberFont(context)
    }
    private val path = Path()
    private val rect = RectF()

    /**
     * [flat] = FpsNative.sessionSeries output: the point layout's size first,
     * then that many values per point. A different size means the Rust
     * library doesn't match this app, so nothing is drawn rather than shifted values.
     */
    fun setSession(flat: DoubleArray, target: Double?) {
        val stride = FpsNative.POINT_STRIDE
        outdated = flat.isNotEmpty() && flat[0].toInt() != stride
        n = if (flat.isEmpty() || outdated) 0 else (flat.size - 1) / stride
        fun col(i: Int) = FloatArray(n) { flat[1 + it * stride + i].toFloat() }
        time = col(0)
        low = col(1)
        avg = col(2)
        high = col(3)
        stutters = col(4)
        for (k in 0..2) temps[k] = col(5 + k)
        this.target = target?.toFloat()
        inspect = null

        // °C scale fitted to this match, with a little padding above and below.
        var lo = Float.MAX_VALUE
        var hi = -Float.MAX_VALUE
        for (t in temps) for (c in t) if (c >= 0f) {
            lo = minOf(lo, c)
            hi = maxOf(hi, c)
        }
        hasHeat = hi >= lo
        if (hasHeat) {
            tempLo = floor(lo - 3f)
            tempHi = ceil(hi + 3f)
        }
        contentDescription = describe()
        invalidate()
    }

    /** One-line summary for screen readers. */
    private fun describe(): String {
        if (n < 2) return if (outdated) "Session chart unavailable: rebuild the app" else "No session data"
        val parts = mutableListOf("Session chart, average %.0f FPS".format(avg.average()))
        TEMP_LABELS.forEachIndexed { k, name ->
            temps[k].filter { it >= 0f }.maxOrNull()?.let { parts += "$name up to %.0f degrees".format(it) }
        }
        val total = stutters.sum().roundToInt()
        if (total > 0) parts += "$total stutters"
        return parts.joinToString(", ")
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthSpec), dp(230f).toInt())
    }

    // Layout: labels on the left, FPS panel, gap, temperature panel, time labels.
    private val left get() = dp(30f)
    private val right get() = width - dp(4f)
    private val fpsTop get() = dp(6f)
    private val fpsBottom get() = height * 0.48f
    private val tempTop get() = fpsBottom + dp(16f)
    private val tempBottom get() = height - dp(18f)

    private fun x(i: Int): Float {
        val span = (time[n - 1] - time[0]).coerceAtLeast(1e-3f)
        return left + (time[i] - time[0]) / span * (right - left)
    }

    override fun onDraw(canvas: Canvas) {
        if (n < 2) {
            val text = if (outdated) "Rebuild the app: chart data doesn't match" else "No data yet"
            canvas.drawText(text, width / 2f, height / 2f, empty)
            return
        }
        drawFps(canvas)
        canvas.drawLine(left, (fpsBottom + tempTop) / 2, right, (fpsBottom + tempTop) / 2, divider)
        drawTemps(canvas)
        drawTimes(canvas)
        inspect?.let { drawInspect(canvas, it) }
    }

    private fun drawFps(canvas: Canvas) {
        val scale = max((target ?: 0f) * 1.1f, high.maxOrNull() ?: 1f).coerceAtLeast(1f)
        fun y(v: Float) = fpsBottom - (v / scale) * (fpsBottom - fpsTop)
        path.reset()
        for (i in 0 until n) if (i == 0) path.moveTo(x(i), y(high[i])) else path.lineTo(x(i), y(high[i]))
        for (i in n - 1 downTo 0) path.lineTo(x(i), y(low[i]))
        path.close()
        canvas.drawPath(path, band)
        path.reset()
        for (i in 0 until n) if (i == 0) path.moveTo(x(i), y(avg[i])) else path.lineTo(x(i), y(avg[i]))
        canvas.drawPath(path, line)
        target?.let {
            canvas.drawLine(left, y(it), right, y(it), targetLine)
            canvas.drawText("%.0f".format(it), 0f, y(it) + dp(4f), label)
        }
        canvas.drawText("0", 0f, fpsBottom, label)
        val r = dp(2.5f)
        for (i in 0 until n) if (stutters[i] > 0f) canvas.drawCircle(x(i), fpsBottom - r, r, mark)
    }

    private fun drawTemps(canvas: Canvas) {
        if (!hasHeat) {
            canvas.drawText("No heat data for this session", (left + right) / 2, (tempTop + tempBottom) / 2, empty)
            return
        }
        val lo = tempLo
        val hi = tempHi
        fun y(c: Float) = tempBottom - (c - lo) / (hi - lo) * (tempBottom - tempTop)
        for (k in 0..2) {
            val t = temps[k]
            path.reset()
            var drawing = false
            for (i in 0 until n) {
                if (t[i] < 0f) {
                    drawing = false // gap in readings: lift the pen
                    continue
                }
                if (drawing) path.lineTo(x(i), y(t[i])) else path.moveTo(x(i), y(t[i]))
                drawing = true
            }
            canvas.drawPath(path, tempLines[k])
        }
        canvas.drawText("%.0f°".format(hi), 0f, tempTop + dp(8f), label)
        canvas.drawText("%.0f°".format(lo), 0f, tempBottom, label)
    }

    private fun drawTimes(canvas: Canvas) {
        val y = height - dp(4f)
        label.textAlign = Paint.Align.LEFT
        canvas.drawText(clock(0f), left, y, label)
        label.textAlign = Paint.Align.CENTER
        canvas.drawText(clock((time[n - 1] - time[0]) / 2), (left + right) / 2, y, label)
        label.textAlign = Paint.Align.RIGHT
        canvas.drawText(clock(time[n - 1] - time[0]), right, y, label)
        label.textAlign = Paint.Align.LEFT
    }

    private fun drawInspect(canvas: Canvas, i: Int) {
        val cx = x(i)
        canvas.drawLine(cx, fpsTop, cx, tempBottom, cursor)
        val parts = mutableListOf(clock(time[i] - time[0]), "%.0f FPS".format(avg[i]))
        TEMP_LABELS.forEachIndexed { k, name -> if (temps[k][i] >= 0f) parts += "$name %.0f°".format(temps[k][i]) }
        if (stutters[i] > 0f) parts += "${stutters[i].roundToInt()} stutter"
        val text = parts.joinToString(" · ")
        val w = bubbleText.measureText(text) + dp(12f)
        val bx = (cx - w / 2).coerceIn(0f, (width - w).coerceAtLeast(0f))
        rect.set(bx, 0f, bx + w, dp(20f))
        canvas.drawRoundRect(rect, dp(6f), dp(6f), bubble)
        canvas.drawText(text, bx + dp(6f), dp(14f), bubbleText)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (n < 2) return false
        when (e.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                // Keep the page from scrolling while the finger reads the chart.
                parent?.requestDisallowInterceptTouchEvent(true)
                inspect = (0 until n).minByOrNull { abs(x(it) - e.x) }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> inspect = null
        }
        invalidate()
        return true
    }

    private fun clock(seconds: Float): String {
        val s = seconds.roundToInt().coerceAtLeast(0)
        return "%d:%02d".format(s / 60, s % 60)
    }

    companion object {
        /** Battery, CPU, GPU line colours (also used by the legend). */
        val TEMP_COLORS = intArrayOf(Theme.WARN, Theme.BAD, 0xFF5B8CFF.toInt())
        val TEMP_LABELS = listOf("BAT", "CPU", "GPU")
    }
}

/** Small card with a caption and a big number. */
class StatTile(context: Context, caption: String) : LinearLayout(context) {
    private val value = TextView(context).apply {
        typeface = Theme.numberFont(context)
        textSize = 26f
        setTextColor(Theme.TEXT)
        gravity = Gravity.CENTER
    }

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        background = Theme.card(context)
        val pad = Theme.dp(context, 10f).toInt()
        setPadding(pad, pad, pad, pad)
        addView(TextView(context).apply {
            text = caption
            typeface = Theme.numberFont(context)
            textSize = 11f
            letterSpacing = 0.12f
            setTextColor(Theme.TEXT_DIM)
            gravity = Gravity.CENTER
        })
        addView(value)
    }

    fun set(text: String, color: Int = Theme.TEXT) {
        if (value.text != text) value.text = text
        value.setTextColor(color)
    }
}

/** Label, value and a bar that turns orange/red as the temperature rises. */
class HeatBar(
    context: Context,
    private val label: String,
    private val min: Float,
    private val max: Float,
    /** Same warm/hot limits as the overlay (see [OverlayMeter]). */
    private val limits: HeatLimits,
) : View(context) {
    private var shown = min
    private var value: Float? = null
    private var animator: ValueAnimator? = null
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Theme.numberFont(context)
        textSize = dp(15f)
        color = Theme.TEXT_DIM
    }
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val rect = RectF()

    fun set(celsius: Double?) {
        value = celsius?.toFloat()
        animator?.cancel()
        animator = ValueAnimator.ofFloat(shown, value ?: min).apply {
            duration = 400
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                shown = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthSpec), dp(34f).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val color = when {
            value == null -> Theme.TEXT_DIM
            shown >= limits.badAt -> Theme.BAD
            shown >= limits.warnAt -> Theme.HOT
            else -> Theme.GOOD
        }
        text.textAlign = Paint.Align.LEFT
        text.color = Theme.TEXT_DIM
        canvas.drawText(label, 0f, dp(14f), text)
        text.textAlign = Paint.Align.RIGHT
        text.color = color
        canvas.drawText(value?.let { "%.1f°C".format(shown) } ?: "--", width.toFloat(), dp(14f), text)

        val y = dp(24f)
        val h = dp(6f)
        rect.set(0f, y, width.toFloat(), y + h)
        bar.color = Theme.CARD_BORDER
        canvas.drawRoundRect(rect, h, h, bar)
        val fraction = ((shown - min) / (max - min)).coerceIn(0f, 1f)
        if (value != null && fraction > 0f) {
            rect.right = width * fraction
            bar.color = color
            canvas.drawRoundRect(rect, h, h, bar)
        }
    }
}
