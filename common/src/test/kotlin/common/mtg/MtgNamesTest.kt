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

    @Test
    fun `requestName reduces a full multi-faced name to its front face`() {
        assertEquals("Archangel Avacyn", MtgNames.requestName("Archangel Avacyn // Avacyn, the Purifier"))
        assertEquals("Fire", MtgNames.requestName("Fire // Ice"))
        assertEquals("Huntmaster of the Fells", MtgNames.requestName("Huntmaster of the Fells // Ravager of the Fells"))
    }

    @Test
    fun `requestName leaves single-faced and front-face names unchanged`() {
        assertEquals("Lightning Bolt", MtgNames.requestName("Lightning Bolt"))
        assertEquals("Archangel Avacyn", MtgNames.requestName("Archangel Avacyn"))
    }

    @Test
    fun `requestName trims surrounding and around-separator whitespace`() {
        assertEquals("Fire", MtgNames.requestName("  Fire // Ice  "))
        assertEquals("Commit", MtgNames.requestName("Commit//Memory"))
    }

    @Test
    fun `the front face requestName resolves back via matchKeys`() {
        // End-to-end of the fix: send the front face, match the full name back.
        val request = MtgNames.requestName("Archangel Avacyn // Avacyn, the Purifier")
        val fullNameKeys = MtgNames.matchKeys("Archangel Avacyn // Avacyn, the Purifier")
        assertTrue(fullNameKeys.contains(MtgNames.lookupKey(request)))
    }

    // --- index ----------------------------------------------------------

    @Test
    fun `index keys an item under its full name and every face`() {
        val dfc = "Huntmaster of the Fells // Ravager of the Fells"
        val byKey = MtgNames.index(listOf(dfc)) { it }

        // Resolvable by the pasted front face, the back face, or the full name.
        assertEquals(dfc, byKey[MtgNames.lookupKey("Huntmaster of the Fells")])
        assertEquals(dfc, byKey[MtgNames.lookupKey("Ravager of the Fells")])
        assertEquals(dfc, byKey[MtgNames.lookupKey(dfc)])
    }

    @Test
    fun `index lets the first item win a contested key`() {
        // Two printings of the same name: the first one indexed is kept.
        val byKey = MtgNames.index(listOf("Forest #1", "Forest #2")) { "Forest" }
        assertEquals("Forest #1", byKey[MtgNames.lookupKey("Forest")])
    }

    @Test
    fun `index maps over arbitrary item types via the name selector`() {
        data class Card(val name: String, val id: Int)
        val byKey = MtgNames.index(listOf(Card("Sol Ring", 7))) { it.name }
        assertEquals(7, byKey[MtgNames.lookupKey("sol ring")]?.id)
    }
}
