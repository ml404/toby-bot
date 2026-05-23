package database.achievement

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AchievementCatalogTest {

    @Test
    fun `every catalog entry has a unique code`() {
        val codes = AchievementCatalog.all.map { it.code }
        val duplicates = codes.groupingBy { it }.eachCount().filter { it.value > 1 }.keys
        assertTrue(duplicates.isEmpty(), "duplicate achievement codes: $duplicates")
        assertEquals(AchievementCatalog.all.size, codes.toSet().size)
    }

    @Test
    fun `byCode resolves every spec and returns null for unknown codes`() {
        AchievementCatalog.all.forEach { spec ->
            assertEquals(spec, AchievementCatalog.byCode(spec.code))
        }
        assertNull(AchievementCatalog.byCode("not_a_real_achievement"))
    }

    @Test
    fun `every catalog entry uses a known category`() {
        val known = setOf("streak", "level", "casino", "social", "music", "voice", "consolation")
        val unknown = AchievementCatalog.all.filterNot { it.category in known }
        assertTrue(unknown.isEmpty(), "achievements with unknown category: ${unknown.map { it.code to it.category }}")
    }

    @Test
    fun `thresholds are positive and rewards are non-negative`() {
        AchievementCatalog.all.forEach { spec ->
            assertTrue(spec.threshold > 0, "${spec.code} threshold must be > 0; got ${spec.threshold}")
            assertTrue(spec.xpReward >= 0, "${spec.code} xpReward must be >= 0; got ${spec.xpReward}")
            assertTrue(spec.creditReward >= 0, "${spec.code} creditReward must be >= 0; got ${spec.creditReward}")
        }
    }

    @Test
    fun `every entry has a non-blank name and description`() {
        AchievementCatalog.all.forEach { spec ->
            assertTrue(spec.name.isNotBlank(), "${spec.code} name is blank")
            assertTrue(spec.description.isNotBlank(), "${spec.code} description is blank")
        }
    }

    @Test
    fun `streak milestone codes match AchievementEventHandler expectations`() {
        // AchievementEventHandler maps streak counts to codes `streak_$count`.
        // If the catalog ships a milestone, the handler must be able to find
        // it — otherwise the unlock call silently no-ops in
        // DefaultAchievementService.unlock.
        listOf(
            "streak_first", "streak_3", "streak_7", "streak_30",
            "streak_100", "streak_365",
        ).forEach { code ->
            assertNotNull(AchievementCatalog.byCode(code), "missing streak achievement '$code'")
        }
    }

    @Test
    fun `level milestone codes match AchievementEventHandler expectations`() {
        listOf("level_5", "level_25", "level_50", "level_75", "level_100").forEach { code ->
            assertNotNull(AchievementCatalog.byCode(code), "missing level achievement '$code'")
        }
    }

    @Test
    fun `tip and duel achievement codes match TipSentEvent and DuelResolvedEvent handlers`() {
        listOf(
            "tip_giver", "tips_sent_10", "tips_sent_50",
            "first_duel_win", "duel_wins_10", "duel_wins_25", "duel_wins_50", "duel_wins_100",
            "duel_losses_5", "duel_losses_25",
            "first_rps_win", "rps_wins_10", "rps_wins_25", "rps_losses_5",
            "first_tictactoe_win", "tictactoe_wins_10", "tictactoe_wins_25", "tictactoe_losses_5",
        ).forEach { code ->
            assertNotNull(AchievementCatalog.byCode(code), "missing achievement '$code'")
        }
    }

    /**
     * Locks in which catalog entries are still pending hookup. Every
     * stub is now wired (PR follow-up to #520), so this set must be
     * empty. When a new hidden entry is added, document it in this
     * test's [PENDING_HIDDEN_CODES] set and revive the comparison.
     */
    @Test
    fun `no achievements are hidden`() {
        val actuallyHidden = AchievementCatalog.all.filter { it.hidden }.map { it.code }.toSet()
        assertEquals(
            emptySet<String>(), actuallyHidden,
            "Hidden achievements set is non-empty. Either wire up the trigger " +
                "and flip hidden=false, or document the new pending-hookup code in this test."
        )
    }

    @Test
    fun `every visible achievement is reachable via a wired event handler`() {
        // The handlers in AchievementEventHandler unlock/progress by
        // these exact codes — keep this set in lockstep with the
        // companion-object tier lists in AchievementEventHandler.
        val wired = setOf(
            "streak_first", "streak_3", "streak_7", "streak_30", "streak_100", "streak_365",
            "level_5", "level_25", "level_50", "level_75", "level_100",
            "tip_giver", "tips_sent_10", "tips_sent_50",
            "first_duel_win", "duel_wins_10", "duel_wins_25", "duel_wins_50", "duel_wins_100",
            "duel_losses_5", "duel_losses_25",
            "first_rps_win", "rps_wins_10", "rps_wins_25", "rps_losses_5",
            "first_tictactoe_win", "tictactoe_wins_10", "tictactoe_wins_25", "tictactoe_losses_5",
            "lottery_winner", "lottery_wins_3", "lottery_wins_10", "lottery_wins_25",
            "intro_set",
            "voice_10h", "voice_100h", "voice_250h", "voice_500h", "voice_1000h",
            "blackjack_natural", "blackjack_natural_5", "blackjack_natural_25",
            "slots_first_jackpot",
            "roulette_first_straight_win",
            "poker_first_royal_flush",
            "dice_first_win",
            "coinflip_first_win",
            "keno_first_perfect",
            "plinko_first_jackpot",
            "scratch_first_jackpot",
            "wheel_first_jackpot",
            "horse_racing_first_win",
            "baccarat_first_win",
            "casino_holdem_first_win",
            "highlow_first_streak",
        )
        val visible = AchievementCatalog.all.filterNot { it.hidden }.map { it.code }.toSet()
        val orphaned = visible - wired
        assertTrue(
            orphaned.isEmpty(),
            "Visible achievements with no event-handler hookup: $orphaned. " +
                "Either wire them up in AchievementEventHandler, mark them hidden, " +
                "or add them to the wired allowlist in this test."
        )
    }
}
