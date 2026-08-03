package bot.toby.intro

import database.dto.music.MusicDto
import database.dto.user.UserDto
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class IntroUndoStoreTest {

    private lateinit var store: IntroUndoStore

    private val userDto = UserDto(discordId = 1234L, guildId = 4567L)

    @BeforeEach
    fun setUp() {
        store = IntroUndoStore()
    }

    private fun intro(index: Int = 2, name: String = "track") =
        MusicDto(userDto, index, name, 40, "https://example.com/a".toByteArray(), 3_000, 9_000)

    @Test
    fun `a remembered intro can be taken back`() {
        store.remember(intro())

        val snapshot = store.take(4567L, 1234L)

        assertNotNull(snapshot)
        assertEquals(2, snapshot!!.index)
        assertEquals("track", snapshot.fileName)
        assertEquals(40, snapshot.introVolume)
        assertEquals(3_000, snapshot.startMs)
        assertEquals(9_000, snapshot.endMs)
        assertTrue(snapshot.musicBlob.contentEquals("https://example.com/a".toByteArray()))
    }

    @Test
    fun `undo is single use`() {
        store.remember(intro())

        assertNotNull(store.take(4567L, 1234L))
        assertNull(store.take(4567L, 1234L))
    }

    @Test
    fun `snapshots are scoped to guild and user`() {
        store.remember(intro())

        assertNull(store.take(9999L, 1234L))
        assertNull(store.take(4567L, 9999L))
        assertNotNull(store.take(4567L, 1234L))
    }

    @Test
    fun `a later delete replaces the pending snapshot`() {
        store.remember(intro(index = 1, name = "first"))
        store.remember(intro(index = 2, name = "second"))

        assertEquals("second", store.take(4567L, 1234L)?.fileName)
    }

    @Test
    fun `an intro with no owner is ignored rather than throwing`() {
        store.remember(MusicDto(id = "orphan", userDto = null))

        assertNull(store.take(4567L, 1234L))
    }

    @Test
    fun `the snapshot copies the blob so later mutation cannot corrupt it`() {
        val original = intro()
        store.remember(original)
        original.musicBlob!![0] = 0

        val snapshot = store.take(4567L, 1234L)!!
        assertEquals('h'.code.toByte(), snapshot.musicBlob!![0])
    }

    @Test
    fun `nothing to take when nothing was deleted`() {
        assertNull(store.take(4567L, 1234L))
    }
}
