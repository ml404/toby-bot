package common.intro

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Allocation reads the **ids**, because the id is what a new row collides
 * with — `createNewMusicFile` looks it up, and before this it turned a hit
 * into an update that destroyed whatever was already there.
 *
 * Two things fooled the old allocators. `size + 1` picked an occupied slot the
 * moment a delete left a gap. Walking the range fixed that but still read the
 * `index` column, which the web drag-reorder rewrote without rewriting the id —
 * so index and id disagreed and the allocator handed out a slot that was
 * already in use.
 */
class IntroSlotsTest {

    /** Ids as the database stores them: `guildId_discordId_slot`. */
    private fun ids(vararg slots: Int) = slots.map { "42_7_$it" }

    // --- slotFromId ---------------------------------------------------------

    @Test
    fun `the slot is read off the end of the id`() {
        assertEquals(1, IntroSlots.slotFromId("42_7_1"))
        assertEquals(3, IntroSlots.slotFromId("999999999999_888888888888_3"))
    }

    @Test
    fun `anything that isn't one of our ids has no slot`() {
        assertNull(IntroSlots.slotFromId(null))
        assertNull(IntroSlots.slotFromId(""))
        assertNull(IntroSlots.slotFromId("42_7_nope"))
        assertNull(IntroSlots.slotFromId("42_7_0"))
        assertNull(IntroSlots.slotFromId("42_7_${IntroSlots.MAX_INTRO_COUNT + 1}"))
    }

    // --- nextFreeSlot -------------------------------------------------------

    @Test
    fun `an empty set starts at slot one`() {
        assertEquals(1, IntroSlots.nextFreeSlot(emptyList()))
    }

    @Test
    fun `slots fill in order`() {
        assertEquals(2, IntroSlots.nextFreeSlot(ids(1)))
        assertEquals(3, IntroSlots.nextFreeSlot(ids(1, 2)))
    }

    @Test
    fun `a gap left by a delete is reused instead of colliding`() {
        assertEquals(2, IntroSlots.nextFreeSlot(ids(1, 3)))
        assertEquals(1, IntroSlots.nextFreeSlot(ids(2, 3)))
        assertEquals(1, IntroSlots.nextFreeSlot(ids(3)))
        assertEquals(2, IntroSlots.nextFreeSlot(ids(3, 1)))
    }

    @Test
    fun `a full set has no free slot`() {
        assertNull(IntroSlots.nextFreeSlot(ids(1, 2, 3)))
        assertNull(IntroSlots.nextFreeSlot(ids(3, 2, 1)))
    }

    @Test
    fun `an unreadable id is ignored rather than blocking a slot`() {
        assertEquals(1, IntroSlots.nextFreeSlot(listOf(null)))
        assertEquals(2, IntroSlots.nextFreeSlot(listOf("42_7_1", null, "garbage")))
    }

    @Test
    fun `a reordered user is allocated a slot that is genuinely free`() {
        // The reported bug. After a drag-reorder the rows held ids [_1, _3]
        // with indexes [2, 1]; reading the index column said slot 3 was free,
        // and the save landed on the row already living at `_3`. Reading the
        // ids says 2, which is the empty one.
        assertEquals(2, IntroSlots.nextFreeSlot(listOf("42_7_1", "42_7_3")))
    }

    @Test
    fun `a user holding fewer than the cap always has somewhere to put one`() {
        // The corollary of allocating on ids: occupancy can never exceed the
        // number of rows, so nobody is told they're full while holding two.
        // Reading indexes *could* overstate it — ids [_1, _3] carrying indexes
        // [2, 1] made all three slots look taken by two rows.
        listOf(ids(1, 2), ids(1, 3), ids(2, 3), ids(1), ids(2), ids(3)).forEach { held ->
            assertNotNull(IntroSlots.nextFreeSlot(held), "no free slot for $held")
        }
    }
}
