package database.poker

import java.time.Instant

/**
 * Mutable state of a single poker table. All mutations happen inside a
 * monitor on the table object (see [PokerTableRegistry.lockTable]) so
 * concurrent button clicks / web POSTs serialise around hand state.
 *
 * `chips` on each [Seat] are escrow — the player's `socialCredit` was
 * debited at buy-in; nothing else touches it until cash-out.
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
    var lastResult: HandResult? = null
) {

    enum class Phase { WAITING, PRE_FLOP, FLOP, TURN, RIVER }

    enum class SeatStatus { ACTIVE, FOLDED, ALL_IN, SITTING_OUT }

    class Seat(
        val discordId: Long,
        var chips: Long,
        var holeCards: List<Card> = emptyList(),
        var committedThisRound: Long = 0L,
        var totalCommittedThisHand: Long = 0L,
        var status: SeatStatus = SeatStatus.SITTING_OUT
    )

    /**
     * Snapshot of how the hand resolved. `winners` may have multiple
     * entries on a chopped pot. `payoutByDiscordId` is what each winner
     * netted from the pot (the slice they were credited as chips, after
     * the rake came off). Seat-level chip mutations have already been
     * applied to [seats] when this is returned.
     */
    data class HandResult(
        val handNumber: Long,
        val winners: List<Long>,
        val payoutByDiscordId: Map<Long, Long>,
        val pot: Long,
        val rake: Long,
        val board: List<Card>,
        val revealedHoleCards: Map<Long, List<Card>>,
        val resolvedAt: Instant
    )
}
