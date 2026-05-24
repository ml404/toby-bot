package database.dto.lottery

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.NamedQueries
import jakarta.persistence.NamedQuery
import jakarta.persistence.Table
import org.springframework.transaction.annotation.Transactional
import java.io.Serializable

/**
 * One row per (lottery, user) tracking the cumulative ticket count and
 * total credits the user has spent on this lottery.
 *
 * - For TICKET_WEIGHTED lotteries: weighting for the draw is by
 *   `ticket_count`; `spent` is kept for refunds on cancel.
 * - For NUMBER_MATCH lotteries: each ticket carries the user's picks
 *   in `picked_numbers` (comma-separated, sorted). One row per user
 *   per draw; buying again on the same draw replaces picks (treat
 *   each pick set as one ticket — `ticket_count` stays at 1 for
 *   NUMBER_MATCH).
 */
@NamedQueries(
    NamedQuery(
        name = "JackpotLotteryTicketDto.get",
        query = "select t from JackpotLotteryTicketDto t " +
                "where t.lotteryId = :lotteryId and t.discordId = :discordId"
    ),
    NamedQuery(
        name = "JackpotLotteryTicketDto.byLottery",
        query = "select t from JackpotLotteryTicketDto t where t.lotteryId = :lotteryId"
    )
)
@Entity
@Table(name = "toby_coin_jackpot_lottery_ticket", schema = "public")
@IdClass(JackpotLotteryTicketId::class)
@Transactional
class JackpotLotteryTicketDto(
    @Id
    @Column(name = "lottery_id")
    var lotteryId: Long = 0,

    @Id
    @Column(name = "discord_id")
    var discordId: Long = 0,

    @Column(name = "ticket_count", nullable = false)
    var ticketCount: Int = 0,

    @Column(name = "spent", nullable = false)
    var spent: Long = 0,

    /**
     * Comma-separated picks for NUMBER_MATCH tickets, sorted ascending.
     * Null for TICKET_WEIGHTED tickets.
     */
    @Column(name = "picked_numbers")
    var pickedNumbers: String? = null,

    /**
     * Free tickets earned from bulk-buy bonus tiers, accumulated across
     * every TICKET_WEIGHTED `/lottery buy N` the user makes on this
     * lottery. Counted as additional weight in the draw alongside the
     * volume multiplier (see [LotteryHelper] / [JackpotLotteryService.effectiveWeight]).
     * Always 0 for NUMBER_MATCH tickets.
     */
    @Column(name = "bonus_tickets", nullable = false)
    var bonusTickets: Long = 0,
) : Serializable

data class JackpotLotteryTicketId(
    var lotteryId: Long = 0,
    var discordId: Long = 0,
) : Serializable
