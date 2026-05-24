package common.events

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import common.events.lottery.LotteryDrawnForTicketHolderEvent

class LotteryDrawnForTicketHolderEventTest {

    @Test
    fun `equals and hashCode reflect value semantics`() {
        val a = LotteryDrawnForTicketHolderEvent(1L, 2L, true, 100L)
        val b = LotteryDrawnForTicketHolderEvent(1L, 2L, true, 100L)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `events differing in any field are not equal`() {
        val base = LotteryDrawnForTicketHolderEvent(1L, 2L, true, 100L)
        assertNotEquals(base, base.copy(discordId = 999L))
        assertNotEquals(base, base.copy(guildId = 999L))
        assertNotEquals(base, base.copy(didWin = false))
        assertNotEquals(base, base.copy(amountWon = 999L))
    }

    @Test
    fun `copy can flip didWin while keeping other fields intact`() {
        val win = LotteryDrawnForTicketHolderEvent(1L, 2L, true, 100L)
        val loss = win.copy(didWin = false, amountWon = 0L)
        assertEquals(1L, loss.discordId)
        assertEquals(2L, loss.guildId)
        assertFalse(loss.didWin)
        assertEquals(0L, loss.amountWon)
    }

    @Test
    fun `toString includes every field for diagnostics`() {
        val str = LotteryDrawnForTicketHolderEvent(1L, 2L, true, 100L).toString()
        assertTrue(str.contains("discordId=1"))
        assertTrue(str.contains("guildId=2"))
        assertTrue(str.contains("didWin=true"))
        assertTrue(str.contains("amountWon=100"))
    }
}
