package database.service

import database.dto.ConfigDto

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
