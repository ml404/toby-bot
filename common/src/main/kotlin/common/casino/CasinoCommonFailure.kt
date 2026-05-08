package common.casino

/**
 * Marker interface implemented by the four standard failure outcomes
 * shared across every casino-game service (Dice, Coinflip, Slots,
 * Scratch, Highlow, Baccarat, etc.).
 *
 * Each per-game `*Outcome` sealed hierarchy used to declare its own
 * `InsufficientCredits(stake, have)`, `InsufficientCoinsForTopUp(needed,
 * have)`, `InvalidStake(min, max)` and `UnknownUser` types — all with
 * identical fields and identical mapping into the controller's error
 * response. By having those nested types implement these interfaces, a
 * controller's `when` block can collapse four arms into one
 * `is CasinoCommonFailure -> errors.mapCommonFailure(outcome)`.
 *
 * The interfaces live in `common/` so `database/` services can declare
 * them on their outcome types and `web/` controllers can pattern-match
 * on them, without either side depending on the other.
 */
sealed interface CasinoCommonFailure {

    interface InsufficientCredits : CasinoCommonFailure {
        val stake: Long
        val have: Long
    }

    interface InsufficientCoinsForTopUp : CasinoCommonFailure {
        val needed: Long
        val have: Long
    }

    interface InvalidStake : CasinoCommonFailure {
        val min: Long
        val max: Long
    }

    interface UnknownUser : CasinoCommonFailure
}
