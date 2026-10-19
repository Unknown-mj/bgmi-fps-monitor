package dev.fpsmonitor

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import android.widget.TextView
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

/**
 * Line graph. Live mode: the last minute of FPS. Session mode: the whole
 * match with a min–max band (so dips show), stutter marks and battery heat.
 */
class Graph(context: Context, private val accent: Int, private val heightDp: Float) : View(context) {
    private var avg = FloatArray(0)
    private var low: FloatArray? = null
    private var high: FloatArray? = null
    private var stutters: FloatArray? = null
    private var battery: FloatArray? = null
    private var target: Float? = null

    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        strokeJoin = Paint.Join.ROUND
        color = accent
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val band = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Theme.withAlpha(accent, 0x33) }
    private val targetLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        color = Theme.TEXT_DIM
        pathEffect = DashPathEffect(floatArrayOf(dp(4f), dp(4f)), 0f)
    }
    private val heat = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
        color = Theme.HOT
    }
    private val mark = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Theme.BAD }
    private val empty = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Theme.TEXT_DIM
        textAlign = Paint.Align.CENTER
        textSize = dp(13f)
    }
    private val path = Path()

    fun setLive(values: DoubleArray, target: Double?) {
        avg = FloatArray(values.size) { values[it].toFloat() }
        low = null
        high = null
        stutters = null
        battery = null
        this.target = target?.toFloat()
        invalidate()
    }

    /** [flat] = FpsNative.sessionSeries output, POINT_STRIDE values per point. */
    fun setSession(flat: DoubleArray, target: Double?) {
        val n = flat.size / FpsNative.POINT_STRIDE
        fun col(i: Int) = FloatArray(n) { flat[it * FpsNative.POINT_STRIDE + i].toFloat() }
        low = col(1)
        avg = col(2)
        high = col(3)
        stutters = col(4)
        battery = col(5).takeIf { b -> b.any { it >= 0f } }
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
        val scale = max((target ?: 0f) * 1.1f, (high ?: avg).maxOrNull() ?: 1f).coerceAtLeast(1f)
        fun x(i: Int) = i * width / (n - 1f)
        fun y(v: Float) = bottom - (v / scale) * (bottom - top)

        val lo = low
        val hi = high
        if (lo != null && hi != null) {
            path.reset()
            for (i in 0 until n) if (i == 0) path.moveTo(x(i), y(hi[i])) else path.lineTo(x(i), y(hi[i]))
            for (i in n - 1 downTo 0) path.lineTo(x(i), y(lo[i]))
            path.close()
            canvas.drawPath(path, band)
        }

        path.reset()
        for (i in 0 until n) if (i == 0) path.moveTo(x(i), y(avg[i])) else path.lineTo(x(i), y(avg[i]))
        canvas.drawPath(path, line)
        path.lineTo(x(n - 1), bottom)
        path.lineTo(x(0), bottom)
        path.close()
        canvas.drawPath(path, fill)

        target?.let { canvas.drawLine(0f, y(it), width.toFloat(), y(it), targetLine) }

        battery?.let { b ->
            // Battery heat on its own scale: 25 °C at the bottom, 50 °C at the top.
            path.reset()
            var started = false
            for (i in 0 until n) {
                if (b[i] < 0f) continue
                val by = bottom - ((b[i] - 25f) / 25f).coerceIn(0f, 1f) * (bottom - top)
                if (!started) path.moveTo(x(i), by) else path.lineTo(x(i), by)
                started = true
            }
            canvas.drawPath(path, heat)
        }

        stutters?.let { s ->
            val r = dp(2.5f)
            for (i in 0 until n) if (s[i] > 0f) canvas.drawCircle(x(i), bottom - r, r, mark)
        }
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
