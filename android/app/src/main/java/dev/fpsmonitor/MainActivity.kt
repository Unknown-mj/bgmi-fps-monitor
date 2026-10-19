package dev.fpsmonitor

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import rikka.shizuku.Shizuku
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Three tabs: Live (gauge, graph, stats, heat, start/stop), Sessions (past
 * matches with graphs) and Settings (theme, overlay). The measuring itself
 * happens in [MonitorService], so it keeps going while BGMI is in front.
 * Each reading only updates values; views are built once.
 */
class MainActivity : Activity() {
    private val mainThread = Handler(Looper.getMainLooper())
    private val dateFormat = SimpleDateFormat("d MMM · HH:mm", Locale.getDefault())
    private lateinit var accent: Theme.Accent

    private lateinit var content: FrameLayout
    private lateinit var tabButtons: List<TextView>
    private lateinit var pages: List<View>
    private var tab = 0

    // Header
    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private var pulse: ObjectAnimator? = null

    // Live tab
    private lateinit var gauge: FpsGauge
    private lateinit var liveGraph: Graph
    private lateinit var tileAvg: StatTile
    private lateinit var tileWorst: StatTile
    private lateinit var tileStutters: StatTile
    private lateinit var tileSkipped: StatTile
    private lateinit var heatTitle: TextView
    private lateinit var batteryBar: HeatBar
    private lateinit var cpuBar: HeatBar
    private lateinit var gpuBar: HeatBar
    private lateinit var message: TextView
    private lateinit var toggle: TextView

    // Sessions and settings tabs
    private lateinit var sessionList: LinearLayout
    private lateinit var overlayPermission: TextView

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { _, result ->
        if (result == PackageManager.PERMISSION_GRANTED) startMonitoring()
        else message.text = "Shizuku permission denied."
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        accent = Theme.accent(this)

        content = FrameLayout(this)
        pages = listOf(livePage(), sessionsPage(), settingsPage())
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Theme.BG)
            addView(header())
            addView(content, LinearLayout.LayoutParams(MATCH, 0, 1f))
            addView(tabBar())
            // Android 15+ draws apps edge to edge: keep clear of the status
            // bar, notch and navigation bar.
            setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        }
        setContentView(root)
        showTab(0, animate = false)
        Shizuku.addRequestPermissionResultListener(permissionListener)
    }

    override fun onResume() {
        super.onResume()
        MonitorService.listener = { r -> mainThread.post { render(r) } }
        // The user may have just granted (or revoked) the overlay permission.
        overlayPermission.visibility = if (Settings.canDrawOverlays(this)) View.GONE else View.VISIBLE
        MonitorService.settingsChanged?.invoke()
        refresh()
        if (tab == 1) loadSessions()
    }

    override fun onPause() {
        MonitorService.listener = null
        super.onPause()
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(permissionListener)
        pulse?.cancel()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- layout helpers

    private fun dp(v: Float) = Theme.dp(this, v).toInt()

    private fun label(text: String, size: Float = 12f, color: Int = Theme.TEXT_DIM) = TextView(this).apply {
        this.text = text
        textSize = size
        typeface = Theme.numberFont(this@MainActivity)
        letterSpacing = 0.12f
        setTextColor(color)
    }

    private fun body(text: String = "", size: Float = 14f, color: Int = Theme.TEXT_DIM) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(color)
    }

    private fun card(vararg children: View) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = Theme.card(this@MainActivity)
        setPadding(dp(16f), dp(14f), dp(16f), dp(14f))
        children.forEach { addView(it) }
    }

    private fun spaced(view: View, top: Float = 12f) = view.apply {
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(top) }
    }

    private fun page(vararg children: View): View {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16f), dp(4f), dp(16f), dp(24f))
            children.forEach { addView(it) }
        }
        return ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(column)
        }
    }

    /** Pill button with a small press-down animation. */
    private fun pillButton(text: String, onClick: () -> Unit) = TextView(this).apply {
        this.text = text
        textSize = 18f
        typeface = Theme.numberFont(this@MainActivity)
        letterSpacing = 0.12f
        gravity = Gravity.CENTER
        setPadding(dp(20f), dp(14f), dp(20f), dp(14f))
        isClickable = true
        setOnClickListener { onClick() }
        setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.96f).scaleY(0.96f).setDuration(80).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
            }
            false
        }
    }

    // ---------------------------------------------------------------- header and tabs

    private fun header(): View {
        statusDot = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Theme.TEXT_DIM)
            }
        }
        statusText = label("IDLE", 13f)
        val chip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Theme.pill(this@MainActivity, Theme.CARD_BORDER, filled = false)
            setPadding(dp(12f), dp(6f), dp(14f), dp(6f))
            addView(statusDot, LinearLayout.LayoutParams(dp(8f), dp(8f)).apply { rightMargin = dp(8f) })
            addView(statusText)
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16f), dp(14f), dp(16f), dp(10f))
            addView(label("FPS MONITOR", 22f, accent.color).apply { letterSpacing = 0.18f },
                LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(chip)
        }
    }

    private fun tabBar(): View {
        tabButtons = listOf("LIVE", "SESSIONS", "SETTINGS").mapIndexed { i, name ->
            label(name, 15f).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(12f), 0, dp(12f))
                setOnClickListener { showTab(i) }
            }
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Theme.CARD)
            setPadding(dp(8f), dp(6f), dp(8f), dp(6f))
            tabButtons.forEach { addView(it, LinearLayout.LayoutParams(0, WRAP, 1f)) }
        }
    }

    private fun showTab(index: Int, animate: Boolean = true) {
        tab = index
        tabButtons.forEachIndexed { i, b ->
            val selected = i == index
            b.setTextColor(if (selected) accent.color else Theme.TEXT_DIM)
            b.background = if (selected) Theme.pill(this, accent.color, filled = false) else null
        }
        content.removeAllViews()
        val page = pages[index]
        content.addView(page)
        if (animate) {
            page.alpha = 0f
            page.animate().alpha(1f).setDuration(150).start()
        }
        if (index == 1) loadSessions()
    }

    // ---------------------------------------------------------------- live tab

    private fun livePage(): View {
        gauge = FpsGauge(this, accent.color)
        liveGraph = Graph(this, accent.color, heightDp = 90f)
        tileAvg = StatTile(this, "AVG FPS")
        tileWorst = StatTile(this, "WORST 1s")
        tileStutters = StatTile(this, "STUTTERS")
        tileSkipped = StatTile(this, "SKIPPED")
        heatTitle = label("PHONE HEAT", 13f)
        batteryBar = HeatBar(this, "BATTERY", 25f, 50f, OverlayMeter.BATTERY.limits!!)
        cpuBar = HeatBar(this, "CPU", 30f, 100f, OverlayMeter.CPU.limits!!)
        gpuBar = HeatBar(this, "GPU", 30f, 100f, OverlayMeter.GPU.limits!!)
        message = body(size = 14f).apply { gravity = Gravity.CENTER }
        toggle = pillButton("START MONITORING") { onToggle() }

        fun tiles(a: View, b: View) = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(a, LinearLayout.LayoutParams(0, WRAP, 1f).apply { rightMargin = dp(6f) })
            addView(b, LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dp(6f) })
        }

        return page(
            gauge,
            spaced(card(label("LAST 60 SECONDS", 12f), spaced(liveGraph, 8f)), 4f),
            spaced(tiles(tileAvg, tileWorst)),
            spaced(tiles(tileStutters, tileSkipped)),
            spaced(card(heatTitle, spaced(batteryBar, 8f), spaced(cpuBar, 6f), spaced(gpuBar, 6f))),
            spaced(message, 14f),
            spaced(toggle, 14f),
        )
    }

    private fun refresh() {
        val running = MonitorService.running
        toggle.text = if (running) "STOP MONITORING" else "START MONITORING"
        toggle.background = if (running) Theme.pill(this, Theme.BAD, filled = false) else Theme.pill(this, accent.color)
        toggle.setTextColor(if (running) Theme.BAD else Theme.BG)

        statusText.text = if (running) "LIVE" else "IDLE"
        statusText.setTextColor(if (running) Theme.GOOD else Theme.TEXT_DIM)
        (statusDot.background as GradientDrawable).setColor(if (running) Theme.GOOD else Theme.TEXT_DIM)
        if (running && pulse == null) {
            pulse = ObjectAnimator.ofFloat(statusDot, View.ALPHA, 1f, 0.25f).apply {
                duration = 700
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                start()
            }
        } else if (!running) {
            pulse?.cancel()
            pulse = null
            statusDot.alpha = 1f
        }

        val r = MonitorService.latest
        if (running && r != null) {
            render(r)
        } else if (!running) {
            gauge.set(null, r?.targetFps)
            message.text = "Tap START, then open BGMI.\nLive FPS shows in the overlay and notification while you play."
        }
    }

    private fun render(r: Reading) {
        if (!MonitorService.running) return
        val ok = r.status == Reading.Status.OK
        gauge.set(if (ok) r.smoothedFps ?: r.fps else null, r.targetFps)
        r.trail?.let { liveGraph.setLive(it, r.targetFps) }

        tileAvg.set(if (r.sessionSeconds > 0) "%.1f".format(r.sessionFps) else "--",
            if (r.sessionSeconds > 0) Theme.fpsColor(r.sessionFps, r.targetFps) else Theme.TEXT)
        tileWorst.set(r.worstFps?.let { "%.0f".format(it) } ?: "--",
            r.worstFps?.let { Theme.fpsColor(it, r.targetFps) } ?: Theme.TEXT)
        tileStutters.set(r.sessionStutters.toString(), if (r.sessionStutters > 0) Theme.WARN else Theme.TEXT)
        tileSkipped.set(r.sessionSkipped.toString())

        heatTitle.text = "PHONE HEAT · ${r.heatStatus?.let { Reading.heatName(it).uppercase() } ?: "--"}"
        heatTitle.setTextColor(if ((r.heatStatus ?: 0) >= 2) Theme.heatColor(r.heatStatus) else Theme.TEXT_DIM)
        batteryBar.set(r.batteryC)
        cpuBar.set(r.cpuC)
        gpuBar.set(r.gpuC)

        message.text = when {
            ok -> "This session: ${duration(r.sessionSeconds)} of gameplay"
            else -> MonitorService.headline(r)
        }
        message.setTextColor(if (r.status == Reading.Status.ERROR) Theme.BAD else Theme.TEXT_DIM)
    }

    private fun onToggle() {
        if (MonitorService.running) {
            stopService(Intent(this, MonitorService::class.java))
            // The final summary is written on the polling thread as it shuts down.
            mainThread.postDelayed(::refresh, 500)
            return
        }
        if (!Shizuku.pingBinder()) {
            message.text = "Shizuku is not running. Start it in the Shizuku app, then try again."
            return
        }
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            message.text = "Asking Shizuku for permission…"
            Shizuku.requestPermission(SHIZUKU_REQUEST)
            return
        }
        startMonitoring()
    }

    private fun startMonitoring() {
        // Without this the service still runs, but the live-FPS notification is hidden.
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFY_REQUEST)
        }
        startForegroundService(Intent(this, MonitorService::class.java))
        mainThread.postDelayed(::refresh, 200)
    }

    // ---------------------------------------------------------------- sessions tab

    private fun sessionsPage(): View {
        sessionList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        return page(sessionList)
    }

    private fun loadSessions() {
        sessionList.removeAllViews()
        val sessions = Sessions.recent(this)
        if (sessions.isEmpty()) {
            sessionList.addView(spaced(body("No sessions yet.\nStart monitoring and play a match.", 15f).apply {
                gravity = Gravity.CENTER
            }, 40f))
            return
        }
        sessions.forEach { sessionList.addView(spaced(sessionCard(it), 10f)) }
    }

    private fun sessionCard(s: Sessions.Summary): View {
        val fpsColor = Theme.fpsColor(s.avgFps, s.targetFps)
        val title = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(label(dateFormat.format(s.started).uppercase(), 13f), LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(label(duration(s.gameplaySeconds) + " PLAYED", 13f))
        }
        val fps = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            addView(label("%.1f".format(s.avgFps), 34f, fpsColor).apply { letterSpacing = 0f })
            addView(label("  AVG FPS" + (s.targetFps?.let { " / %.0f".format(it) } ?: ""), 14f).apply {
                setPadding(0, 0, 0, dp(6f))
            })
        }
        val stats = body(
            "%.1f stutters/min · %d skipped%s".format(
                s.stuttersPerMin, s.skipped, s.worstFps?.let { " · worst second %.0f".format(it) } ?: "",
            ),
        )
        val heat = s.maxBatteryC?.let {
            body("Battery %s → %.1f °C · heat peak %s".format(
                s.startBatteryC?.let { b -> "%.1f".format(b) } ?: "?", it,
                s.maxHeatStatus?.let { h -> Reading.heatName(h) } ?: "?",
            ), color = Theme.heatColor(s.maxHeatStatus).takeIf { (s.maxHeatStatus ?: 0) >= 2 } ?: Theme.TEXT_DIM)
        }
        val hint = label("TAP FOR GRAPH", 11f, accent.color)
        val card = card(title, spaced(fps, 4f), spaced(stats, 4f)).apply {
            heat?.let { addView(spaced(it, 2f)) }
            addView(spaced(hint, 8f))
        }

        var graph: Graph? = null
        card.setOnClickListener {
            graph?.let {
                card.removeView(it)
                graph = null
                hint.text = "TAP FOR GRAPH"
                return@setOnClickListener
            }
            val g = Graph(this, accent.color, heightDp = 130f)
            graph = g
            hint.text = "FPS (BAND = LOWEST–HIGHEST) · RED DOTS = STUTTERS · ORANGE = BATTERY °C"
            card.addView(spaced(g, 8f))
            // Rust reads and shrinks the log; keep the file work off the main thread.
            thread {
                val series = FpsNative.sessionSeries(s.csv.path, GRAPH_POINTS) ?: DoubleArray(0)
                mainThread.post { g.setSession(series, s.targetFps) }
            }
        }
        return card
    }

    // ---------------------------------------------------------------- settings tab

    private fun settingsPage(): View = page(
        spaced(card(
            label("ACCENT THEME", 13f),
            spaced(accentChips(), 10f),
        ), 4f),
        spaced(overlayCard()),
        spaced(card(
            label("ABOUT", 13f),
            spaced(body("Reads BGMI's frame stats from Android's display system through Shizuku, " +
                "twice a second. It never touches the game.", 13f), 6f),
        )),
    )

    /** Tell a running monitor to re-apply overlay settings straight away. */
    private fun settingsChanged() = MonitorService.settingsChanged?.invoke()

    private val switchTint by lazy {
        ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(accent.color, Theme.TEXT_DIM),
        )
    }

    /** Switch bound to a boolean setting; [onChange] runs after it is saved. */
    private fun switch(prefs: SharedPreferences, text: String, key: String, default: Boolean, onChange: () -> Unit = {}) =
        Switch(this).apply {
            this.text = text
            textSize = 15f
            setTextColor(Theme.TEXT)
            thumbTintList = switchTint
            trackTintList = switchTint
            setPadding(0, dp(10f), 0, dp(10f))
            isChecked = prefs.getBoolean(key, default)
            setOnCheckedChangeListener { _, on ->
                prefs.edit().putBoolean(key, on).apply()
                settingsChanged()
                onChange()
            }
        }

    /** Row of pill chips; exactly one is selected. */
    private fun chips(options: List<Pair<String, Int>>, selected: Int, onPick: (Int) -> Unit): LinearLayout {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val views = options.map { (name, color) ->
            label(name, 13f, color).apply {
                gravity = Gravity.CENTER
                setPadding(dp(8f), dp(9f), dp(8f), dp(9f))
            }
        }
        fun paint(sel: Int) = views.forEachIndexed { i, v ->
            val color = options[i].second
            v.background = Theme.pill(this, color, filled = i == sel)
            v.setTextColor(if (i == sel) Theme.BG else color)
        }
        views.forEachIndexed { i, v ->
            v.setOnClickListener {
                paint(i)
                onPick(i)
            }
            row.addView(v, LinearLayout.LayoutParams(0, WRAP, 1f).apply {
                if (i > 0) leftMargin = dp(6f)
            })
        }
        paint(selected)
        return row
    }

    private fun accentChips(): View {
        val accents = Theme.Accent.entries
        return chips(accents.map { it.label.uppercase() to it.color }, accents.indexOf(accent)) { i ->
            UiSettings.prefs(this).edit().putString(UiSettings.ACCENT, accents[i].name).apply()
            settingsChanged()
            recreate()
        }
    }

    private fun overlayCard(): View {
        val prefs = OverlaySettings.prefs(this)
        overlayPermission = pillButton("ALLOW \"DISPLAY OVER OTHER APPS\"") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }.apply {
            textSize = 14f
            background = Theme.pill(this@MainActivity, Theme.WARN, filled = false)
            setTextColor(Theme.WARN)
        }
        val background = chips(
            listOf("BLACK" to Theme.TEXT, "TRANSPARENT" to Theme.TEXT),
            if (prefs.getBoolean(OverlaySettings.BLACK_BG, true)) 0 else 1,
        ) { i ->
            prefs.edit().putBoolean(OverlaySettings.BLACK_BG, i == 0).apply()
            settingsChanged()
        }
        val sizes = chips(listOf("S", "M", "L").map { it to Theme.TEXT }, prefs.getInt(OverlaySettings.SIZE, 1)) { i ->
            prefs.edit().putInt(OverlaySettings.SIZE, i).apply()
            settingsChanged()
        }
        return card(
            label("FLOATING OVERLAY", 13f),
            spaced(overlayPermission, 10f),
            switch(prefs, "Show overlay while monitoring", OverlaySettings.ENABLED, false),
            spaced(label("BACKGROUND", 11f), 4f),
            spaced(background, 6f),
            spaced(label("SHOW ON OVERLAY", 11f), 10f),
            meterSwitches(prefs),
            spaced(label("SIZE", 11f), 10f),
            spaced(sizes, 6f),
            spaced(switch(prefs, "Stack meters vertically", OverlaySettings.VERTICAL, false), 6f),
            switch(prefs, "Only show over BGMI", OverlaySettings.ONLY_GAME, true),
            switch(prefs, "Flash red on stutter", OverlaySettings.FLASH, false),
            switch(prefs, "Lock position (touches go to the game)", OverlaySettings.LOCKED, true),
            spaced(body("To move it: turn off Lock (it then shows on any screen), drag it, then lock it again.", 13f), 4f),
        )
    }

    /** One switch per meter, plus a hint when all are off (the overlay then hides itself). */
    private fun meterSwitches(prefs: SharedPreferences): View {
        val hint = body("Turn on at least one meter to show the overlay.", 13f, Theme.WARN)
        fun updateHint() {
            hint.visibility = if (OverlayMeter.entries.any { it.enabled(prefs) }) View.GONE else View.VISIBLE
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            OverlayMeter.entries.forEach { meter ->
                addView(switch(prefs, meter.title, meter.key, meter.default, ::updateHint))
            }
            addView(hint)
            updateHint()
        }
    }

    private fun duration(seconds: Double): String {
        val total = seconds.toInt()
        return "%d:%02d".format(total / 60, total % 60)
    }

    private companion object {
        const val SHIZUKU_REQUEST = 1
        const val NOTIFY_REQUEST = 2
        const val GRAPH_POINTS = 200
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}
