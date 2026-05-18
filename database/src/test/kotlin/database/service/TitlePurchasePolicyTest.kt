package database.service

import common.leveling.LevelCurve
import database.dto.TitleDto
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class TitlePurchasePolicyTest {

    private fun title(requiredLevel: Int) =
        TitleDto(id = 1L, label = "x", cost = 100L, requiredLevel = requiredLevel)

    @Test
    fun `requiredLevel 0 is always Ok regardless of xp`() {
        val t = title(requiredLevel = 0)
        assertSame(TitlePurchasePolicy.Result.Ok, TitlePurchasePolicy.check(t, 0L))
        assertSame(TitlePurchasePolicy.Result.Ok, TitlePurchasePolicy.check(t, 1_000_000L))
        assertSame(TitlePurchasePolicy.Result.Ok, TitlePurchasePolicy.check(t, -42L))
    }

    @Test
    fun `negative requiredLevel is treated as no gate`() {
        // The schema enforces a CHECK >= 0 at the moderation layer, but the
        // policy itself should still be defensive — anything <= 0 means
        // ungated, so an unexpected negative value shouldn't lock everyone out.
        val t = title(requiredLevel = -5)
        assertSame(TitlePurchasePolicy.Result.Ok, TitlePurchasePolicy.check(t, 0L))
    }

    @Test
    fun `actor below required returns LevelLocked with both fields populated`() {
        val t = title(requiredLevel = 10)
        val result = TitlePurchasePolicy.check(t, 0L)
        val locked = assertInstanceOf(TitlePurchasePolicy.Result.LevelLocked::class.java, result)
        assertEquals(10, locked.required)
        assertEquals(0, locked.actor)
    }

    @Test
    fun `actor exactly at required level is Ok`() {
        val t = title(requiredLevel = 5)
        // cumulative XP to reach level 5 = 1150
        val xp = LevelCurve.cumulativeXpForLevel(5)
        assertSame(TitlePurchasePolicy.Result.Ok, TitlePurchasePolicy.check(t, xp))
    }

    @Test
    fun `actor one level above required is Ok`() {
        val t = title(requiredLevel = 5)
        val xp = LevelCurve.cumulativeXpForLevel(6)
        assertSame(TitlePurchasePolicy.Result.Ok, TitlePurchasePolicy.check(t, xp))
    }

    @Test
    fun `actor one xp below threshold is LevelLocked`() {
        val t = title(requiredLevel = 5)
        // Reaching level 5 takes 1150 XP — 1149 leaves the actor at level 4.
        val xp = LevelCurve.cumulativeXpForLevel(5) - 1L
        val result = TitlePurchasePolicy.check(t, xp)
        val locked = result as TitlePurchasePolicy.Result.LevelLocked
        assertEquals(5, locked.required)
        assertEquals(4, locked.actor)
    }

    @Test
    fun `negative xp clamps to level 0`() {
        val t = title(requiredLevel = 3)
        val locked = TitlePurchasePolicy.check(t, -1_000L) as TitlePurchasePolicy.Result.LevelLocked
        assertEquals(0, locked.actor)
        assertEquals(3, locked.required)
    }

    @Test
    fun `high requiredLevel surfaces the correct actor level at very high xp`() {
        val t = title(requiredLevel = 200)
        val xp = LevelCurve.cumulativeXpForLevel(100)
        val locked = TitlePurchasePolicy.check(t, xp) as TitlePurchasePolicy.Result.LevelLocked
        assertEquals(200, locked.required)
        assertEquals(100, locked.actor)
    }
}
