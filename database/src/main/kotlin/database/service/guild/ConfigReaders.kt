package database.service.guild

import database.dto.guild.ConfigDto
import database.service.guild.ConfigService

/**
 * Read a per-guild Long config value with a default fallback and a
 * lower-bound coercion.
 *
 * Mirrors the local `cfgLong` helper in `BlackjackService.readMultiTableParams`
 * — hoisted out so every game service can read its own min/max stake config
 * without duplicating the boilerplate. Returns [default] when the row is
 * missing or unparseable; otherwise coerces the parsed value to be at least
 * [min] (so an admin who sets `*_MAX_STAKE = 0` doesn't silently disable a
 * game).
 */
fun ConfigService.cfgLong(
    key: ConfigDto.Configurations,
    guildId: Long,
    default: Long,
    min: Long,
): Long {
    val raw = getConfigByName(key.configValue, guildId.toString())?.value
    return raw?.toLongOrNull()?.coerceAtLeast(min) ?: default
}

/**
 * Read a per-guild Long config value where `0` is a sentinel meaning "no
 * upper cap." For `*_MAX_STAKE` / `POKER_MAX_BUY_IN` / `BLACKJACK_MAX_ANTE`
 * style fields admins can type `0` instead of a giant number to remove
 * the ceiling. Returns [Long.MAX_VALUE] when stored value is exactly `0L`,
 * [default] when missing/unparseable, otherwise coerces to be at least
 * [min] (defensive — write-time validation already enforces this).
 *
 * `JACKPOT_STAKE_ANCHOR` and `*_MIN_STAKE` keys keep using [cfgLong] —
 * they have no "unlimited" semantics (anchor is a divisor; min < 1 has
 * no meaning).
 */
fun ConfigService.cfgLongMax(
    key: ConfigDto.Configurations,
    guildId: Long,
    default: Long,
    min: Long,
): Long {
    val raw = getConfigByName(key.configValue, guildId.toString())?.value?.toLongOrNull()
        ?: return default
    if (raw == 0L) return Long.MAX_VALUE
    return raw.coerceAtLeast(min)
}
