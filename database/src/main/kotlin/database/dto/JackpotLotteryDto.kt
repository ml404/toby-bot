package database.dto

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.NamedQueries
import jakarta.persistence.NamedQuery
import jakarta.persistence.Table
import org.springframework.transaction.annotation.Transactional
import java.io.Serializable
import java.time.Instant

/**
 * Per-guild jackpot lottery event row.
 *
 * One OPEN row per guild at any time (enforced by a partial unique
 * index in V27). When the admin runs `/jackpotadmin lottery_draw`, the
 * service marks the row DRAWN and writes ticket-weighted payouts. On
 * `lottery_cancel` the row goes CANCELLED, all ticket spend is
 * refunded, and `pool_amount` returns to the per-guild jackpot pool.
 */
@NamedQueries(
    NamedQuery(
        name = "JackpotLotteryDto.getOpenByGuild",
        query = "select l from JackpotLotteryDto l " +
                "where l.guildId = :guildId and l.status = 'OPEN'"
    )
)
@Entity
@Table(name = "toby_coin_jackpot_lottery", schema = "public")
@Transactional
class JackpotLotteryDto(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    var id: Long? = null,

    @Column(name = "guild_id", nullable = false)
    var guildId: Long = 0,

    @Column(name = "ticket_price", nullable = false)
    var ticketPrice: Long = 0,

    @Column(name = "pool_amount", nullable = false)
    var poolAmount: Long = 0,

    @Column(name = "winner_count", nullable = false)
    var winnerCount: Int = 0,

    @Column(name = "opened_at", nullable = false)
    var openedAt: Instant = Instant.EPOCH,

    @Column(name = "closes_at", nullable = false)
    var closesAt: Instant = Instant.EPOCH,

    @Column(name = "status", nullable = false, length = 16)
    var status: String = STATUS_OPEN,
) : Serializable {

    companion object {
        const val STATUS_OPEN = "OPEN"
        const val STATUS_DRAWN = "DRAWN"
        const val STATUS_CANCELLED = "CANCELLED"
    }
}
