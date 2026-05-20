package common.events

/**
 * Fact emitted by `PokerService` whenever a seat's revealed best-five
 * at showdown is a straight flush with ace-high (i.e. a royal flush).
 * Holding the hand is enough — winning the pot is not required, to
 * match the achievement copy "Hit a royal flush in poker".
 *
 * Subscribers (achievements) unlock the `poker_first_royal_flush`
 * catalog entry.
 */
data class PokerRoyalFlushEvent(
    val discordId: Long,
    val guildId: Long,
)
