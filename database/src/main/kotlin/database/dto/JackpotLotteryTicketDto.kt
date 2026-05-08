package database.dto

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
 * total credits the user has spent on this lottery. Weighting for the
 * draw is by `ticket_count`; `spent` is kept for refunds on cancel.
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
) : Serializable

data class JackpotLotteryTicketId(
    var lotteryId: Long = 0,
    var discordId: Long = 0,
) : Serializable
