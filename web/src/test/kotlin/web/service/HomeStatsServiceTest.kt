package web.service

import core.command.Command
import core.managers.CommandManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.utils.cache.SnowflakeCacheView
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class HomeStatsServiceTest {

    private lateinit var jda: JDA
    private lateinit var commandManager: CommandManager
    private lateinit var service: HomeStatsService

    @BeforeEach
    fun setup() {
        jda = mockk(relaxed = true)
        commandManager = mockk(relaxed = true)
        @Suppress("UNCHECKED_CAST")
        val guildCache = mockk<SnowflakeCacheView<Guild>>(relaxed = true)
        every { jda.guildCache } returns guildCache
        every { guildCache.size() } returns 12L
        // Pre-bake category sizes so the sum is deterministic.
        every { commandManager.musicCommands } returns commandList(17)
        every { commandManager.dndCommands } returns commandList(3)
        every { commandManager.moderationCommands } returns commandList(18)
        every { commandManager.economyCommands } returns commandList(6)
        every { commandManager.gameCommands } returns commandList(34)
        every { commandManager.miscCommands } returns commandList(17)
        every { commandManager.fetchCommands } returns commandList(3)
        service = HomeStatsService(jda, commandManager)
    }

    private fun commandList(n: Int): List<Command> =
        (1..n).map { mockk(relaxed = true) }

    @Test
    fun `serverCount reads from JDA guild cache`() {
        assertEquals(12, service.get().serverCount)
    }

    @Test
    fun `memberCount sums the member count of every cached guild`() {
        @Suppress("UNCHECKED_CAST")
        val guildCache = mockk<SnowflakeCacheView<Guild>>(relaxed = true)
        every { jda.guildCache } returns guildCache
        every { guildCache.size() } returns 3L
        every { guildCache.iterator() } returns mutableListOf(
            mockk<Guild> { every { memberCount } returns 1000 },
            mockk<Guild> { every { memberCount } returns 250 },
            mockk<Guild> { every { memberCount } returns 7 },
        ).iterator()
        service = HomeStatsService(jda, commandManager)

        assertEquals(1257L, service.get().memberCount)
    }

    @Test
    fun `commandCount sums every category in CommandManager`() {
        // 17 + 3 + 18 + 6 + 34 + 17 + 3 = 98
        assertEquals(98, service.get().commandCount)
    }

    @Test
    fun `subsequent get calls return the same memoised instance and never recompute (60s TTL)`() {
        // First call computes.
        val first = service.get()
        // Bump the JDA cache and command counts in case the service does a fresh read.
        @Suppress("UNCHECKED_CAST")
        val guildCache = mockk<SnowflakeCacheView<Guild>>(relaxed = true)
        every { jda.guildCache } returns guildCache
        every { guildCache.size() } returns 999L
        every { commandManager.musicCommands } returns commandList(999)

        // Second call within the TTL window must return the cached instance,
        // not the new computation.
        val second = service.get()

        assertSame(first, second)
        // Verify the new sources were not consulted on the cached path.
        verify(exactly = 1) { jda.guildCache }      // only the initial compute()
        verify(exactly = 1) { commandManager.musicCommands }
    }
}
