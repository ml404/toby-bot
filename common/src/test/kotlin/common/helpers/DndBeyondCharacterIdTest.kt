package common.helpers

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class DndBeyondCharacterIdTest {

    @Test
    fun `parses the character page URL`() {
        assertEquals(48690485L, parseDndBeyondCharacterId("https://www.dndbeyond.com/characters/48690485"))
    }

    @Test
    fun `parses a bare numeric ID`() {
        assertEquals(48690485L, parseDndBeyondCharacterId("48690485"))
    }

    @Test
    fun `parses the character-service API URL`() {
        assertEquals(
            48690485L,
            parseDndBeyondCharacterId("https://character-service.dndbeyond.com/character/v5/character/48690485")
        )
    }

    @Test
    fun `returns null when input has no digits`() {
        assertNull(parseDndBeyondCharacterId("not-a-valid-url"))
    }

    @Test
    fun `returns the trailing numeric segment when multiple are present`() {
        assertEquals(99L, parseDndBeyondCharacterId("abc123def/99"))
    }
}
