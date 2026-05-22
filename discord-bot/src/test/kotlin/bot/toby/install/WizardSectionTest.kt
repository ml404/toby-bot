package bot.toby.install

import database.dto.ConfigDto.Configurations
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class WizardSectionTest {

    @Test
    fun `byId resolves every section`() {
        WizardSection.entries.forEach { section ->
            assertEquals(section, WizardSection.byId(section.id))
        }
    }

    @Test
    fun `byId returns null on unknown`() {
        assertNull(WizardSection.byId(""))
        assertNull(WizardSection.byId("not-a-section"))
    }

    @Test
    fun `gated sections are hidden when their opt-in is off`() {
        val reader: (Configurations) -> String? = { _ -> null }  // everything off
        val visible = WizardSection.visibleFor(reader)
        assertFalse(visible.contains(WizardSection.ACTIVITY))
        assertFalse(visible.contains(WizardSection.LOTTERY))
        // Non-gated sections are always visible.
        assertTrue(visible.contains(WizardSection.GENERAL))
        assertTrue(visible.contains(WizardSection.ECONOMY))
        assertTrue(visible.contains(WizardSection.POKER))
        assertTrue(visible.contains(WizardSection.BLACKJACK))
    }

    @Test
    fun `gated sections appear when their opt-in is true`() {
        val reader: (Configurations) -> String? = { key ->
            if (key == Configurations.ACTIVITY_TRACKING || key == Configurations.LOTTERY_DAILY_ENABLED) "true"
            else null
        }
        val visible = WizardSection.visibleFor(reader)
        assertTrue(visible.contains(WizardSection.ACTIVITY))
        assertTrue(visible.contains(WizardSection.LOTTERY))
        // All six sections are visible in this scenario.
        assertEquals(WizardSection.entries.size, visible.size)
    }

    @Test
    fun `gated sections treat any non-true value as off`() {
        val reader: (Configurations) -> String? = { _ -> "false" }
        val visible = WizardSection.visibleFor(reader)
        assertFalse(visible.contains(WizardSection.ACTIVITY))
        assertFalse(visible.contains(WizardSection.LOTTERY))
    }

    @Test
    fun `every section has at least one category`() {
        WizardSection.entries.forEach { section ->
            assertTrue(section.categories.isNotEmpty(), "${section.name} has no categories")
        }
    }

    @Test
    fun `activity section is gated by ACTIVITY_TRACKING`() {
        assertEquals(OptInFeatures.ACTIVITY_TRACKING, WizardSection.ACTIVITY.gate)
    }

    @Test
    fun `lottery section is gated by LOTTERY_DAILY`() {
        assertEquals(OptInFeatures.LOTTERY_DAILY, WizardSection.LOTTERY.gate)
    }

    @Test
    fun `non-gated sections have null gate`() {
        assertNull(WizardSection.GENERAL.gate)
        assertNull(WizardSection.ECONOMY.gate)
        assertNull(WizardSection.POKER.gate)
        assertNull(WizardSection.BLACKJACK.gate)
    }

    @Test
    fun `economy section contains per-game stakes`() {
        val tokens = WizardSection.ECONOMY.categories.map { it.token }
        assertTrue(tokens.contains("stakes"))
    }

    @Test
    fun `economy section exposes the jackpot quick-channels entry`() {
        val tokens = WizardSection.ECONOMY.categories.map { it.token }
        assertTrue(
            tokens.contains(JACKPOT_QUICK_CHANNELS_TOKEN),
            "jackpot_quick_channels missing from Economy section",
        )
    }

    @Test
    fun `lottery section exposes the lottery quick-channels entry`() {
        val tokens = WizardSection.LOTTERY.categories.map { it.token }
        assertTrue(
            tokens.contains(LOTTERY_QUICK_CHANNELS_TOKEN),
            "lottery_quick_channels missing from Lottery section",
        )
    }

    @Test
    fun `general section exposes the quick-channels entry`() {
        val tokens = WizardSection.GENERAL.categories.map { it.token }
        assertTrue(tokens.contains(QUICK_CHANNELS_TOKEN), "quick_channels missing from General section")
    }

    @Test
    fun `every section id is unique`() {
        val ids = WizardSection.entries.map { it.id }
        assertEquals(ids.size, ids.toSet().size, "section ids must be unique")
    }
}
