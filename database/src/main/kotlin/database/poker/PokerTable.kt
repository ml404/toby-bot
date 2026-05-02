package database.poker

import database.card.Card
import database.card.Deck
import java.time.Instant

/**
 * Mutable state of a single poker table. All mutations happen inside a
 * monitor on the table object (see [PokerTableRegistry.lockTable]) so
 * concurrent button clicks / web POSTs serialise around hand state.
 *
 * `chips` on each [Seat] are escrow — the player's `socialCredit` was
 * debited at buy-in; nothing else touches it until cash-out. On
 * free-play tables ([isFreePlay]), the same chip mechanics run but
 * the wallet is never touched and the chips are play money.
 */
class PokerTable(
    val id: Long,
    val guildId: Long,
    val hostDiscordId: Long,
    val minBuyIn: Long,
    val maxBuyIn: Long,
    val smallBlind: Long,
    val bigBlind: Long,
    val smallBet: Long,
    val bigBet: Long,
    val maxRaisesPerStreet: Int,
    val maxSeats: Int,
    /**
     * v2 (PR #v2-2): per-actor decision deadline snapshotted at table
     * creation. `0` means the shot clock is disabled and the table
     * only ever closes via the idle sweeper. Snapshotted (not read
     * live) so a guild admin can't shorten the clock under a player
     * who is actively thinking about a decision.
     */
    val shotClockSeconds: Int = 0,
    /**
     * v2-7: free-play flag snapshotted at create time. When `true`,
     * [database.service.PokerService] skips every wallet write
     * (buy-in / rebuy debit, cash-out / sweep / evict credit) and
     * skips the jackpot route + `poker_hand_log` persistence on hand
     * resolution. The engine itself stays oblivious — it only cares
     * about chip counts, which are play money on free tables. Flag
     * is per-table (not per-guild), set once at construction, and
     * never mutates so a started hand can never flip into or out of
     * free-play under a player.
     */
    val isFreePlay: Boolean = false,
    var phase: Phase = Phase.WAITING,
    val seats: MutableList<Seat> = mutableListOf(),
    val community: MutableList<Card> = mutableListOf(),
    var pot: Long = 0L,
    var currentBet: Long = 0L,
    var raisesThisStreet: Int = 0,
    var dealerIndex: Int = 0,
    var actorIndex: Int = 0,
    var seatsToAct: Int = 0,
    var deck: Deck? = null,
    var handNumber: Long = 0L,
    var lastActivityAt: Instant = Instant.now(),
    var lastResult: HandResult? = null,
    /**
     * v2: when the current actor's shot clock fires, or null if no
     * hand is in progress, the clock is disabled, or the deadline
     * has not yet been armed for this actor. The registry's
     * scheduled future does the actual auto-fold; this field exists
     * so the [database.service.PokerService] / web projection can
     * render a countdown without touching scheduler internals.
     */
    var currentActorDeadline: Instant? = null
) {

    enum class Phase { WAITING, PRE_FLOP, FLOP, TURN, RIVER }

    enum class SeatStatus { ACTIVE, FOLDED, ALL_IN, SITTING_OUT }

    class Seat(
        val discordId: Long,
        var chips: Long,
        var holeCards: List<Card> = emptyList(),
        var committedThisRound: Long = 0L,
        var totalCommittedThisHand: Long = 0L,
        var status: SeatStatus = SeatStatus.SITTING_OUT,
        /**
         * v2 (PR #v2-3): set when a seated player asks to leave during
         * a hand. Honoured by [PokerEngine] (the seat is excluded from
         * the next hand's start) and by [database.service.PokerService]
         * (the seat is folded on its turn during the in-flight hand,
         * then cashed out as soon as the hand resolves). Stays `false`
         * for buy-in / between-hand cash-outs — those go through the
         * synchronous flow that removes the seat directly.
         */
        var pendingLeave: Boolean = false
    )

    /**
     * Snapshot of how the hand resolved. `winners` may have multiple
     * entries on a chopped pot. `payoutByDiscordId` is what each winner
     * netted from the pot (the slice they were credited as chips, after
     * the rake came off). Seat-level chip mutations have already been
     * applied to [seats] when this is returned.
     *
     * As of v2 (PR #v2-1), the hand splits into [pots] tiers when
     * players go all-in for different amounts. `pot` and
     * `payoutByDiscordId` remain the across-all-tiers totals for
     * back-compat with v1 callers; `pots` exposes the per-tier breakdown
     * for richer rendering and the side-pot audit log. `refundedByDiscordId`
     * captures uncontested over-commits returned to the over-committer
     * (no rake applied).
     */
    data class HandResult(
        val handNumber: Long,
        val winners: List<Long>,
        val payoutByDiscordId: Map<Long, Long>,
        val pot: Long,
        val rake: Long,
        val board: List<Card>,
        val revealedHoleCards: Map<Long, List<Card>>,
        val resolvedAt: Instant,
        val pots: List<PotResult> = emptyList(),
        val refundedByDiscordId: Map<Long, Long> = emptyMap()
    )

    /**
     * One side-pot tier from a multi-way all-in resolution.
     *
     *   - [cap] — the per-seat commitment level that this tier was
     *     peeled at (every contributor put in `cap - previousCap` to
     *     reach this tier).
     *   - [eligibleDiscordIds] — the contenders eligible to win this
     *     tier (anyone whose total commitment matched `cap`). FOLDED
     *     players are NOT eligible even if they contributed.
     *   - [amount] — chips in this tier AFTER its share of the rake.
     *   - [winners] — discord ids of the seat(s) that took this tier
     *     after hand evaluation against [eligibleDiscordIds].
     *   - [payoutByDiscordId] — chip credit each winner received from
     *     this tier specifically.
     *
     * For a hand with no all-ins (everyone matches the same final bet),
     * there is exactly one [PotResult] whose `eligibleDiscordIds`
     * equals the full contender list. Tracking it as a list either way
     * keeps the rendering layer agnostic.
     */
    data class PotResult(
        val cap: Long,
        val eligibleDiscordIds: List<Long>,
        val amount: Long,
        val winners: List<Long>,
        val payoutByDiscordId: Map<Long, Long>
    )
}
