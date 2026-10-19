package dev.fpsmonitor

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Session files live in the app's private storage, files/sessions/:
 * `<start time>.csv` (every reading) and `<start time>.summary` (key=value),
 * both written by the Rust side.
 */
object Sessions {
    private val fileName = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)

    private fun dir(context: Context) = File(context.filesDir, "sessions").apply { mkdirs() }

    fun newLogFile(context: Context): File = File(dir(context), fileName.format(Date()) + ".csv")

    /** Newest first; sessions with under [minSeconds] of gameplay are skipped. */
    fun recent(context: Context, limit: Int = 20, minSeconds: Double = 5.0): List<Summary> =
        dir(context).listFiles { f -> f.name.endsWith(".summary") }.orEmpty()
            .sortedByDescending { it.name }
            .mapNotNull { Summary.read(it) }
            .filter { it.gameplaySeconds >= minSeconds }
            .take(limit)

    data class Summary(
        /** The session's per-reading log, for the match graph. */
        val csv: File,
        /** PUBG version played, e.g. "BGMI"; null for sessions logged before multi-game support. */
        val game: String?,
        val started: Date,
        val gameplaySeconds: Double,
        val avgFps: Double,
        val targetFps: Double?,
        val worstFps: Double?,
        val skipped: Long,
        val stutters: Long,
        val stuttersPerMin: Double,
        val longPauses: Long,
        val startBatteryC: Double?,
        val maxBatteryC: Double?,
        val maxHeatStatus: Int?,
    ) {
        companion object {
            fun read(file: File): Summary? = runCatching {
                val v = file.readLines().mapNotNull { line ->
                    line.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] }
                }.toMap()
                Summary(
                    csv = File(file.parentFile, file.nameWithoutExtension + ".csv"),
                    game = v["game"]?.takeIf { it.isNotBlank() },
                    started = fileName.parse(file.nameWithoutExtension)!!,
                    gameplaySeconds = v.getValue("gameplay_seconds").toDouble(),
                    avgFps = v.getValue("avg_fps").toDouble(),
                    targetFps = v["target_fps"]?.toDoubleOrNull(),
                    worstFps = v["worst_1s_fps"]?.toDoubleOrNull(),
                    skipped = v.getValue("skipped").toLong(),
                    stutters = v.getValue("stutters").toLong(),
                    stuttersPerMin = v.getValue("stutters_per_min").toDouble(),
                    longPauses = v.getValue("long_pauses").toLong(),
                    startBatteryC = v["start_battery_c"]?.toDoubleOrNull(),
                    maxBatteryC = v["max_battery_c"]?.toDoubleOrNull(),
                    maxHeatStatus = v["max_heat_status"]?.toIntOrNull(),
                )
            }.getOrNull()
        }
    }
}
