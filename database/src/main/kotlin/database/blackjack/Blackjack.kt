package database.blackjack

import database.card.Card
import database.card.Deck
import kotlin.random.Random

/**
 * Pure-logic blackjack primitives. No Spring, no DB, no JDA — just the
 * card maths and the dealer's mechanical S17 play-out.
 *
 * Unlike [database.economy.Highlow], a single hand spans multiple
 * decisions (HIT until you stand or bust). Rather than thread an
 * immutable HandState through every call, this engine exposes small
 * primitives that mutate the supplied [Deck] / hand list. The owning
 * caller (solo flow in [database.service.BlackjackService] or the
 * multi-seat table) holds the actual state under a monitor and calls
 * each primitive as decisions come in.
 *
 * v1 rules:
 *   - Single 52-card deck per hand (reuses [database.card.Deck]).
 *   - Dealer stands on all 17, including soft 17 (S17).
 *   - Natural blackjack pays 3:2 (multiplier 2.5).
 *   - Double down: any first two cards, doubles stake, exactly one card
 *     drawn, then auto-stand.
 *   - Push refunds the stake (multiplier 1.0).
 *   - No split, surrender, insurance, dealer peek (deferred).
 */
class Blackjack(private val random: Random = Random.Default) {

    enum class Action { HIT, STAND, DOUBLE, SPLIT }

    enum class Result {
        PLAYER_BLACKJACK,
        PLAYER_WIN,
        PUSH,
        DEALER_WIN,
        PLAYER_BUST
    }

    /** Fresh shuffled 52-card deck using the engine's RNG. */
    fun newDeck(): Deck = Deck(random)

    /** Deal opening cards (2 player, 2 dealer) off the supplied [deck]. */
    fun dealStartingHands(deck: Deck): StartingDeal {
        val player = mutableListOf(deck.deal(), deck.deal())
        val dealer = mutableListOf(deck.deal(), deck.deal())
        return StartingDeal(player, dealer)
    }

    data class StartingDeal(
        val player: MutableList<Card>,
        val dealer: MutableList<Card>
    )

    /** Append one card from [deck] to [hand]. */
    fun hit(hand: MutableList<Card>, deck: Deck) {
        hand.add(deck.deal())
    }

    /**
     * Dealer's mechanical play-out: draw until the best total is at
     * least [DEALER_STAND_VALUE] (17). When [hitsSoft17] is true the
     * dealer also hits a soft 17 (H17 rule, slightly worse for the
     * player). Default is S17. Mutates [dealer].
     */
    fun playOutDealer(dealer: MutableList<Card>, deck: Deck, hitsSoft17: Boolean = false) {
        while (shouldDealerHit(dealer, hitsSoft17)) {
            dealer.add(deck.deal())
        }
    }

    private fun shouldDealerHit(dealer: List<Card>, hitsSoft17: Boolean): Boolean {
        val total = bestTotal(dealer)
        if (total < DEALER_STAND_VALUE) return true
        if (total == DEALER_STAND_VALUE && hitsSoft17 && isSoft(dealer)) return true
        return false
    }

    /**
     * Compare a finished [player] hand to a finished [dealer] hand.
     * Both hands must already be in their terminal state (player has
     * stood / busted / doubled-and-drawn; dealer has played out or
     * stayed pat for a player-bust short-circuit).
     *
     * Order of checks matters:
     *   - player bust always loses (dealer doesn't draw)
     *   - both blackjack pushes
     *   - player blackjack alone wins 3:2
     *   - dealer blackjack alone wins (player loses 1:1)
     *   - dealer bust → player wins 1:1
     *   - else compare totals
     *
     * [fromSplit] suppresses the natural-blackjack premium when the
     * player's hand was created by splitting a pair. Standard rules:
     * a 21 on a split hand pays 1:1, not 3:2 — even if the cards are
     * an Ace plus a ten-value (which would otherwise be a natural).
     */
    fun evaluate(player: List<Card>, dealer: List<Card>, fromSplit: Boolean = false): Result {
        if (isBust(player)) return Result.PLAYER_BUST
        val playerBJ = !fromSplit && isBlackjack(player)
        val dealerBJ = isBlackjack(dealer)
        if (playerBJ && dealerBJ) return Result.PUSH
        if (playerBJ) return Result.PLAYER_BLACKJACK
        if (dealerBJ) return Result.DEALER_WIN
        if (isBust(dealer)) return Result.PLAYER_WIN
        val pt = bestTotal(player)
        val dt = bestTotal(dealer)
        return when {
            pt > dt -> Result.PLAYER_WIN
            pt < dt -> Result.DEALER_WIN
            else -> Result.PUSH
        }
    }

    /**
     * Payout multiplier on the player's (possibly doubled) stake for
     * the given [result]. Same shape as [database.economy.Highlow] —
     * fed straight to [database.service.WagerHelper.applyMultiplier]
     * for solo settlement. [blackjackPayoutMult] overrides the natural
     * blackjack multiplier (default 2.5 i.e. 3:2; set to 2.2 for 6:5).
     */
    fun multiplier(result: Result, blackjackPayoutMult: Double = BLACKJACK_MULT): Double = when (result) {
        Result.PLAYER_BLACKJACK -> blackjackPayoutMult
        Result.PLAYER_WIN -> WIN_MULT
        Result.PUSH -> PUSH_MULT
        Result.DEALER_WIN, Result.PLAYER_BUST -> 0.0
    }

    companion object {
        const val MIN_STAKE: Long = 10L
        const val MAX_STAKE: Long = 500L

        /** Dealer hits until best total is at least this. S17 rule. */
        const val DEALER_STAND_VALUE: Int = 17

        const val BLACKJACK_MULT: Double = 2.5
        const val WIN_MULT: Double = 2.0
        const val PUSH_MULT: Double = 1.0

        const val MULTI_MIN_ANTE: Long = 10L
        const val MULTI_MAX_ANTE: Long = 500L
        const val MULTI_MIN_SEATS: Int = 2
        const val MULTI_MAX_SEATS: Int = 5

        /** Fraction of a multiplayer pot routed to the jackpot pool. */
        const val MULTI_RAKE: Double = 0.05

        /**
         * Maximum number of hand-slots a single seat can hold after
         * splitting. The classic blackjack cap is 4 (split → re-split
         * → re-split again, giving at most 4 parallel hands). Split-aces
         * specifically can't be re-split here either way; this is the
         * absolute ceiling enforced by the SPLIT pre-check.
         */
        const val MAX_SPLIT_HANDS: Int = 4

        /**
         * Per-actor decision deadline for multi tables. Auto-stands the
         * actor on their behalf when the clock fires. `0` disables the
         * clock entirely; the table only ever closes via the idle
         * sweeper.
         */
        const val MULTI_SHOT_CLOCK_SECONDS: Int = 30
    }
}
