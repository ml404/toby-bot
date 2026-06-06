package common.mtg

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MtgGlossaryTest {

    @Test
    fun `lookup matches a keyword case-insensitively`() {
        assertEquals("Trample", MtgGlossary.lookup("trample")?.keyword)
        assertEquals("Trample", MtgGlossary.lookup("TRAMPLE")?.keyword)
        assertEquals("Flying", MtgGlossary.lookup("  Flying  ")?.keyword)
    }

    @Test
    fun `lookup falls back to a prefix match`() {
        assertEquals("Deathtouch", MtgGlossary.lookup("death")?.keyword)
        assertEquals("Double strike", MtgGlossary.lookup("double")?.keyword)
    }

    @Test
    fun `lookup returns null for unknown or blank queries`() {
        assertNull(MtgGlossary.lookup("zzz"))
        assertNull(MtgGlossary.lookup(""))
        assertNull(MtgGlossary.lookup("   "))
    }

    @Test
    fun `every term has a keyword and non-blank reminder text`() {
        val all = MtgGlossary.all()
        assertTrue(all.size >= 20, "expected a decent glossary, got ${all.size}")
        all.forEach { term ->
            assertTrue(term.keyword.isNotBlank(), "blank keyword")
            assertTrue(term.text.isNotBlank(), "blank text for ${term.keyword}")
        }
        // No duplicate keywords (the by-key map would silently collapse them).
        assertEquals(all.size, all.map { it.keyword.lowercase() }.toSet().size)
    }
}
