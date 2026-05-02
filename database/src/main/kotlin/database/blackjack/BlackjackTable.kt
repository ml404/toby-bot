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
    var lastResult: HandResult? = null,
    /**
     * Per-actor decision deadline snapshotted by the registry when the
     * shot clock arms. `null` outside a player's turn (waiting / dealer /
     * resolved) and on tables whose `shotClockSeconds` is `0`. Exists so
     * the embed/web projection can render a countdown without touching
     * the scheduler internals.
     */
    var currentActorDeadline: Instant? = null,
    /**
     * Seconds the actor has to act before the registry auto-stands them
     * on their behalf. `0` disables the clock — the table only ever
     * closes via the idle sweeper.
     */
    val shotClockSeconds: Int = 0,
    /**
     * Per-guild rule snapshot taken at table creation. Held on the table
     * (not read live) so an admin retuning `BLACKJACK_BJ_PAYOUT_NUM` etc.
     * mid-hand can't change the payout schedule for an in-flight hand.
     */
    val rules: TableRules = TableRules()
) {

    /**
     * Snapshot of the configurable house rules for a single table. All
     * defaults match the v1 hardcoded behaviour so callers that don't
     * thread the rules through still get the original semantics.
     */
    data class TableRules(
        /** True for H17 (dealer hits soft 17), false for S17 (default). */
        val dealerHitsSoft17: Boolean = false,
        /** Natural-blackjack multiplier on the player's stake. 2.5 = 3:2 (default), 2.2 = 6:5. */
        val blackjackPayoutMultiplier: Double = 2.5,
        /** Fraction of the multi losers' pool routed to the jackpot pool. */
        val rakeFraction: Double = 0.05,
    )

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

    /**
     * One hand belonging to a seat. v1 always had exactly one hand per
     * seat; with split support a seat now holds a list of hand slots,
     * one per split branch (so 2 after a split, 3 after re-splitting,
     * etc.). Each slot carries its own stake, doubled flag, status, and
     * a "from split" marker so resolution can distinguish a true natural
     * 21 from a 21 reached by drawing onto a split.
     */
    class HandSlot(
        var cards: MutableList<Card> = mutableListOf(),
        /** Doubled-aware at-risk stake for this specific hand. */
        var stake: Long = 0L,
        var doubled: Boolean = false,
        var status: SeatStatus = SeatStatus.ACTIVE,
        /**
         * True if this slot was created by [database.service.BlackjackService]
         * splitting a pair. Two-card 21s on a split hand pay 1:1, not
         * 3:2 — the natural-blackjack premium only applies to the
         * originally-dealt pair.
         */
        var fromSplit: Boolean = false,
    )

    class Seat private constructor(
        val discordId: Long,
        /** What the seat originally bet for this hand (pre-double, pre-split). */
        val ante: Long,
        /**
         * One slot per split branch; ≥1 entry. v1 single-hand play
         * always uses [hands][0]. After the first split there are 2;
         * after re-splitting there can be more.
         */
        val hands: MutableList<HandSlot>,
        /** Index into [hands] of which split hand is currently in play. */
        var activeHandIndex: Int = 0,
        /**
         * Set when a seated player asks to leave during a hand (multi
         * mode only). Honoured by [database.service.BlackjackService] —
         * the seat is auto-stood on its turn during the in-flight hand,
         * then dropped from the table as soon as the hand resolves.
         * Stays `false` for between-hand `/blackjack leave`, which goes
         * through the immediate-removal path.
         */
        var pendingLeave: Boolean = false,
    ) {
        /**
         * Convenience constructor preserving the v1 single-hand shape so
         * existing call sites that construct a `Seat(discordId, hand,
         * ante, stake, doubled, status)` keep compiling. The [hand] /
         * [stake] / [doubled] / [status] arguments populate the seat's
         * single initial [HandSlot].
         */
        constructor(
            discordId: Long,
            hand: MutableList<Card> = mutableListOf(),
            ante: Long = 0L,
            stake: Long = 0L,
            doubled: Boolean = false,
            status: SeatStatus = SeatStatus.ACTIVE,
            pendingLeave: Boolean = false,
        ) : this(
            discordId = discordId,
            ante = ante,
            hands = mutableListOf(
                HandSlot(cards = hand, stake = stake, doubled = doubled, status = status)
            ),
            activeHandIndex = 0,
            pendingLeave = pendingLeave,
        )

        /** The currently-active hand slot — the one accepting actions. */
        val activeHand: HandSlot get() = hands[activeHandIndex]

        // Backwards-compat accessors that delegate to [activeHand] so v1
        // single-hand code (most of the service) can keep using
        // `seat.hand`, `seat.stake`, etc. without spelling out the slot.

        var hand: MutableList<Card>
            get() = activeHand.cards
            set(value) {
                activeHand.cards = value
            }

        var stake: Long
            get() = activeHand.stake
            set(value) {
                activeHand.stake = value
            }

        var doubled: Boolean
            get() = activeHand.doubled
            set(value) {
                activeHand.doubled = value
            }

        var status: SeatStatus
            get() = activeHand.status
            set(value) {
                activeHand.status = value
            }

        /** Sum of `stake` across every hand on this seat. */
        val totalStake: Long
            get() = hands.sumOf { it.stake }

        /** True once every hand on this seat is in a terminal status. */
        val isFinished: Boolean
            get() = hands.all {
                it.status == SeatStatus.STANDING ||
                    it.status == SeatStatus.DOUBLED ||
                    it.status == SeatStatus.BUSTED ||
                    it.status == SeatStatus.BLACKJACK
            }

        /**
         * Reset this seat's per-hand state for a fresh round: drop any
         * split hands, replace with a single empty active hand, clear
         * the leave flag. Called by [database.service.BlackjackService]
         * on every `startMultiHand` for surviving seats.
         */
        fun resetForNextHand(stake: Long) {
            hands.clear()
            hands.add(HandSlot(stake = stake))
            activeHandIndex = 0
            pendingLeave = false
        }
    }

    /**
     * Snapshot of how a hand resolved. For SOLO, [seatResults] /
     * [payouts] aggregate per-discordId. For MULTI, [pot] is the sum
     * of stakes (across every hand-slot, after any pushed-stake
     * refunds), [rake] is what was routed to the jackpot pool, and
     * [payouts] is what each player was credited (summed across split
     * hands if any).
     *
     * [perHandResults] carries the per-split-hand breakdown so embeds
     * and the web JSON projection can render each hand individually
     * (cards, total, doubled flag, individual outcome). For seats that
     * never split, this list has exactly one entry per seated player.
     * For seats that did split, there's one entry per split branch.
     * Older callers that only need aggregate per-discordId info can
     * stick with [seatResults] / [payouts] and ignore this field.
     */
    data class HandResult(
        val handNumber: Long,
        val dealer: List<Card>,
        val dealerTotal: Int,
        val seatResults: Map<Long, Blackjack.Result>,
        val payouts: Map<Long, Long>,
        val pot: Long,
        val rake: Long,
        val resolvedAt: Instant,
        val perHandResults: List<PerHandResult> = emptyList(),
    )

    /**
     * Outcome of a single hand-slot — one entry per (seat, split index)
     * pair on the resolved table. [handIndex] is 0-based within the
     * seat's `hands` list; on a single-hand seat it's always 0. On a
     * seat that split once it's 0 or 1; after re-splitting up to 3.
     */
    data class PerHandResult(
        val discordId: Long,
        val handIndex: Int,
        val cards: List<Card>,
        val total: Int,
        val stake: Long,
        val doubled: Boolean,
        val fromSplit: Boolean,
        val result: Blackjack.Result,
        val payout: Long,
    )
}
