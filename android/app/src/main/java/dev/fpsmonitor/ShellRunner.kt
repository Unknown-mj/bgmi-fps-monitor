package dev.fpsmonitor

import rikka.shizuku.Shizuku

/**
 * Runs fixed, read-only system commands with Shizuku's shell-level (uid 2000)
 * access: SurfaceFlinger timestats, and phone heat. These four commands are
 * the ONLY things this app can run: there is deliberately no way to pass in
 * any other command.
 */
object ShellRunner {
    private val ENABLE = arrayOf("dumpsys", "SurfaceFlinger", "--timestats", "-enable")
    private val CLEAR = arrayOf("dumpsys", "SurfaceFlinger", "--timestats", "-clear")

    // Keeps only the PUBG games' block lines; everything about other apps stays
    // on the phone's shell. Built by Rust from its fixed list of versions (see
    // fps_core::games::awk_filter), the same filter the PC tools use. It must
    // name the games and contain no single quote (it is wrapped in '…' below);
    // otherwise monitoring stops with a clear error instead of silently
    // matching nothing.
    private val GAME_FILTER: String = FpsNative.gameFilter()
        ?.takeIf { "SurfaceView\\[(com" in it && '\'' !in it }
        ?: throw IllegalStateException(Reading.OUTDATED_LIBRARY)

    // -maxlayers keeps the layers with the most frames (verified on device),
    // so after a clear the game is always among them while it is being played.
    private val READ = arrayOf(
        "sh", "-c",
        "dumpsys SurfaceFlinger --timestats -dump -maxlayers 20 | awk '$GAME_FILTER'",
    )

    // Heat status, live sensor temperatures and battery temperature (~3 KB).
    private val HEAT = arrayOf(
        "sh", "-c",
        "dumpsys thermalservice | grep -E '^Thermal Status|temperatures|Temperature[{]'; " +
            "dumpsys battery | grep -E '^ +temperature:'",
    )

    fun enableStats() { run(ENABLE) }

    /** Heat lines for the Rust side to parse. */
    fun readHeat(): String = run(HEAT)

    /** Forget all past layers, so dumps stay small and new layers get tracked. */
    fun clearStats() { run(CLEAR) }

    /** The PUBG games' lines from the timestats dump (usually 1–4 KB). */
    fun readGameStats(): String = run(READ)

    // Shizuku 13 made newProcess private to push apps toward a UserService.
    // For short read-only commands, calling it directly is much simpler, and
    // output comes over a pipe, so there is no Binder size limit.
    private val newProcess = Shizuku::class.java.getDeclaredMethod(
        "newProcess",
        Array<String>::class.java,
        Array<String>::class.java,
        String::class.java,
    ).apply { isAccessible = true }

    private fun run(cmd: Array<String>): String {
        val process = newProcess.invoke(null, cmd, null, null) as Process
        val out = process.inputStream.bufferedReader().readText()
        val err = process.errorStream.bufferedReader().readText()
        val code = process.waitFor()
        if (code != 0) error("${cmd.joinToString(" ").take(60)} exited with $code: $err")
        return out
    }
}
