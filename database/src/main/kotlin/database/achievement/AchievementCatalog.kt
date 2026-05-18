package database.achievement

/**
 * Static catalogue of every achievement the bot can grant. Seeded into
 * the `achievement` table on startup by [AchievementSeeder] — adding a
 * new achievement is a one-line edit here, no migration needed.
 *
 * [code] is the stable referent the engine fires against; the engine
 * looks up the matching row by code and unlocks / progresses against it.
 * Code conventions:
 *   - streak achievements end in the streak threshold (`streak_3`, `streak_7`).
 *   - level achievements end in the level (`level_5`).
 *   - count achievements end in the count when ambiguous (`duel_wins_10`).
 *   - "first" / one-shot achievements use a descriptive suffix (`first_duel_win`).
 *
 * [threshold] is the cumulative count that triggers the unlock for
 * counter-based achievements. For one-shot achievements (claim once, win
 * once, set once) leave [threshold] at the default 1 — calling
 * `AchievementService.unlock(code)` skips the progress table.
 */
data class AchievementSpec(
    val code: String,
    val name: String,
    val description: String,
    val category: String,
    val icon: String? = null,
    val xpReward: Int = 0,
    val creditReward: Long = 0,
    val threshold: Long = 1,
    val hidden: Boolean = false
)

object AchievementCatalog {

    /**
     * Source of truth for every achievement the bot ships. Entries marked
     * `hidden = true` are *pending hookup* — they exist as catalog rows
     * (so the seeder doesn't delete them when a hookup lands later) but
     * `AchievementService.listFor` filters them out of the user-facing
     * view until the user actually owns them. Flip `hidden = false`
     * the moment the relevant trigger calls `progress(...)`/`unlock(...)`.
     */
    val all: List<AchievementSpec> = listOf(
        // Streak achievements — fired by AchievementEventHandler on StreakClaimedEvent.
        AchievementSpec(
            code = "streak_first",
            name = "Daily Habit",
            description = "Claim your first daily reward.",
            category = "streak",
            icon = "🌱",
            xpReward = 25,
            creditReward = 50
        ),
        AchievementSpec(
            code = "streak_3",
            name = "Three in a Row",
            description = "Hit a 3-day login streak.",
            category = "streak",
            icon = "🔥",
            xpReward = 50,
            creditReward = 100,
            threshold = 3
        ),
        AchievementSpec(
            code = "streak_7",
            name = "Week Strong",
            description = "Hit a 7-day login streak.",
            category = "streak",
            icon = "📅",
            xpReward = 150,
            creditReward = 300,
            threshold = 7
        ),
        AchievementSpec(
            code = "streak_30",
            name = "Month Locked In",
            description = "Hit a 30-day login streak.",
            category = "streak",
            icon = "💎",
            xpReward = 500,
            creditReward = 1000,
            threshold = 30
        ),

        // Level achievements — fired by AchievementEventHandler on LevelUpEvent.
        AchievementSpec(
            code = "level_5",
            name = "Getting Started",
            description = "Reach level 5.",
            category = "level",
            icon = "🥉",
            xpReward = 50,
            creditReward = 50,
            threshold = 5
        ),
        AchievementSpec(
            code = "level_25",
            name = "Veteran",
            description = "Reach level 25.",
            category = "level",
            icon = "🥈",
            xpReward = 200,
            creditReward = 250,
            threshold = 25
        ),
        AchievementSpec(
            code = "level_50",
            name = "Hardcore",
            description = "Reach level 50.",
            category = "level",
            icon = "🥇",
            xpReward = 500,
            creditReward = 750,
            threshold = 50
        ),

        // Casino / social achievements — fired by inline calls from
        // DuelService / TipService / IntroHelper resolution paths.
        AchievementSpec(
            code = "first_duel_win",
            name = "First Blood",
            description = "Win your first duel.",
            category = "casino",
            icon = "⚔️",
            xpReward = 50,
            creditReward = 50
        ),
        AchievementSpec(
            code = "duel_wins_10",
            name = "Gladiator",
            description = "Win 10 duels.",
            category = "casino",
            icon = "🏆",
            xpReward = 150,
            creditReward = 200,
            threshold = 10
        ),
        AchievementSpec(
            code = "tip_giver",
            name = "Generous",
            description = "Tip another user for the first time.",
            category = "social",
            icon = "🎁",
            xpReward = 25,
            creditReward = 50
        ),
        AchievementSpec(
            code = "intro_set",
            name = "Make an Entrance",
            description = "Set your first voice-channel intro song.",
            category = "music",
            icon = "🎵",
            xpReward = 50,
            creditReward = 50
        ),
        AchievementSpec(
            code = "lottery_winner",
            name = "Jackpot",
            description = "Win a daily lottery draw.",
            category = "casino",
            icon = "🎰",
            xpReward = 100,
            creditReward = 0
        ),
        AchievementSpec(
            code = "voice_10h",
            name = "Voice Regular",
            description = "Spend 10 cumulative hours in voice channels.",
            category = "voice",
            icon = "🎙️",
            xpReward = 100,
            creditReward = 100,
            threshold = 36000L
        ),
        AchievementSpec(
            code = "voice_100h",
            name = "Voice Veteran",
            description = "Spend 100 cumulative hours in voice channels.",
            category = "voice",
            icon = "📻",
            xpReward = 500,
            creditReward = 500,
            threshold = 360000L
        ),

        // ---- Pending hookup. Hidden so they don't show up in
        // `/achievements` until the trigger path is wired in a
        // follow-up. Seeder still upserts the row so the id stays
        // stable when the hookup lands.
        AchievementSpec(
            code = "blackjack_natural",
            name = "21!",
            description = "Get a natural blackjack on the deal.",
            category = "casino",
            icon = "🃏",
            xpReward = 50,
            creditReward = 50,
            hidden = true
        )
    )

    fun byCode(code: String): AchievementSpec? = all.firstOrNull { it.code == code }
}
