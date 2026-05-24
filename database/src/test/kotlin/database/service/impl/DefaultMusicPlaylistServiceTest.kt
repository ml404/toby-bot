package database.service.impl

import database.dto.MusicPlaylistDto
import database.persistence.music.MusicPlaylistPersistence
import database.service.music.MusicPlaylistService.PlaylistItemInput
import database.service.music.MusicPlaylistService.PlaylistNameTakenException
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import database.service.music.impl.DefaultMusicPlaylistService

class DefaultMusicPlaylistServiceTest {

    private val guildId = 100L
    private val ownerId = 200L

    private lateinit var persistence: MusicPlaylistPersistence
    private lateinit var service: DefaultMusicPlaylistService

    @BeforeEach
    fun setUp() {
        persistence = mockk(relaxed = true)
        service = DefaultMusicPlaylistService(persistence)
    }

    @Test
    fun `create rejects blank name`() {
        assertThrows(IllegalArgumentException::class.java) {
            service.create(guildId, ownerId, "   ", listOf(item("a")))
        }
    }

    @Test
    fun `create rejects empty item list`() {
        assertThrows(IllegalArgumentException::class.java) {
            service.create(guildId, ownerId, "Mix", emptyList())
        }
    }

    @Test
    fun `create rejects name longer than 80 chars`() {
        assertThrows(IllegalArgumentException::class.java) {
            service.create(guildId, ownerId, "x".repeat(81), listOf(item("a")))
        }
    }

    @Test
    fun `create throws PlaylistNameTakenException on duplicate`() {
        every { persistence.getByGuildOwnerAndName(guildId, ownerId, "Mix") } returns
            MusicPlaylistDto(id = 1L, guildId = guildId, ownerDiscordId = ownerId, name = "Mix")
        assertThrows(PlaylistNameTakenException::class.java) {
            service.create(guildId, ownerId, "Mix", listOf(item("a")))
        }
    }

    @Test
    fun `create trims name before persisting`() {
        every { persistence.getByGuildOwnerAndName(any(), any(), any()) } returns null
        val captured = slot<MusicPlaylistDto>()
        every { persistence.save(capture(captured)) } answers { captured.captured }
        service.create(guildId, ownerId, "  Mix  ", listOf(item("a")))
        assertEquals("Mix", captured.captured.name)
    }

    @Test
    fun `create assigns positions in input order`() {
        every { persistence.getByGuildOwnerAndName(any(), any(), any()) } returns null
        val captured = slot<MusicPlaylistDto>()
        every { persistence.save(capture(captured)) } answers { captured.captured }
        service.create(guildId, ownerId, "Mix", listOf(item("a"), item("b"), item("c")))
        val items = captured.captured.items
        assertEquals(3, items.size)
        assertEquals("a", items[0].identifier)
        assertEquals(0, items[0].position)
        assertEquals("c", items[2].identifier)
        assertEquals(2, items[2].position)
    }

    @Test
    fun `deleteById delegates to persistence`() {
        every { persistence.deleteById(7L) } just Runs
        service.deleteById(7L)
        verify(exactly = 1) { persistence.deleteById(7L) }
    }

    @Test
    fun `listForGuild delegates to persistence`() {
        val expected = listOf(MusicPlaylistDto(id = 1L, guildId = guildId, name = "x"))
        every { persistence.listByGuild(guildId) } returns expected
        assertSame(expected, service.listForGuild(guildId))
    }

    @Test
    fun `listForUserInGuild delegates to persistence`() {
        val expected = listOf(MusicPlaylistDto(id = 1L, guildId = guildId, ownerDiscordId = ownerId, name = "x"))
        every { persistence.listByGuildAndOwner(guildId, ownerId) } returns expected
        assertSame(expected, service.listForUserInGuild(guildId, ownerId))
    }

    @Test
    fun `getById delegates to persistence`() {
        val expected = MusicPlaylistDto(id = 1L, name = "x")
        every { persistence.getById(1L) } returns expected
        assertSame(expected, service.getById(1L))
    }

    private fun item(id: String) = PlaylistItemInput(
        identifier = id,
        title = "title-$id",
        author = "author-$id",
        durationMs = 60_000L,
        sourceName = "youtube",
    )
}
