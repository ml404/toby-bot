package database.persistence.lottery

import database.dto.lottery.JackpotLotteryDto
import database.dto.lottery.JackpotLotteryTicketDto

interface JackpotLotteryPersistence {
    /**
     * Returns the OPEN lottery row for [guildId] in [mode], or null.
     * One OPEN per (guild, mode) is enforced by the V28 partial unique
     * index, so this returns at most one row.
     */
    fun getOpenByGuildAndMode(guildId: Long, mode: String): JackpotLotteryDto?

    /** SELECT … FOR UPDATE on the OPEN lottery row. Requires @Transactional. */
    fun getOpenByGuildAndModeForUpdate(guildId: Long, mode: String): JackpotLotteryDto?

    /**
     * Most-recent lottery row for [guildId] in [mode], regardless of
     * status. Used for the web UI's "latest result" panel — pulls the
     * last DRAWN row by [openedAt] desc.
     */
    fun getLatestByGuildAndMode(guildId: Long, mode: String): JackpotLotteryDto?

    fun upsert(lottery: JackpotLotteryDto): JackpotLotteryDto

    /** Direct primary-key lookup. Used by the announcement-bookkeeping helpers. */
    fun findById(lotteryId: Long): JackpotLotteryDto?

    /** SELECT … FOR UPDATE on a single ticket row, or null if absent. */
    fun getTicketForUpdate(lotteryId: Long, discordId: Long): JackpotLotteryTicketDto?

    fun upsertTicket(ticket: JackpotLotteryTicketDto): JackpotLotteryTicketDto

    /** All ticket rows for [lotteryId]. Used at draw and cancel time. */
    fun ticketsByLottery(lotteryId: Long): List<JackpotLotteryTicketDto>
}
