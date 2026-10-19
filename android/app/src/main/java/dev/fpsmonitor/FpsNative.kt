package dev.fpsmonitor

/** Rust side (fps-jni). One handle = one monitoring session. */
object FpsNative {
    init {
        System.loadLibrary("fps_jni")
    }

    /** [logPath] is the session's .csv file, or "" for no log. */
    @JvmStatic external fun create(packageName: String, logPath: String): Long

    /** See fps-jni/src/lib.rs for the layout of the returned array. */
    @JvmStatic external fun feed(handle: Long, dump: String, nowNanos: Long): DoubleArray?

    /** Filtered thermalservice + battery text; values show up in the next [feed]. */
    @JvmStatic external fun feedThermal(handle: Long, text: String)

    /** Last minute of smoothed FPS, oldest first. Call on the polling thread only. */
    @JvmStatic external fun history(handle: Long): DoubleArray?

    /**
     * Match graph from a session .csv: [POINT_STRIDE] values per point
     * (t, fpsMin, fpsAvg, fpsMax, stutters, batteryC, cpuC; missing = -1).
     * Reads a file, so call it off the main thread.
     */
    @JvmStatic external fun sessionSeries(csvPath: String, maxPoints: Int): DoubleArray?

    const val POINT_STRIDE = 7

    /** Writes the final summary and frees the session. */
    @JvmStatic external fun destroy(handle: Long)
}

/** One poll's result, decoded from the array [FpsNative.feed] returns. */
data class Reading(
    val status: Status,
    val fps: Double = 0.0,
    val frames: Long = 0,
    val skipped: Long = 0,
    val stutters: Long = 0,
    val longPauses: Long = 0,
    val sessionFps: Double = 0.0,
    val sessionSeconds: Double = 0.0,
    val sessionFrames: Long = 0,
    val sessionSkipped: Long = 0,
    val sessionStutters: Long = 0,
    val sessionLongPauses: Long = 0,
    /** FPS over the last ~1 s; null until there is a window. */
    val smoothedFps: Double? = null,
    /** Lowest 1-second FPS while the game was drawing; null until known. */
    val worstFps: Double? = null,
    val logOk: Boolean = false,
    /** Frame rate the game is aiming for (30/60/90/120…); null until known. */
    val targetFps: Double? = null,
    /** Android heat status: 0 None, 1 Light, 2 Moderate, 3 Severe… */
    val heatStatus: Int? = null,
    val batteryC: Double? = null,
    val cpuC: Double? = null,
    val gpuC: Double? = null,
    val skinC: Double? = null,
    val maxHeatStatus: Int? = null,
    val maxBatteryC: Double? = null,
    /** Last minute of smoothed FPS for the live graph; only filled while the app screen is open. */
    val trail: DoubleArray? = null,
    val error: String? = null,
) {
    enum class Status { NO_GAME, FIRST, OK, RESET, ERROR }

    companion object {
        fun from(v: DoubleArray) = Reading(
            status = Status.entries[v[0].toInt()],
            fps = v[1],
            frames = v[2].toLong(),
            skipped = v[3].toLong(),
            stutters = v[4].toLong(),
            longPauses = v[5].toLong(),
            sessionFps = v[6],
            sessionSeconds = v[7],
            sessionFrames = v[8].toLong(),
            sessionSkipped = v[9].toLong(),
            sessionStutters = v[10].toLong(),
            sessionLongPauses = v[11].toLong(),
            smoothedFps = v[12].takeIf { it >= 0 },
            worstFps = v[13].takeIf { it >= 0 },
            logOk = v[14] > 0,
            targetFps = v[15].takeIf { it >= 0 },
            heatStatus = v[16].takeIf { it >= 0 }?.toInt(),
            batteryC = v[17].takeIf { it >= 0 },
            cpuC = v[18].takeIf { it >= 0 },
            skinC = v[19].takeIf { it >= 0 },
            maxHeatStatus = v[20].takeIf { it >= 0 }?.toInt(),
            maxBatteryC = v[21].takeIf { it >= 0 },
            gpuC = v[22].takeIf { it >= 0 },
        )

        fun heatName(status: Int): String =
            listOf("None", "Light", "Moderate", "Severe", "Critical", "Emergency", "Shutdown")
                .getOrElse(status) { "Unknown" }

        fun error(message: String) = Reading(Status.ERROR, error = message)
    }
}
