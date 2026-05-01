package database.blackjack

import database.card.Card
import database.card.Deck
import java.time.Instant

/**
 * Mutable state of a single blackjack table — used for both the SOLO
 * one-seat flow and the MULTI shared-dealer flow. All mutations happen
 * inside a monitor on the table object (see [BlackjackTableRegistry.lockTable])
 * so concurrent button clicks serialise around the hand state.
 *
 * Like [database.poker.PokerTable], chips on each [Seat] are escrow:
 * the player's `socialCredit` was debited at deal/buy-in time; nothing
 * else touches it until the hand resolves and the seat is paid out
 * (or evicted on idle).
 */
class BlackjackTable(
    val id: Long,
    val guildId: Long,
    val mode: Mode,
    /** Null for SOLO tables (the seated player is implicitly the host). */
    val hostDiscordId: Long?,
    /**
     * Per-hand wager. SOLO tables can in principle re-deal at a
     * different stake, but v1 makes a fresh table per `/blackjack solo`
     * call so this is effectively the only stake a SOLO table will see.
     * MULTI tables fix this at create time and every joiner pays the
     * same per hand.
     */
    val ante: Long,
    val maxSeats: Int,
    var phase: Phase = Phase.LOBBY,
    val seats: MutableList<Seat> = mutableListOf(),
    val dealer: MutableList<Card> = mutableListOf(),
    var deck: Deck? = null,
    /** Index into [seats] of whose turn it is. Only meaningful in PLAYER_TURNS. */
    var actorIndex: Int = 0,
    var handNumber: Long = 0L,
    var lastActivityAt: Instant = Instant.now(),
    var lastResult: HandResult? = null
) {

    enum class Mode { SOLO, MULTI }

    /**
     * SOLO tables fast-resolve a single hand and skip LOBBY entirely
     * (created already in PLAYER_TURNS, or RESOLVED on natural BJ).
     * MULTI tables cycle LOBBY → PLAYER_TURNS → DEALER_TURN → RESOLVED → LOBBY
     * across hands.
     */
    enum class Phase { LOBBY, PLAYER_TURNS, DEALER_TURN, RESOLVED }

    enum class SeatStatus {
        ACTIVE,
        STANDING,
        DOUBLED,
        BUSTED,
        BLACKJACK
    }

    class Seat(
        val discordId: Long,
        var hand: MutableList<Card> = mutableListOf(),
        /** What the seat originally bet for this hand (pre-double). */
        var ante: Long = 0L,
        /** Current at-risk stake — equals [ante] unless the seat doubled. */
        var stake: Long = 0L,
        var doubled: Boolean = false,
        var status: SeatStatus = SeatStatus.ACTIVE
    ) {
        /** True once the seat can no longer take an action this hand. */
        val isFinished: Boolean
            get() = status == SeatStatus.STANDING ||
                status == SeatStatus.DOUBLED ||
                status == SeatStatus.BUSTED ||
                status == SeatStatus.BLACKJACK
    }

    /**
     * Snapshot of how a hand resolved. For SOLO, [seatResults] /
     * [payouts] are single-entry maps. For MULTI, [pot] is the sum of
     * antes (after any pushed-seat refunds), [rake] is what was routed
     * to the jackpot pool, and [payouts] is what each surviving seat
     * was credited.
     */
    data class HandResult(
        val handNumber: Long,
        val dealer: List<Card>,
        val dealerTotal: Int,
        val seatResults: Map<Long, Blackjack.Result>,
        val payouts: Map<Long, Long>,
        val pot: Long,
        val rake: Long,
        val resolvedAt: Instant
    )
}
