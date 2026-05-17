package common.leveling

/**
 * MEE6-style level curve. The XP required to advance from level `n` to
 * level `n+1` is `5 * n^2 + 50 * n + 100`, and the cumulative XP needed to
 * *reach* level `n` is the sum of that polynomial for levels 0..n-1.
 *
 * Levels start at 0 (everyone is level 0 with 0 XP). The first level-up
 * happens at 100 XP, the second at 255 XP (100 + 155), and so on.
 */
object LevelCurve {

    /**
     * XP required to go from [level] to [level] + 1.
     */
    fun xpForNextLevel(level: Int): Long {
        require(level >= 0) { "level must be >= 0" }
        val n = level.toLong()
        return 5L * n * n + 50L * n + 100L
    }

    /**
     * Cumulative XP needed to *reach* [level]. Reaching level 0 costs 0.
     */
    fun cumulativeXpForLevel(level: Int): Long {
        require(level >= 0) { "level must be >= 0" }
        var total = 0L
        for (n in 0 until level) {
            total += xpForNextLevel(n)
        }
        return total
    }

    /**
     * Resolve the level a user is at given their lifetime [xp]. Negative
     * XP is clamped to 0.
     */
    fun levelForXp(xp: Long): Int {
        if (xp <= 0L) return 0
        var level = 0
        var cumulative = 0L
        while (true) {
            val next = cumulative + xpForNextLevel(level)
            if (xp < next) return level
            cumulative = next
            level++
        }
    }

    /**
     * Progress within the current level: `(level, xpIntoLevel, xpForNextLevel)`.
     * Useful for rendering a progress bar — `xpIntoLevel / xpForNextLevel`.
     */
    fun progress(xp: Long): Progress {
        val level = levelForXp(xp)
        val base = cumulativeXpForLevel(level)
        val needed = xpForNextLevel(level)
        return Progress(
            level = level,
            xpIntoLevel = (xp - base).coerceAtLeast(0L),
            xpForNextLevel = needed
        )
    }

    data class Progress(
        val level: Int,
        val xpIntoLevel: Long,
        val xpForNextLevel: Long
    ) {
        val xpRemaining: Long get() = (xpForNextLevel - xpIntoLevel).coerceAtLeast(0L)
    }
}
