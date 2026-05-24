package bot.toby.command.commands.game

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Wire-format tests for [PvpButtonIdCodec]. The button-id format is a
 * public contract — un-clicked Discord messages may sit for days before
 * the click arrives, so any change here must keep old encodings parsing
 * correctly.
 */
class PvpButtonIdCodecTest {

    @Test
    fun `encode joins parts with colons in fixed order`() {
        val id = PvpButtonIdCodec.encode("rps", "ACCEPT", sessionId = 42L, payload = 999L)
        assertEquals("rps:ACCEPT:42:999", id)
    }

    @Test
    fun `parse round-trips the encode output`() {
        val encoded = PvpButtonIdCodec.encode("tictactoe", "PLACE_3", 7L, 3L)
        val raw = PvpButtonIdCodec.parse(encoded, "tictactoe")
        assertEquals("PLACE_3", raw?.actionName)
        assertEquals(7L, raw?.sessionId)
        assertEquals(3L, raw?.payload)
    }

    @Test
    fun `parse accepts negative payload (forfeit and zero-sentinel actions)`() {
        val raw = PvpButtonIdCodec.parse("rps:FORFEIT:5:0", "rps")
        assertEquals("FORFEIT", raw?.actionName)
        assertEquals(5L, raw?.sessionId)
        assertEquals(0L, raw?.payload)
    }

    @Test
    fun `parse is case-insensitive on the prefix`() {
        val raw = PvpButtonIdCodec.parse("RPS:ACCEPT:1:2", "rps")
        assertEquals("ACCEPT", raw?.actionName)
    }

    @Test
    fun `parse returns null when the prefix doesn't match`() {
        assertNull(PvpButtonIdCodec.parse("connect4:DROP_0:1:0", "rps"))
    }

    @Test
    fun `parse returns null when the segment count is wrong`() {
        assertNull(PvpButtonIdCodec.parse("rps:ACCEPT:1", "rps"))
        assertNull(PvpButtonIdCodec.parse("rps:ACCEPT:1:0:extra", "rps"))
    }

    @Test
    fun `parse returns null when sessionId or payload is not numeric`() {
        assertNull(PvpButtonIdCodec.parse("rps:ACCEPT:not-a-number:0", "rps"))
        assertNull(PvpButtonIdCodec.parse("rps:ACCEPT:1:nope", "rps"))
    }

    @Test
    fun `parse leaves Action validation to the caller (stage-2)`() {
        // The codec doesn't know the per-game Action enum; an unknown
        // actionName parses successfully here and only fails when the
        // caller does Action.valueOf(raw.actionName).
        val raw = PvpButtonIdCodec.parse("rps:NEVER_HEARD_OF_IT:1:0", "rps")
        assertEquals("NEVER_HEARD_OF_IT", raw?.actionName)
    }
}
