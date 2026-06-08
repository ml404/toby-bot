package database.service.impl

import database.dto.music.MusicPlaylistDto
import database.dto.music.MusicPlaylistItemDto
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
import org.junit.jupiter.api.Assertions.assertNull
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

    // ---- Curation: empty create + item mutation + rename --------------

    @Test
    fun `createEmpty persists a playlist with no items`() {
        every { persistence.getByGuildOwnerAndName(any(), any(), any()) } returns null
        val captured = slot<MusicPlaylistDto>()
        every { persistence.save(capture(captured)) } answers { captured.captured }
        service.createEmpty(guildId, ownerId, "Empty")
        assertEquals("Empty", captured.captured.name)
        assertEquals(0, captured.captured.items.size)
    }

    @Test
    fun `createEmpty rejects blank name`() {
        assertThrows(IllegalArgumentException::class.java) {
            service.createEmpty(guildId, ownerId, "   ")
        }
    }

    @Test
    fun `createEmpty throws PlaylistNameTakenException on duplicate`() {
        every { persistence.getByGuildOwnerAndName(guildId, ownerId, "Mix") } returns
            MusicPlaylistDto(id = 1L, guildId = guildId, ownerDiscordId = ownerId, name = "Mix")
        assertThrows(PlaylistNameTakenException::class.java) {
            service.createEmpty(guildId, ownerId, "Mix")
        }
    }

    @Test
    fun `addItem appends at the next position`() {
        val playlist = playlistWith("a")
        every { persistence.getById(5L) } returns playlist
        val captured = slot<MusicPlaylistDto>()
        every { persistence.update(capture(captured)) } answers { captured.captured }
        service.addItem(5L, item("b"))
        val items = captured.captured.items.sortedBy { it.position }
        assertEquals(2, items.size)
        assertEquals("b", items[1].identifier)
        assertEquals(1, items[1].position)
    }

    @Test
    fun `addItem returns null when the playlist is missing`() {
        every { persistence.getById(5L) } returns null
        assertNull(service.addItem(5L, item("b")))
    }

    @Test
    fun `removeItem drops the item and re-sequences positions`() {
        val playlist = playlistWith("a", "b", "c") // item ids 1,2,3
        every { persistence.getById(5L) } returns playlist
        val captured = slot<MusicPlaylistDto>()
        every { persistence.update(capture(captured)) } answers { captured.captured }
        service.removeItem(5L, 2L)
        val items = captured.captured.items.sortedBy { it.position }
        assertEquals(listOf("a", "c"), items.map { it.identifier })
        assertEquals(listOf(0, 1), items.map { it.position })
    }

    @Test
    fun `removeItem returns null when the item is absent`() {
        every { persistence.getById(5L) } returns playlistWith("a")
        assertNull(service.removeItem(5L, 999L))
    }

    @Test
    fun `reorderItems moves an item and re-sequences positions`() {
        val playlist = playlistWith("a", "b", "c")
        every { persistence.getById(5L) } returns playlist
        val captured = slot<MusicPlaylistDto>()
        every { persistence.update(capture(captured)) } answers { captured.captured }
        service.reorderItems(5L, 0, 2) // a -> end
        val items = captured.captured.items.sortedBy { it.position }
        assertEquals(listOf("b", "c", "a"), items.map { it.identifier })
        assertEquals(listOf(0, 1, 2), items.map { it.position })
    }

    @Test
    fun `reorderItems returns null for an out-of-range index`() {
        every { persistence.getById(5L) } returns playlistWith("a", "b")
        assertNull(service.reorderItems(5L, 0, 9))
    }

    @Test
    fun `rename throws when another playlist already has the name`() {
        every { persistence.getById(5L) } returns
            MusicPlaylistDto(id = 5L, guildId = guildId, ownerDiscordId = ownerId, name = "Old")
        every { persistence.getByGuildOwnerAndName(guildId, ownerId, "New") } returns
            MusicPlaylistDto(id = 9L, guildId = guildId, ownerDiscordId = ownerId, name = "New")
        assertThrows(PlaylistNameTakenException::class.java) {
            service.rename(5L, "New")
        }
    }

    @Test
    fun `rename succeeds when the only name clash is the playlist itself`() {
        val playlist = MusicPlaylistDto(id = 5L, guildId = guildId, ownerDiscordId = ownerId, name = "Mix")
        every { persistence.getById(5L) } returns playlist
        every { persistence.getByGuildOwnerAndName(guildId, ownerId, "Mix") } returns playlist
        val captured = slot<MusicPlaylistDto>()
        every { persistence.update(capture(captured)) } answers { captured.captured }
        service.rename(5L, "Mix")
        assertEquals("Mix", captured.captured.name)
    }

    @Test
    fun `rename returns null when the playlist is missing`() {
        every { persistence.getById(5L) } returns null
        assertNull(service.rename(5L, "New"))
    }

    private fun playlistWith(vararg identifiers: String): MusicPlaylistDto {
        val playlist = MusicPlaylistDto(id = 5L, guildId = guildId, ownerDiscordId = ownerId, name = "Mix")
        identifiers.forEachIndexed { index, id ->
            playlist.items.add(
                MusicPlaylistItemDto(
                    id = (index + 1).toLong(),
                    playlist = playlist,
                    position = index,
                    identifier = id,
                ),
            )
        }
        return playlist
    }

    private fun item(id: String) = PlaylistItemInput(
        identifier = id,
        title = "title-$id",
        author = "author-$id",
        durationMs = 60_000L,
        sourceName = "youtube",
    )
}
