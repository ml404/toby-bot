package database.persistence

import database.dto.PokerHandLogDto

interface PokerHandLogPersistence {
    fun insert(row: PokerHandLogDto): PokerHandLogDto

    /**
     * Most recent settled hands on [tableId] within [guildId], newest
     * first. The guild filter is intentional: tables share an in-memory
     * id namespace across guilds, so a stale id from another server
     * must never leak rows out of its own guild.
     */
    fun findRecentByTable(guildId: Long, tableId: Long, limit: Int): List<PokerHandLogDto>

    /**
     * Most recent settled hands across the entire guild (any table),
     * newest first. Used by `/poker history` when no `table:` argument
     * is given.
     */
    fun findRecentByGuild(guildId: Long, limit: Int): List<PokerHandLogDto>
}
