package web.util

/**
 * Returns this value if it is strictly positive, otherwise null.
 *
 * Casino response builders use this to elide zero-valued payout fields
 * from the JSON body — `jackpotPayout = outcome.jackpotPayout.positiveOrNull()`
 * reads as a single concept where `takeIf { it > 0L }` is structural
 * boilerplate repeated ~30× across the per-game controllers.
 */
fun Long.positiveOrNull(): Long? = takeIf { it > 0L }
