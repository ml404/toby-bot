package database.service.social

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate

class StreakStatusTest {

    private val today: LocalDate = LocalDate.of(2026, 11, 25)

    @Test
    fun `never claimed is NEW`() {
        assertEquals(StreakStatus.NEW, StreakStatus.of(null, today))
    }

    @Test
    fun `claimed today is CLAIMED`() {
        assertEquals(StreakStatus.CLAIMED, StreakStatus.of(today, today))
    }

    @Test
    fun `claimed yesterday is AT_RISK`() {
        assertEquals(StreakStatus.AT_RISK, StreakStatus.of(today.minusDays(1), today))
        assertEquals(today.minusDays(1), StreakStatus.atRiskClaimDate(today))
    }

    @Test
    fun `claimed two or more days ago is LAPSED`() {
        assertEquals(StreakStatus.LAPSED, StreakStatus.of(today.minusDays(2), today))
        assertEquals(StreakStatus.LAPSED, StreakStatus.of(today.minusDays(30), today))
    }

    @Test
    fun `only CLAIMED and AT_RISK are active`() {
        assertTrue(StreakStatus.CLAIMED.isActive)
        assertTrue(StreakStatus.AT_RISK.isActive)
        assertFalse(StreakStatus.LAPSED.isActive)
        assertFalse(StreakStatus.NEW.isActive)
    }
}
