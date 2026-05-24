package database.dto.lottery

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.NamedQueries
import jakarta.persistence.NamedQuery
import jakarta.persistence.Table
import org.hibernate.annotations.DynamicUpdate
import org.springframework.transaction.annotation.Transactional
import java.io.Serializable
import java.time.Instant

/**
 * Per-guild jackpot lottery event row. Two flavours coexist on the
 * same table, distinguished by [mode]:
 *
 *  - `TICKET_WEIGHTED`: admin-fired one-shot. Each ticket is a weight
 *    in a top-K winner draw. `pickCount` / `numberMax` / `drawnNumbers`
 *    are unused.
 *  - `NUMBER_MATCH`: daily auto-draw. Players pick 5 numbers from
 *    1-49; the job draws 5 winning numbers; payouts tier by match
 *    count. `winnerCount` is unused (any number of holders can match).
 *
 * V28 added a partial unique index on (guild_id, mode) where status =
 * 'OPEN', so one OPEN row per (guild, mode) is allowed — both flavours
 * can run concurrently.
 */
@NamedQueries(
    NamedQuery(
        name = "JackpotLotteryDto.getOpenByGuildAndMode",
        query = "select l from JackpotLotteryDto l " +
                "where l.guildId = :guildId and l.mode = :mode and l.status = 'OPEN'"
    ),
    NamedQuery(
        name = "JackpotLotteryDto.getLatestByGuildAndMode",
        query = "select l from JackpotLotteryDto l " +
                "where l.guildId = :guildId and l.mode = :mode " +
                "order by l.openedAt desc"
    )
)
@Entity
@DynamicUpdate
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
    var status: String = "OPEN",

    @Column(name = "mode", nullable = false, length = 16)
    var mode: String = MODE_TICKET_WEIGHTED,

    @Column(name = "pick_count", nullable = false)
    var pickCount: Int = 0,

    @Column(name = "number_max", nullable = false)
    var numberMax: Int = 0,

    /**
     * Comma-separated winning numbers for NUMBER_MATCH draws. Null
     * until status flips to DRAWN. Sorted ascending for stable display.
     */
    @Column(name = "drawn_numbers")
    var drawnNumbers: String? = null,

    /**
     * Discord channel + message ids of the announce embed posted by
     * [LotteryAnnouncer.announceCycle]. Captured after the first send so
     * a periodic refresh job can edit the embed when [poolAmount] grows.
     * Cleared back to null when the message is found-not-found on edit
     * (admin/user deleted it).
     */
    @Column(name = "announcement_channel_id")
    var announcementChannelId: Long? = null,

    @Column(name = "announcement_message_id")
    var announcementMessageId: Long? = null,

    /**
     * Last [poolAmount] value pushed to the announce embed. Lets the
     * refresh job short-circuit when the pool hasn't grown — no
     * round-trip to Discord on quiet ticks.
     */
    @Column(name = "announced_pool_amount")
    var announcedPoolAmount: Long? = null,

    /**
     * Stable digest (SHA-256 hex) of the participation-incentive tiers
     * that were live at the most recent announce/refresh. Lets the
     * refresh job detect a mid-lottery config edit (web UI tier
     * change) and re-render the "Active incentives" embed field even
     * when [announcedPoolAmount] hasn't moved. Null before the first
     * announce; cleared together with [announcedPoolAmount] when the
     * announcement reference is dropped.
     */
    @Column(name = "announced_incentives_digest", length = 64)
    var announcedIncentivesDigest: String? = null,

    /**
     * Highest guild-wide ticket-count milestone that has already paid
     * out on this lottery. Set on each `/lottery buy` to the highest
     * threshold the new running total crossed, so each milestone fires
     * exactly once per lottery row. 0 means no milestone has fired.
     */
    @Column(name = "milestones_fired", nullable = false)
    var milestonesFired: Long = 0,
) : Serializable {

    companion object {
        const val STATUS_OPEN = "OPEN"
        const val STATUS_DRAWN = "DRAWN"
        const val STATUS_CANCELLED = "CANCELLED"

        const val MODE_TICKET_WEIGHTED = "TICKET_WEIGHTED"
        const val MODE_NUMBER_MATCH = "NUMBER_MATCH"
    }
}
