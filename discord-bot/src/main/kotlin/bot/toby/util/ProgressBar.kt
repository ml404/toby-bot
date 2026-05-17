package bot.toby.util

object ProgressBar {
    // String (not Char) because the handle emoji 🔘 is outside the BMP and
    // needs a surrogate pair on the JVM. Filled / empty are single-codeunit
    // BMP chars, but we keep them as String for uniformity.
    private const val FILLED = "▬" // ▬
    private const val EMPTY = "—"  // —
    private const val HANDLE = "🔘" // 🔘 (U+1F518)

    fun render(positionMs: Long, durationMs: Long, cells: Int = 20): String {
        require(cells > 0) { "cells must be > 0" }
        if (durationMs <= 0L) return EMPTY.repeat(cells)
        val ratio = (positionMs.toDouble() / durationMs.toDouble()).coerceIn(0.0, 1.0)
        val handleIndex = (ratio * (cells - 1)).toInt().coerceIn(0, cells - 1)
        return buildString {
            repeat(cells) { i ->
                when {
                    i == handleIndex -> append(HANDLE)
                    i < handleIndex -> append(FILLED)
                    else -> append(EMPTY)
                }
            }
        }
    }
}
