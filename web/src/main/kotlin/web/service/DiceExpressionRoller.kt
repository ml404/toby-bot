package web.service

import kotlin.random.Random

/**
 * Parser + roller for dice expressions like `2d6+3` and bare integers like `45`.
 * Shared by combat damage/heal application, monster HP rolling, and monster
 * attack damage rolling so they stay consistent on caps and accepted syntax.
 */
object DiceExpressionRoller {

    const val MAX_DICE_COUNT = 20
    const val MAX_DICE_MODIFIER = 50
    const val MAX_LITERAL_AMOUNT = 1000
    val ALLOWED_DIE_SIDES = setOf(4, 6, 8, 10, 12, 20, 100)

    private val DICE_EXPR_REGEX = Regex("^(\\d*)d(\\d+)([+-]\\d+)?$", RegexOption.IGNORE_CASE)

    data class ParsedDice(val count: Int, val sides: Int, val modifier: Int)

    /**
     * Parsed result of a [parseAmount] call. [expression] is non-null only when
     * the input was a dice formula; in that case [rolls] holds the individual
     * faces so callers can narrate "rolled 2d6+3 = 11".
     */
    data class RolledAmount(
        val total: Int,
        val expression: String?,
        val rolls: List<Int>?
    )

    fun parseDiceExpression(raw: String): ParsedDice? {
        val cleaned = raw.filterNot { it.isWhitespace() }
        if (cleaned.isEmpty()) return null
        val match = DICE_EXPR_REGEX.matchEntire(cleaned) ?: return null
        val count = match.groupValues[1].ifEmpty { "1" }.toIntOrNull() ?: return null
        val sides = match.groupValues[2].toIntOrNull() ?: return null
        val modifier = match.groupValues[3].takeIf { it.isNotEmpty() }?.toIntOrNull() ?: 0
        return ParsedDice(count, sides, modifier)
    }

    /**
     * Accept either an integer (`"6"`) or a dice expression (`"2d6+3"`, `"d20-1"`)
     * and return a rolled amount. Integer totals outside `[0, MAX_LITERAL_AMOUNT]`
     * and dice expressions exceeding the count/side/modifier caps are rejected
     * (returns null).
     */
    fun parseAmount(raw: String, random: Random = Random): RolledAmount? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        trimmed.toIntOrNull()?.let { literal ->
            if (literal < 0 || literal > MAX_LITERAL_AMOUNT) return null
            return RolledAmount(total = literal, expression = null, rolls = null)
        }
        val parsed = parseDiceExpression(trimmed) ?: return null
        if (parsed.sides !in ALLOWED_DIE_SIDES) return null
        if (parsed.count !in 1..MAX_DICE_COUNT) return null
        if (parsed.modifier !in -MAX_DICE_MODIFIER..MAX_DICE_MODIFIER) return null
        val rolled = (0 until parsed.count).map { random.nextInt(1, parsed.sides + 1) }
        val total = (rolled.sum() + parsed.modifier).coerceAtLeast(0)
        return RolledAmount(
            total = total,
            expression = normaliseExpression(parsed),
            rolls = rolled
        )
    }

    fun normaliseExpression(parsed: ParsedDice): String {
        val mod = when {
            parsed.modifier > 0 -> "+${parsed.modifier}"
            parsed.modifier < 0 -> parsed.modifier.toString()
            else -> ""
        }
        return "${parsed.count}d${parsed.sides}$mod"
    }
}
