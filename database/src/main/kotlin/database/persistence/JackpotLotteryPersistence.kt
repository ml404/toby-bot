package database.persistence

import database.dto.JackpotLotteryDto
import database.dto.JackpotLotteryTicketDto

interface JackpotLotteryPersistence {
    /** Returns the single OPEN lottery row for [guildId], or null. */
    fun getOpenByGuild(guildId: Long): JackpotLotteryDto?

    /** SELECT … FOR UPDATE on the OPEN lottery row. Requires @Transactional. */
    fun getOpenByGuildForUpdate(guildId: Long): JackpotLotteryDto?

    fun upsert(lottery: JackpotLotteryDto): JackpotLotteryDto

    /** SELECT … FOR UPDATE on a single ticket row, or null if absent. */
    fun getTicketForUpdate(lotteryId: Long, discordId: Long): JackpotLotteryTicketDto?

    fun upsertTicket(ticket: JackpotLotteryTicketDto): JackpotLotteryTicketDto

    /** All ticket rows for [lotteryId]. Used at draw and cancel time. */
    fun ticketsByLottery(lotteryId: Long): List<JackpotLotteryTicketDto>
}
