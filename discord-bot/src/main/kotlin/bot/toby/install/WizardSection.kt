package bot.toby.install

import bot.toby.command.commands.moderation.SetConfigCommand

/** Synthetic category token for the General section's Quick channels modal (`InstallQuickChannelsModal`). */
const val QUICK_CHANNELS_TOKEN = "quick_channels"

/** Synthetic category token for the Jackpot section's Quick channels modal (`InstallJackpotChannelsModal`). */
const val JACKPOT_QUICK_CHANNELS_TOKEN = "jackpot_quick_channels"

/** Synthetic category token for the Lottery section's Quick channels modal (`InstallLotteryChannelsModal`). */
const val LOTTERY_QUICK_CHANNELS_TOKEN = "lottery_quick_channels"

/**
 * Groups the 12 setconfig categories into themed sections so the custom
 * install flow is two-clicks-to-modal instead of a 13-deep dropdown.
 *
 * `gate` (if non-null) hides the entire section from the section picker
 * when the gated opt-in is off — so e.g. with the daily lottery disabled,
 * the "Lottery" section disappears, and tuning lottery-only configs
 * doesn't dilute the menu.
 */
enum class WizardSection(
    val id: String,
    val label: String,
    val description: String,
    val gate: OptInFeatures?,
    val categories: List<SectionCategory>,
) {
    GENERAL(
        id = "general",
        label = "General settings",
        description = "Audio defaults, delete delay, move + leaderboard channels",
        gate = null,
        categories = listOf(
            SectionCategory(QUICK_CHANNELS_TOKEN, "Quick channels", "Pick your voice + text channels from a list (no IDs)"),
            SectionCategory(SetConfigCommand.SUB_GENERAL, "All general settings", "Audio + auto-delete + channels (typed values)"),
        ),
    ),
    ECONOMY(
        id = "economy",
        label = "Economy & casino",
        description = "Fees, jackpot, per-game stake bounds",
        gate = null,
        categories = listOf(
            SectionCategory(SetConfigCommand.SUB_FEES, "Fees", "Loss tribute %, jackpot win %, Toby Coin trade fees"),
            SectionCategory(SetConfigCommand.SUB_JACKPOT, "Jackpot", "Stake anchor, cooldown, RTP gate, modlog channel"),
            SectionCategory(JACKPOT_QUICK_CHANNELS_TOKEN, "Jackpot modlog channel", "Pick the casino modlog channel from a list (no IDs)"),
            SectionCategory(SetConfigCommand.SUB_STAKES, "Per-game stakes", "Min/max stake bounds (apply to all or drill down)"),
        ),
    ),
    ACTIVITY(
        id = "activity",
        label = "Activity tracking",
        description = "XP, leaderboards, jackpot eligibility",
        gate = OptInFeatures.ACTIVITY_TRACKING,
        categories = listOf(
            SectionCategory(SetConfigCommand.SUB_ACTIVITY, "Activity config", "UBI, daily credit cap, XP bonus, tracking toggle"),
            SectionCategory(SetConfigCommand.SUB_JACKPOT_ACTIVITY, "Jackpot eligibility", "Activity-day window"),
        ),
    ),
    POKER(
        id = "poker",
        label = "Poker",
        description = "Stakes, table, shot clock",
        gate = null,
        categories = listOf(
            SectionCategory(SetConfigCommand.SUB_POKER_STAKES, "Poker stakes", "Blinds, bets, rake"),
            SectionCategory(SetConfigCommand.SUB_POKER_TABLE, "Poker table", "Buy-ins, seats, shot clock"),
        ),
    ),
    BLACKJACK(
        id = "blackjack",
        label = "Blackjack",
        description = "Rules, payouts, seats",
        gate = null,
        categories = listOf(
            SectionCategory(SetConfigCommand.SUB_BLACKJACK_RULES, "Blackjack rules", "Rake, ante, dealer rule, shot clock"),
            SectionCategory(SetConfigCommand.SUB_BLACKJACK_TABLE, "Blackjack table", "Seats + natural payout ratio"),
        ),
    ),
    LOTTERY(
        id = "lottery",
        label = "Daily lottery",
        description = "Ticket pricing, mode, pools, announce channel",
        gate = OptInFeatures.LOTTERY_DAILY,
        categories = listOf(
            SectionCategory(SetConfigCommand.SUB_LOTTERY_BASICS, "Lottery basics", "On/off, ticket price, mode, ping"),
            SectionCategory(SetConfigCommand.SUB_LOTTERY_POOLS, "Lottery pools", "Seed/revenue split + announce channel"),
            SectionCategory(LOTTERY_QUICK_CHANNELS_TOKEN, "Lottery announce channel", "Pick the announce channel from a list (no IDs)"),
        ),
    ),
    ;

    companion object {
        fun byId(id: String): WizardSection? = entries.find { it.id == id }

        /**
         * Sections to show given the current opt-in state. Gated sections
         * fall out when their toggle is off (`"true"` is the on-signal;
         * anything else is off).
         */
        fun visibleFor(currentValues: ConfigReader): List<WizardSection> =
            entries.filter { section ->
                val gate = section.gate ?: return@filter true
                currentValues(gate.key) == "true"
            }
    }
}

data class SectionCategory(
    val token: String,
    val label: String,
    val description: String,
)
