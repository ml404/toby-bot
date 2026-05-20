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
        AchievementSpec(
            code = "streak_100",
            name = "Centurion",
            description = "Hit a 100-day login streak.",
            category = "streak",
            icon = "🏛️",
            xpReward = 1500,
            creditReward = 3000,
            threshold = 100
        ),
        AchievementSpec(
            code = "streak_365",
            name = "Year of Toby",
            description = "Hit a 365-day login streak.",
            category = "streak",
            icon = "👑",
            xpReward = 5000,
            creditReward = 10000,
            threshold = 365
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
        AchievementSpec(
            code = "level_75",
            name = "Elite",
            description = "Reach level 75.",
            category = "level",
            icon = "🏅",
            xpReward = 1000,
            creditReward = 1500,
            threshold = 75
        ),
        AchievementSpec(
            code = "level_100",
            name = "Legend",
            description = "Reach level 100.",
            category = "level",
            icon = "🌟",
            xpReward = 2500,
            creditReward = 5000,
            threshold = 100
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
            code = "duel_wins_25",
            name = "Champion",
            description = "Win 25 duels.",
            category = "casino",
            icon = "🛡️",
            xpReward = 300,
            creditReward = 400,
            threshold = 25
        ),
        AchievementSpec(
            code = "duel_wins_50",
            name = "Warlord",
            description = "Win 50 duels.",
            category = "casino",
            icon = "⚜️",
            xpReward = 600,
            creditReward = 800,
            threshold = 50
        ),
        AchievementSpec(
            code = "duel_wins_100",
            name = "Undefeated",
            description = "Win 100 duels.",
            category = "casino",
            icon = "💀",
            xpReward = 1500,
            creditReward = 2000,
            threshold = 100
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
            code = "tips_sent_10",
            name = "Patron",
            description = "Tip another user 10 times.",
            category = "social",
            icon = "💝",
            xpReward = 150,
            creditReward = 150,
            threshold = 10
        ),
        AchievementSpec(
            code = "tips_sent_50",
            name = "Philanthropist",
            description = "Tip another user 50 times.",
            category = "social",
            icon = "🪙",
            xpReward = 500,
            creditReward = 500,
            threshold = 50
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
            code = "lottery_wins_3",
            name = "Lucky Streak",
            description = "Win 3 lifetime daily lottery draws.",
            category = "casino",
            icon = "🍀",
            xpReward = 200,
            creditReward = 0,
            threshold = 3
        ),
        AchievementSpec(
            code = "lottery_wins_10",
            name = "Fortune's Favourite",
            description = "Win 10 lifetime daily lottery draws.",
            category = "casino",
            icon = "🎟️",
            xpReward = 500,
            creditReward = 0,
            threshold = 10
        ),
        AchievementSpec(
            code = "lottery_wins_25",
            name = "Lottomaniac",
            description = "Win 25 lifetime daily lottery draws.",
            category = "casino",
            icon = "💰",
            xpReward = 1500,
            creditReward = 0,
            threshold = 25
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
        AchievementSpec(
            code = "voice_250h",
            name = "Voice Devotee",
            description = "Spend 250 cumulative hours in voice channels.",
            category = "voice",
            icon = "🎚️",
            xpReward = 1000,
            creditReward = 1000,
            threshold = 900000L
        ),
        AchievementSpec(
            code = "voice_500h",
            name = "Voice Legend",
            description = "Spend 500 cumulative hours in voice channels.",
            category = "voice",
            icon = "📡",
            xpReward = 2000,
            creditReward = 2000,
            threshold = 1800000L
        ),
        AchievementSpec(
            code = "voice_1000h",
            name = "Voice Immortal",
            description = "Spend 1000 cumulative hours in voice channels.",
            category = "voice",
            icon = "🛰️",
            xpReward = 5000,
            creditReward = 5000,
            threshold = 3600000L
        ),

        AchievementSpec(
            code = "blackjack_natural",
            name = "21!",
            description = "Get a natural blackjack on the deal.",
            category = "casino",
            icon = "🃏",
            xpReward = 50,
            creditReward = 50
        ),
        AchievementSpec(
            code = "blackjack_natural_5",
            name = "Card Counter",
            description = "Hit 5 lifetime natural blackjacks.",
            category = "casino",
            icon = "🂡",
            xpReward = 200,
            creditReward = 200,
            threshold = 5
        ),
        AchievementSpec(
            code = "blackjack_natural_25",
            name = "Pit Boss",
            description = "Hit 25 lifetime natural blackjacks.",
            category = "casino",
            icon = "🎴",
            xpReward = 750,
            creditReward = 750,
            threshold = 25
        ),

        // Consolation achievements — fired by AchievementEventHandler on
        // DuelResolvedEvent against the loser side.
        AchievementSpec(
            code = "duel_losses_5",
            name = "Tough Luck",
            description = "Lose 5 duels.",
            category = "consolation",
            icon = "🩹",
            xpReward = 50,
            creditReward = 100,
            threshold = 5
        ),
        AchievementSpec(
            code = "duel_losses_25",
            name = "Comeback Kid",
            description = "Lose 25 duels.",
            category = "consolation",
            icon = "💪",
            xpReward = 250,
            creditReward = 500,
            threshold = 25
        ),

        // Roadmap stubs for casino games that don't yet publish domain
        // events. Hidden until a future PR adds the corresponding event
        // and wires up an AchievementEventHandler listener; the codes
        // are reserved here so the hookup PR doesn't have to invent
        // them. Each pending entry must stay enumerated in
        // AchievementCatalogTest.PENDING_HIDDEN_CODES.
        AchievementSpec(
            code = "slots_first_jackpot",
            name = "Jackpot!",
            description = "Hit the slot machine jackpot.",
            category = "casino",
            icon = "🎰",
            xpReward = 100,
            creditReward = 100,
            hidden = true
        ),
        AchievementSpec(
            code = "roulette_first_straight_win",
            name = "Lucky Number",
            description = "Win a straight-up roulette bet.",
            category = "casino",
            icon = "🎯",
            xpReward = 100,
            creditReward = 100,
            hidden = true
        ),
        AchievementSpec(
            code = "poker_first_royal_flush",
            name = "Royal Flush",
            description = "Hit a royal flush in poker.",
            category = "casino",
            icon = "♠️",
            xpReward = 100,
            creditReward = 100,
            hidden = true
        ),
        AchievementSpec(
            code = "dice_first_win",
            name = "Loaded Dice",
            description = "Win your first dice game.",
            category = "casino",
            icon = "🎲",
            xpReward = 100,
            creditReward = 100,
            hidden = true
        ),
        AchievementSpec(
            code = "coinflip_first_win",
            name = "Heads or Tails",
            description = "Win your first coinflip.",
            category = "casino",
            icon = "🪙",
            xpReward = 100,
            creditReward = 100,
            hidden = true
        ),
        AchievementSpec(
            code = "keno_first_perfect",
            name = "Perfect Card",
            description = "Hit every chosen number in keno.",
            category = "casino",
            icon = "🔢",
            xpReward = 100,
            creditReward = 100,
            hidden = true
        ),
        AchievementSpec(
            code = "plinko_first_jackpot",
            name = "The Drop",
            description = "Land a plinko jackpot.",
            category = "casino",
            icon = "🟢",
            xpReward = 100,
            creditReward = 100,
            hidden = true
        ),
        AchievementSpec(
            code = "scratch_first_jackpot",
            name = "Hit the Strip",
            description = "Hit the jackpot on a scratch ticket.",
            category = "casino",
            icon = "🎫",
            xpReward = 100,
            creditReward = 100,
            hidden = true
        ),
        AchievementSpec(
            code = "wheel_first_jackpot",
            name = "Spin Doctor",
            description = "Land the wheel-of-fortune jackpot.",
            category = "casino",
            icon = "🎡",
            xpReward = 100,
            creditReward = 100,
            hidden = true
        ),
        AchievementSpec(
            code = "horse_racing_first_win",
            name = "Photo Finish",
            description = "Win your first horse race.",
            category = "casino",
            icon = "🐎",
            xpReward = 100,
            creditReward = 100,
            hidden = true
        ),
        AchievementSpec(
            code = "baccarat_first_win",
            name = "Punto Banco",
            description = "Win your first baccarat hand.",
            category = "casino",
            icon = "🎴",
            xpReward = 100,
            creditReward = 100,
            hidden = true
        ),
        AchievementSpec(
            code = "casino_holdem_first_win",
            name = "All In",
            description = "Win your first Casino Hold'em hand.",
            category = "casino",
            icon = "🂠",
            xpReward = 100,
            creditReward = 100,
            hidden = true
        ),
        AchievementSpec(
            code = "highlow_first_streak",
            name = "Higher or Lower",
            description = "Win 5 high-low calls in a row.",
            category = "casino",
            icon = "🔼",
            xpReward = 100,
            creditReward = 100,
            hidden = true
        )
    )

    fun byCode(code: String): AchievementSpec? = all.firstOrNull { it.code == code }
}
