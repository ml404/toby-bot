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
        val known = setOf("streak", "level", "casino", "social", "music", "voice")
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
        listOf("streak_first", "streak_3", "streak_7", "streak_30").forEach { code ->
            assertNotNull(AchievementCatalog.byCode(code), "missing streak achievement '$code'")
        }
    }

    @Test
    fun `level milestone codes match AchievementEventHandler expectations`() {
        listOf("level_5", "level_25", "level_50").forEach { code ->
            assertNotNull(AchievementCatalog.byCode(code), "missing level achievement '$code'")
        }
    }

    @Test
    fun `tip and duel achievement codes match TipSentEvent and DuelResolvedEvent handlers`() {
        listOf("tip_giver", "first_duel_win", "duel_wins_10").forEach { code ->
            assertNotNull(AchievementCatalog.byCode(code), "missing achievement '$code'")
        }
    }

    /**
     * Locks in which catalog entries are still pending hookup. When you
     * wire one up (e.g. `blackjack_natural` becomes triggered by the
     * blackjack hand resolution path), flip `hidden = false` in
     * AchievementCatalog AND remove it from this list. The intent is
     * that "hidden" never silently grows — every hidden entry is a
     * promise to a contributor that finishing the hookup makes it
     * visible.
     */
    @Test
    fun `no achievements are hidden`() {
        // blackjack_natural was the last hidden entry; this PR wires it
        // through BlackjackService → BlackjackNaturalEvent →
        // AchievementEventHandler. Every catalog entry is now reachable.
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
        // these exact codes:
        //   streak    → streak_first + streak_{3,7,30}
        //   level     → level_{5,25,50}
        //   tip       → tip_giver
        //   duel      → first_duel_win, duel_wins_10
        //   lottery   → lottery_winner
        //   intro     → intro_set
        //   voice     → voice_10h, voice_100h
        //   blackjack → blackjack_natural
        val wired = setOf(
            "streak_first", "streak_3", "streak_7", "streak_30",
            "level_5", "level_25", "level_50",
            "tip_giver", "first_duel_win", "duel_wins_10",
            "lottery_winner", "intro_set",
            "voice_10h", "voice_100h",
            "blackjack_natural",
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
