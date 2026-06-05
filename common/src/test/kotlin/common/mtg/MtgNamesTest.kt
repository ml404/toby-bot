package common.mtg

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MtgNamesTest {

    @Test
    fun `a single-faced card matches only by its full name`() {
        assertEquals(listOf("lightning bolt"), MtgNames.matchKeys("Lightning Bolt"))
    }

    @Test
    fun `a double-faced card matches by its full name and each face`() {
        assertEquals(
            listOf(
                "huntmaster of the fells // ravager of the fells",
                "huntmaster of the fells",
                "ravager of the fells",
            ),
            MtgNames.matchKeys("Huntmaster of the Fells // Ravager of the Fells"),
        )
    }

    @Test
    fun `keys are trimmed and lower-cased and de-duplicated`() {
        // Same text on both faces collapses to two keys (full + the one face).
        assertEquals(listOf("a // a", "a"), MtgNames.matchKeys("  A // A  "))
    }

    @Test
    fun `lookupKey trims and lower-cases a user-entered name`() {
        assertEquals("huntmaster of the fells", MtgNames.lookupKey("  Huntmaster of the Fells "))
    }

    @Test
    fun `a front-face entry resolves against a fetched full name`() {
        // Mirrors how the web/Discord resolvers match a pasted face name.
        val byKey = MtgNames.matchKeys("Huntmaster of the Fells // Ravager of the Fells").associateWith { it }
        assertEquals(true, byKey.containsKey(MtgNames.lookupKey("Huntmaster of the Fells")))
    }

    @Test
    fun `the back face also resolves against the full name`() {
        val keys = MtgNames.matchKeys("Archangel Avacyn // Avacyn, the Purifier")
        assertTrue(keys.contains(MtgNames.lookupKey("Avacyn, the Purifier")))
        assertTrue(keys.contains(MtgNames.lookupKey("Archangel Avacyn")))
        assertTrue(keys.contains(MtgNames.lookupKey("Archangel Avacyn // Avacyn, the Purifier")))
    }

    @Test
    fun `split cards expose both halves`() {
        // e.g. Fire // Ice — paste either half.
        val keys = MtgNames.matchKeys("Fire // Ice")
        assertEquals(listOf("fire // ice", "fire", "ice"), keys)
    }

    @Test
    fun `adventure cards expose the creature and the adventure name`() {
        val keys = MtgNames.matchKeys("Brazen Borrower // Petty Theft")
        assertTrue(keys.contains("brazen borrower"))
        assertTrue(keys.contains("petty theft"))
    }

    @Test
    fun `a face name with commas and apostrophes is preserved`() {
        val keys = MtgNames.matchKeys("Jace, Vryn's Prodigy // Jace, Telepath Unbound")
        assertTrue(keys.contains("jace, vryn's prodigy"))
        assertTrue(keys.contains("jace, telepath unbound"))
    }

    @Test
    fun `a single-faced name containing no separator yields exactly one key`() {
        assertEquals(1, MtgNames.matchKeys("Sol Ring").size)
    }

    @Test
    fun `blank or whitespace names yield no keys`() {
        assertTrue(MtgNames.matchKeys("").isEmpty())
        assertTrue(MtgNames.matchKeys("   ").isEmpty())
    }

    @Test
    fun `empty faces around a separator are dropped`() {
        // Defensive: a stray "Name //" shouldn't add a blank key.
        val keys = MtgNames.matchKeys("Name //")
        assertEquals(listOf("name //", "name"), keys)
    }

    @Test
    fun `lookupKey is the inverse used for matching`() {
        val keys = MtgNames.matchKeys("Wear // Tear")
        assertTrue(keys.contains(MtgNames.lookupKey("  WEAR  ")))
        assertTrue(keys.contains(MtgNames.lookupKey("tear")))
    }
}
