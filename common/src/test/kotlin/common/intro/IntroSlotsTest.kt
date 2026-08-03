package common.intro

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * The gap case is the whole point: `size + 1` picked an already-taken slot,
 * and because `createNewMusicFile` turns an id collision into an update, the
 * intro sitting in that slot was silently overwritten.
 */
class IntroSlotsTest {

    @Test
    fun `an empty set starts at slot one`() {
        assertEquals(1, IntroSlots.nextFreeIndex(emptyList()))
    }

    @Test
    fun `slots fill in order`() {
        assertEquals(2, IntroSlots.nextFreeIndex(listOf(1)))
        assertEquals(3, IntroSlots.nextFreeIndex(listOf(1, 2)))
    }

    @Test
    fun `a gap left by a delete is reused instead of colliding`() {
        // Deleting slot 2 of [1, 2, 3] leaves [1, 3]: size + 1 would say 3,
        // which already exists.
        assertEquals(2, IntroSlots.nextFreeIndex(listOf(1, 3)))
        assertEquals(1, IntroSlots.nextFreeIndex(listOf(2, 3)))
        assertEquals(1, IntroSlots.nextFreeIndex(listOf(3)))
        assertEquals(2, IntroSlots.nextFreeIndex(listOf(3, 1)))
    }

    @Test
    fun `a full set has no free slot`() {
        assertNull(IntroSlots.nextFreeIndex(listOf(1, 2, 3)))
        assertNull(IntroSlots.nextFreeIndex(listOf(3, 2, 1)))
    }

    @Test
    fun `null indexes are ignored rather than blocking a slot`() {
        assertEquals(1, IntroSlots.nextFreeIndex(listOf(null)))
        assertEquals(2, IntroSlots.nextFreeIndex(listOf(1, null)))
    }

    @Test
    fun `indexes outside the range never consume a slot`() {
        // Defensive: a stray index 4 (the old allocator could produce one)
        // shouldn't make 1..3 look full.
        assertEquals(1, IntroSlots.nextFreeIndex(listOf(4, 5)))
        assertEquals(2, IntroSlots.nextFreeIndex(listOf(1, 4)))
    }
}
