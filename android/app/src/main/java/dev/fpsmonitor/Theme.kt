package dev.fpsmonitor

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue

/** UI choices, saved on the phone. */
object UiSettings {
    const val ACCENT = "accent"

    fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences("ui", Context.MODE_PRIVATE)
}

/**
 * Dark esports look shared by every screen and the overlay. Status colours
 * (good / dipping / low, heat warnings) are fixed so they mean the same in
 * every accent theme; only the accent changes.
 */
object Theme {
    const val BG = 0xFF0B0F14.toInt()
    const val CARD = 0xFF141A22.toInt()
    const val CARD_BORDER = 0xFF232D3A.toInt()
    const val TEXT = 0xFFE6EDF3.toInt()
    const val TEXT_DIM = 0xFF8B98A5.toInt()

    const val GOOD = 0xFF39FF88.toInt()
    const val WARN = 0xFFFFC53D.toInt()
    const val BAD = 0xFFFF4D5E.toInt()
    const val HOT = 0xFFFFA726.toInt()

    enum class Accent(val label: String, val color: Int) {
        NEON_CYAN("Neon Cyan", 0xFF00E5FF.toInt()),
        DRAGON_RED("Dragon Red", 0xFFFF2E4D.toInt()),
        TOXIC_GREEN("Toxic Green", 0xFF39FF14.toInt()),
        PURPLE_PULSE("Purple Pulse", 0xFFB14DFF.toInt()),
    }

    fun accent(context: Context): Accent {
        val name = UiSettings.prefs(context).getString(UiSettings.ACCENT, null)
        return Accent.entries.firstOrNull { it.name == name } ?: Accent.NEON_CYAN
    }

    private var font: Typeface? = null

    /** Rajdhani Bold (subset, 20 KB) for numbers and headings. */
    fun numberFont(context: Context): Typeface =
        font ?: context.resources.getFont(R.font.rajdhani_bold).also { font = it }

    fun dp(context: Context, value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, context.resources.displayMetrics)

    /** Raised panel with a thin border; [glow] tints the border with a colour. */
    fun card(context: Context, glow: Int? = null): GradientDrawable = GradientDrawable().apply {
        setColor(CARD)
        cornerRadius = dp(context, 14f)
        setStroke(dp(context, 1f).toInt(), glow?.let { withAlpha(it, 0x99) } ?: CARD_BORDER)
    }

    /** Rounded pill: filled with [color], or outlined when [filled] is false. */
    fun pill(context: Context, color: Int, filled: Boolean = true): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(context, 999f)
        if (filled) {
            setColor(color)
        } else {
            setColor(withAlpha(color, 0x1F))
            setStroke(dp(context, 1.5f).toInt(), color)
        }
    }

    /** Green at target, amber when dipping, red when well below. */
    fun fpsColor(fps: Double, target: Double?): Int {
        val ratio = fps / (target ?: 120.0)
        return when {
            ratio >= 0.95 -> GOOD
            ratio >= 0.80 -> WARN
            else -> BAD
        }
    }

    /** Android heat status: 2 Moderate = orange, 3 Severe or worse = red. */
    fun heatColor(status: Int?): Int = when {
        (status ?: 0) >= 3 -> BAD
        (status ?: 0) >= 2 -> HOT
        else -> TEXT
    }

    fun withAlpha(color: Int, alpha: Int): Int = Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
}
