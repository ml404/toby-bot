package database.service.guild

import database.dto.ConfigDto

interface ConfigService {
    fun listAllConfig(): List<ConfigDto?>?
    fun listGuildConfig(guildId: String?): List<ConfigDto?>?
    fun getConfigByName(name: String?, guildId: String?): ConfigDto?
    fun createNewConfig(configDto: ConfigDto): ConfigDto?
    fun updateConfig(configDto: ConfigDto?): ConfigDto?
    fun deleteAll(guildId: String?)
    fun deleteConfig(guildId: String?, name: String?)
    fun clearCache()

    /**
     * Insert-or-update a single (name, guildId) config row to [value].
     * Looks up the existing row via [getConfigByName] and updates if it
     * matches the same guild; otherwise creates a fresh row. Returns
     * `Created` or `Updated` so callers that care about the difference
     * (log lines, first-enable side effects) can branch on it; callers
     * that don't can ignore the result.
     *
     * Replaces the open-coded `if (existing != null && existing.guildId
     * == newDto.guildId) update else create` boilerplate that used to
     * sit in every `/setconfig` subcommand and in the moderation web
     * service.
     */
    fun upsertConfig(name: String, value: String, guildId: String): UpsertResult

    /**
     * Insert-or-update multiple `(name, value)` rows under a single
     * [guildId] in one transactional batch. Equivalent to calling
     * [upsertConfig] in a loop, but wrapped in a single `@Transactional`
     * boundary so all rows commit together (or none do). Returns the
     * per-row results in the same order as [rows] so callers can
     * correlate by index — e.g. branch on `Created` vs `Updated` for
     * first-enable side effects.
     *
     * Empty input is a no-op and returns `emptyList()`.
     *
     * Replaces hand-rolled loops like
     * `rows.forEach { (name, value) -> upsertConfig(name, value, guildId) }`
     * which committed N times instead of once.
     */
    fun upsertAll(guildId: String, rows: List<Pair<String, String>>): List<UpsertResult>

    sealed interface UpsertResult {
        val dto: ConfigDto
        data class Created(override val dto: ConfigDto) : UpsertResult
        data class Updated(override val dto: ConfigDto, val previousValue: String?) : UpsertResult
    }
}
