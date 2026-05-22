package bot.toby.install

import database.dto.ConfigDto.Configurations
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class OptInFeaturesTest {

    @Test
    fun `byKeyName resolves the activity tracking key`() {
        val feature = OptInFeatures.byKeyName("ACTIVITY_TRACKING")
        assertNotNull(feature)
        assertEquals(OptInFeatures.ACTIVITY_TRACKING, feature)
    }

    @Test
    fun `byKeyName resolves the lottery daily key`() {
        val feature = OptInFeatures.byKeyName("LOTTERY_DAILY_ENABLED")
        assertNotNull(feature)
        assertEquals(OptInFeatures.LOTTERY_DAILY, feature)
    }

    @Test
    fun `byKeyName returns null for non-opt-in keys`() {
        assertNull(OptInFeatures.byKeyName("DEFAULT_VOLUME"))
        assertNull(OptInFeatures.byKeyName(""))
        assertNull(OptInFeatures.byKeyName("NOPE"))
    }

    @Test
    fun `every entry roundtrips through Configurations`() {
        // Catches a future rename in ConfigDto silently breaking a toggle.
        OptInFeatures.entries.forEach { feature ->
            val resolved = Configurations.valueOf(feature.key.name)
            assertEquals(feature.key, resolved, "entry ${feature.name} does not roundtrip")
        }
    }

    @Test
    fun `every entry has nonblank label and description`() {
        OptInFeatures.entries.forEach { feature ->
            assertTrue(feature.label.isNotBlank(), "${feature.name} label is blank")
            assertTrue(feature.description.isNotBlank(), "${feature.name} description is blank")
        }
    }
}
