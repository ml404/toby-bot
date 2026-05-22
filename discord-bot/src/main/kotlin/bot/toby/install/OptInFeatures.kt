package bot.toby.install

import database.dto.ConfigDto.Configurations

/**
 * Single source of truth for the feature toggles surfaced in the install
 * wizard's "Optional features" view. Adding a new opt-in elsewhere in the
 * config schema is a one-line change here — the toggle row, embed, and
 * tests iterate this enum.
 */
enum class OptInFeatures(
    val key: Configurations,
    val label: String,
    val description: String,
) {
    ACTIVITY_TRACKING(
        Configurations.ACTIVITY_TRACKING,
        "Activity tracking",
        "Track per-user message activity for XP, leaderboards, and jackpot eligibility.",
    ),
    LOTTERY_DAILY(
        Configurations.LOTTERY_DAILY_ENABLED,
        "Daily lottery",
        "Auto-draw Pick-5-of-49 lottery at 00:00 UTC, funded by jackpot pool + tickets.",
    ),
    ;

    companion object {
        fun byKeyName(name: String): OptInFeatures? = entries.find { it.key.name == name }
    }
}
