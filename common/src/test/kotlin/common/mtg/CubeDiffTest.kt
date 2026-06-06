package common.mtg

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CubeDiffTest {

    private fun entries(vararg lines: String) = CardListParser.parse(lines.joinToString("\n"))

    @Test
    fun `reports added, removed and changed cards`() {
        val a = entries("Lightning Bolt", "Counterspell", "3 Forest")
        val b = entries("Lightning Bolt", "Shock", "5 Forest")
        val diff = CubeDiff.diff(a, b)

        assertEquals(listOf("Shock"), diff.added.map { it.name })
        assertEquals(0, diff.added.first().from)
        assertEquals(1, diff.added.first().to)

        assertEquals(listOf("Counterspell"), diff.removed.map { it.name })
        assertEquals(1, diff.removed.first().from)

        assertEquals(listOf("Forest"), diff.changed.map { it.name })
        assertEquals(3, diff.changed.first().from)
        assertEquals(5, diff.changed.first().to)
    }

    @Test
    fun `an unchanged card appears in none of the buckets`() {
        val diff = CubeDiff.diff(entries("Sol Ring"), entries("Sol Ring"))
        assertTrue(diff.added.isEmpty())
        assertTrue(diff.removed.isEmpty())
        assertTrue(diff.changed.isEmpty())
    }

    @Test
    fun `matching is case-insensitive and counts are summed per name`() {
        val a = entries("lightning bolt", "2 Forest", "3 Forest") // 5 Forest total
        val b = entries("Lightning Bolt", "5 forest")
        val diff = CubeDiff.diff(a, b)
        assertTrue(diff.added.isEmpty())
        assertTrue(diff.removed.isEmpty())
        assertTrue(diff.changed.isEmpty()) // bolt unchanged, forest 5 == 5
    }

    @Test
    fun `reports the total size of each side`() {
        val diff = CubeDiff.diff(entries("Bolt", "10 Forest"), entries("Bolt"))
        assertEquals(11, diff.sizeA)
        assertEquals(1, diff.sizeB)
    }

    @Test
    fun `buckets are alphabetised`() {
        val b = entries("Shock", "Ancestral Recall", "Mox Pearl")
        val diff = CubeDiff.diff(emptyList(), b)
        assertEquals(listOf("Ancestral Recall", "Mox Pearl", "Shock"), diff.added.map { it.name })
    }

    @Test
    fun `diffing against an empty list reports everything as added or removed`() {
        assertEquals(2, CubeDiff.diff(emptyList(), entries("A", "B")).added.size)
        assertEquals(2, CubeDiff.diff(entries("A", "B"), emptyList()).removed.size)
    }
}
