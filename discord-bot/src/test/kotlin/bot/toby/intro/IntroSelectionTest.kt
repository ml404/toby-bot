package bot.toby.intro

import database.dto.music.MusicDto
import database.dto.user.UserDto
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

class IntroSelectionTest {

    private val userDto = UserDto(discordId = 1234L, guildId = 4567L)

    private fun intro(index: Int) = MusicDto(userDto, index, "intro$index", 90, byteArrayOf(index.toByte()))

    @Test
    fun `no intros means nothing to play`() {
        assertNull(IntroSelection.pick(emptyList()))
    }

    @Test
    fun `a single intro is always picked, even if it just played`() {
        val only = intro(1)
        assertEquals(only, IntroSelection.pick(listOf(only)))
        assertEquals(only, IntroSelection.pick(listOf(only), lastPlayedId = only.id))
    }

    @Test
    fun `the previous pick is never repeated back to back`() {
        val intros = listOf(intro(1), intro(2), intro(3))
        val last = intros[1]

        // Every seed must avoid it — this is the whole point of the change.
        repeat(200) { seed ->
            val picked = IntroSelection.pick(intros, last.id, Random(seed))
            assertNotEquals(last.id, picked?.id, "seed $seed repeated the previous intro")
        }
    }

    @Test
    fun `with no history any intro can come up`() {
        val intros = listOf(intro(1), intro(2), intro(3))
        val seen = (0 until 200).mapNotNull { IntroSelection.pick(intros, null, Random(it))?.id }.toSet()
        assertEquals(intros.mapNotNull { it.id }.toSet(), seen)
    }

    @Test
    fun `both remaining intros stay reachable after excluding the previous one`() {
        val intros = listOf(intro(1), intro(2), intro(3))
        val seen = (0 until 200).mapNotNull { IntroSelection.pick(intros, intros[0].id, Random(it))?.id }.toSet()
        assertEquals(setOf(intros[1].id, intros[2].id), seen)
    }

    @Test
    fun `two intros alternate`() {
        val intros = listOf(intro(1), intro(2))
        assertEquals(intros[1].id, IntroSelection.pick(intros, intros[0].id)?.id)
        assertEquals(intros[0].id, IntroSelection.pick(intros, intros[1].id)?.id)
    }

    @Test
    fun `an unknown last-played id excludes nothing`() {
        val intros = listOf(intro(1), intro(2))
        val seen = (0 until 100).mapNotNull { IntroSelection.pick(intros, "not-an-id", Random(it))?.id }.toSet()
        assertEquals(intros.mapNotNull { it.id }.toSet(), seen)
    }

    @Test
    fun `duplicate ids fall back to the full set rather than playing nothing`() {
        // Defensive: shouldn't happen (ids are unique per slot), but excluding
        // every candidate must not silence the intro.
        val dup = intro(1)
        val intros = listOf(dup, dup)
        assertTrue(IntroSelection.pick(intros, dup.id) != null)
    }
}
