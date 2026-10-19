package dev.fpsmonitor

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.ceil

/** Overlay choices, saved on the phone. Changed in [MainActivity]. */
object OverlaySettings {
    const val ENABLED = "enabled"
    const val BLACK_BG = "black_bg"
    const val LOCKED = "locked"
    const val X = "x"
    const val Y = "y"
    /** 0 S, 1 M, 2 L. */
    const val SIZE = "size"
    /** Hide while the game isn't drawing (home screen, other apps). */
    const val ONLY_GAME = "only_game"
    /** Briefly turn the border red when a stutter happens. */
    const val FLASH = "flash"
    /** Stack the meters in a column instead of one row. */
    const val VERTICAL = "vertical"

    /** Settings from the earlier Mini/Standard/Full overlay (and the even older heat switch). */
    private const val OLD_LAYOUT = "layout"
    private const val OLD_HEAT = "heat"

    fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences("overlay", Context.MODE_PRIVATE).also(::migrate)

    /** Turn an old layout choice into meter switches once, then drop the old keys. */
    private fun migrate(prefs: SharedPreferences) {
        if (!prefs.contains(OLD_LAYOUT) && !prefs.contains(OLD_HEAT)) return
        // Old meaning: 0 Mini = FPS, 1 Standard = + battery, CPU, 2 Full = + GPU.
        val layout = prefs.getInt(OLD_LAYOUT, if (prefs.getBoolean(OLD_HEAT, true)) 1 else 0)
        prefs.edit()
            .putBoolean(OverlayMeter.FPS.key, true)
            .putBoolean(OverlayMeter.BATTERY.key, layout >= 1)
            .putBoolean(OverlayMeter.CPU.key, layout >= 1)
            .putBoolean(OverlayMeter.GPU.key, layout == 2)
            .remove(OLD_LAYOUT)
            .remove(OLD_HEAT)
            .apply()
    }
}

/** Temperatures (°C) where a part counts as warm (orange) and hot (red). Shared by the overlay and the app's heat bars. */
class HeatLimits(val warnAt: Double, val badAt: Double) {
    fun color(celsius: Double) = when {
        celsius >= badAt -> Theme.BAD
        celsius >= warnAt -> Theme.HOT
        else -> Theme.TEXT
    }
}

/**
 * The meters the overlay can show, each with its own on/off switch.
 * [label] is the tiny tag shown before the value (none for FPS).
 * Temperatures share one format and colour rule; FPS has its own.
 */
enum class OverlayMeter(val key: String, val title: String, val label: String?, val default: Boolean) {
    FPS("show_fps", "FPS", null, true) {
        override val widest = "888"
        override val sizeSp = 20f
        override fun text(r: Reading) =
            if (r.status == Reading.Status.OK) "%.0f".format(r.smoothedFps ?: r.fps) else "--"
        override fun color(r: Reading) =
            if (r.status == Reading.Status.OK) Theme.fpsColor(r.smoothedFps ?: r.fps, r.targetFps) else Theme.TEXT
    },
    STUTTERS("show_stutters", "Stutter count", "STUT", false) {
        override val widest = "9999"
        override fun text(r: Reading) = r.sessionStutters.toString()
        override fun color(r: Reading) = if (r.sessionStutters > 0) Theme.WARN else Theme.TEXT
    },
    BATTERY("show_battery", "Battery temperature", "BAT", true) {
        override val limits = HeatLimits(40.0, 44.0)
        override fun celsius(r: Reading) = r.batteryC
    },
    SURFACE("show_surface", "Surface temperature", "SKN", false) {
        override val limits = HeatLimits(42.0, 46.0)
        override fun celsius(r: Reading) = r.skinC
    },
    CPU("show_cpu", "CPU temperature", "CPU", true) {
        override val limits = HeatLimits(75.0, 88.0)
        override fun celsius(r: Reading) = r.cpuC
    },
    GPU("show_gpu", "GPU temperature", "GPU", false) {
        override val limits = HeatLimits(70.0, 85.0)
        override fun celsius(r: Reading) = r.gpuC
    };

    fun enabled(prefs: SharedPreferences) = prefs.getBoolean(key, default)

    /** Warm/hot thresholds; null for FPS. */
    open val limits: HeatLimits? = null
    /** Widest text the value can take, used for its fixed width. */
    open val widest = "888°"
    open val sizeSp = 14f

    protected open fun celsius(r: Reading): Double? = null

    /** Whole degrees, so the shown number and its colour always agree. */
    private fun shown(r: Reading) = celsius(r)?.let { Math.round(it).toDouble() }

    open fun text(r: Reading): String = shown(r)?.let { "%.0f°".format(it) } ?: "--"

    /** Never cooler than Android's own Severe-or-worse heat status. */
    open fun color(r: Reading): Int {
        val c = shown(r) ?: return Theme.TEXT_DIM
        return if ((r.heatStatus ?: 0) >= 3) Theme.BAD else limits?.color(c) ?: Theme.TEXT
    }
}

/**
 * Small floating readout on top of the game: one clean row of numbers.
 * Display only: it shows what the Rust side measured. All methods must be
 * called on the main thread.
 *
 * Kept light on purpose: every value has a fixed width so changes never
 * re-lay out the window, text is only set when it changes, and nothing
 * animates except an optional short stutter flash.
 */
class Overlay(private val context: Context) {
    private val wm = context.getSystemService(WindowManager::class.java)
    private val prefs = OverlaySettings.prefs(context)
    private val mainThread = Handler(Looper.getMainLooper())

    private var root: LinearLayout? = null
    private var params: WindowManager.LayoutParams? = null
    /** Settings the current views were built for; a change rebuilds them. */
    private var builtFor: List<Any>? = null

    private val values = mutableListOf<Pair<OverlayMeter, TextView>>()
    private var background: GradientDrawable? = null
    private var accent = 0
    private var black = true

    private var last: Reading? = null
    private var lastFramesAt = 0L
    private var flashing = false
    /** Value tinted red by a flash on the transparent background; skipped by [recolor]. */
    private var flashed: TextView? = null

    /** Show, hide, rebuild or restyle to match the current settings and permission. */
    fun sync() {
        val meters = OverlayMeter.entries.filter { it.enabled(prefs) }
        val wanted = prefs.getBoolean(OverlaySettings.ENABLED, false) &&
            Settings.canDrawOverlays(context) && meters.isNotEmpty()
        if (!wanted) {
            remove()
            return
        }
        val key = listOf(
            meters,
            prefs.getInt(OverlaySettings.SIZE, 1),
            prefs.getBoolean(OverlaySettings.BLACK_BG, true),
            prefs.getBoolean(OverlaySettings.VERTICAL, false),
            Theme.accent(context),
        )
        if (root == null || key != builtFor) {
            remove()
            add(meters)
            builtFor = key
        }
        applyLock()
        last?.let(::update)
    }

    fun remove() {
        mainThread.removeCallbacksAndMessages(null)
        flashing = false
        flashed = null
        root?.let { runCatching { wm.removeView(it) } }
        root = null
        values.clear()
    }

    fun update(r: Reading) {
        // A failed reading (e.g. one Shizuku hiccup) has no values: keep showing
        // the last good ones instead of flickering to "STUT 0" and "--".
        val failed = r.status == Reading.Status.ERROR
        if (!failed) last = r
        val view = root ?: return

        // Only over the game: hide when no frames for 2 s. Always shown while
        // unlocked, so it can be positioned on any screen.
        val now = SystemClock.uptimeMillis()
        if (r.status == Reading.Status.OK && r.frames > 0) lastFramesAt = now
        val show = !prefs.getBoolean(OverlaySettings.ONLY_GAME, true) ||
            !prefs.getBoolean(OverlaySettings.LOCKED, true) ||
            now - lastFramesAt < GAME_GONE_MS
        val visibility = if (show) View.VISIBLE else View.GONE
        if (view.visibility != visibility) view.visibility = visibility
        if (!show || failed) return

        for ((meter, text) in values) setText(text, meter.text(r))
        recolor(r)

        if (r.status == Reading.Status.OK && r.stutters > 0 && prefs.getBoolean(OverlaySettings.FLASH, false)) flash()
    }

    /** Normal colours for every value, except the one currently flashing. */
    private fun recolor(r: Reading) {
        for ((meter, text) in values) if (text != flashed) text.setTextColor(meter.color(r))
    }

    private fun setText(view: TextView, text: String) {
        if (view.text.toString() != text) view.text = text
    }

    private fun flash() {
        if (flashing) return
        flashing = true
        if (black) {
            background?.setStroke(dp(1.5f), Theme.BAD)
        } else {
            // No border to flash: tint the FPS value, or the first meter if FPS is off.
            flashed = (values.firstOrNull { it.first == OverlayMeter.FPS } ?: values.firstOrNull())?.second
            flashed?.setTextColor(Theme.BAD)
        }
        mainThread.postDelayed({
            flashing = false
            flashed = null
            if (black) background?.setStroke(dp(1f), Theme.withAlpha(accent, 0x88))
            last?.let(::recolor)
        }, FLASH_MS)
    }

    private fun dp(value: Float) = Theme.dp(context, value).toInt()

    private fun text(sizeSp: Float, color: Int) = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        typeface = Theme.numberFont(context)
        includeFontPadding = false
        maxLines = 1
        setTextColor(color)
        if (!black) setShadowLayer(dp(2f).toFloat(), 0f, 0f, Color.BLACK)
    }

    /** Fixed width that fits [widest], so value updates never resize the window. */
    private fun TextView.fixedWidth(widest: String) = apply {
        layoutParams = LinearLayout.LayoutParams(ceil(paint.measureText(widest)).toInt() + 2, LinearLayout.LayoutParams.WRAP_CONTENT)
    }

    private fun add(meters: List<OverlayMeter>) {
        black = prefs.getBoolean(OverlaySettings.BLACK_BG, true)
        accent = Theme.accent(context).color
        background = if (black) {
            GradientDrawable().apply {
                setColor(Color.BLACK)
                cornerRadius = dp(10f).toFloat()
                setStroke(dp(1f), Theme.withAlpha(accent, 0x88))
            }
        } else null
        val view = LinearLayout(context).apply {
            this.background = this@Overlay.background
            addView(buildMeters(meters))
        }
        val lp = windowParams()
        enableDragging(view, lp)
        wm.addView(view, lp)
        root = view
        params = lp
    }

    /** The meters side by side, or stacked when "Stack meters vertically" is on. */
    private fun buildMeters(meters: List<OverlayMeter>): LinearLayout {
        val scale = when (prefs.getInt(OverlaySettings.SIZE, 1)) {
            0 -> 0.85f
            2 -> 1.25f
            else -> 1f
        }
        val vertical = prefs.getBoolean(OverlaySettings.VERTICAL, false)
        val container = LinearLayout(context).apply {
            orientation = if (vertical) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            // Stacked: right-align every line so the values form a tidy column.
            gravity = if (vertical) Gravity.END else Gravity.CENTER_VERTICAL
            setPadding(dp(9f), dp(4f), dp(9f), dp(4f))
        }
        meters.forEachIndexed { i, meter ->
            val group = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.BOTTOM
                if (i > 0) if (vertical) setPadding(0, dp(2f), 0, 0) else setPadding(dp(9f), 0, 0, 0)
            }
            meter.label?.let { group.addView(text(9f * scale, Theme.TEXT_DIM).apply {
                text = "$it "
                letterSpacing = 0.08f
            }) }
            val value = text(meter.sizeSp * scale, Theme.TEXT).apply { gravity = Gravity.END }.fixedWidth(meter.widest)
            group.addView(value)
            values += meter to value
            container.addView(group)
        }
        return container
    }

    private fun windowParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = prefs.getInt(OverlaySettings.X, dp(24f))
        y = prefs.getInt(OverlaySettings.Y, dp(24f))
        // Android 12+ blocks touches passing through an overlay that is more
        // than 80% opaque. At 0.8, taps on top of it still reach the game.
        alpha = 0.8f
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
    }

    /** Locked: every touch goes to the game. Unlocked: drag to move. */
    private fun applyLock() {
        val view = root ?: return
        val lp = params ?: return
        val locked = prefs.getBoolean(OverlaySettings.LOCKED, true)
        val flags = if (locked) {
            lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        } else {
            lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        }
        if (flags != lp.flags) {
            lp.flags = flags
            wm.updateViewLayout(view, lp)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun enableDragging(view: View, lp: WindowManager.LayoutParams) {
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        view.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = lp.x
                    startY = lp.y
                    touchX = e.rawX
                    touchY = e.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = startX + (e.rawX - touchX).toInt()
                    lp.y = startY + (e.rawY - touchY).toInt()
                    wm.updateViewLayout(view, lp)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    prefs.edit().putInt(OverlaySettings.X, lp.x).putInt(OverlaySettings.Y, lp.y).apply()
                    true
                }
                else -> false
            }
        }
    }

    private companion object {
        const val GAME_GONE_MS = 2000L
        const val FLASH_MS = 350L
    }
}
