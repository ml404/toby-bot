package bot.toby.helpers.charactersheet

import bot.toby.dto.web.dnd.AbilityStat
import bot.toby.dto.web.dnd.CharacterSheet
import bot.toby.helpers.charactersheet.CharacterSheetProvider.FetchResult
import database.persistence.CharacterSheetPersistence
import database.service.CharacterSheetService
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

class DndBeyondCharacterSheetProviderTest {

    private val freshJson = """{"id":1,"name":"Fresh","stats":[{"id":2,"value":14}],"baseHitPoints":10}"""
    private val fetchedSheet = CharacterSheet(
        id = 1L,
        name = "Fresh",
        stats = listOf(AbilityStat(2, 14)),
        baseHitPoints = 10,
        bonusHitPoints = null,
        removedHitPoints = 0,
        temporaryHitPoints = 0,
        race = null,
        classes = null,
    )

    @Test
    fun `fresh cache is returned without hitting the fetcher`() = runTest {
        val fetcher = mockk<DndBeyondCharacterFetcher>()
        val service = mockk<CharacterSheetService>()
        every { service.getCachedSheet(1L) } returns CharacterSheetPersistence.CachedSheet(
            freshJson, LocalDateTime.now().minusSeconds(30)
        )

        val provider = DndBeyondCharacterSheetProvider(fetcher, service, ttlSeconds = 3600)
        val sheet = provider.getCharacterSheet(1L)

        assertNotNull(sheet)
        assertEquals("Fresh", sheet?.name)
        coVerify(exactly = 0) { fetcher.fetch(any()) }
        verify(exactly = 0) { service.saveSheet(any(), any()) }
    }

    @Test
    fun `stale cache triggers fetch and persists result`() = runTest {
        val fetcher = mockk<DndBeyondCharacterFetcher>()
        val service = mockk<CharacterSheetService>()
        every { service.getCachedSheet(1L) } returns CharacterSheetPersistence.CachedSheet(
            freshJson, LocalDateTime.now().minusHours(2)
        )
        coEvery { fetcher.fetch(1L) } returns FetchResult.Success(fetchedSheet, freshJson)
        every { service.saveSheet(1L, freshJson) } just Runs

        val provider = DndBeyondCharacterSheetProvider(fetcher, service, ttlSeconds = 3600)
        val sheet = provider.getCharacterSheet(1L)

        assertEquals("Fresh", sheet?.name)
        coVerify { fetcher.fetch(1L) }
        verify { service.saveSheet(1L, freshJson) }
    }

    @Test
    fun `missing cache triggers fetch and persists result`() = runTest {
        val fetcher = mockk<DndBeyondCharacterFetcher>()
        val service = mockk<CharacterSheetService>()
        every { service.getCachedSheet(1L) } returns null
        coEvery { fetcher.fetch(1L) } returns FetchResult.Success(fetchedSheet, freshJson)
        every { service.saveSheet(1L, freshJson) } just Runs

        val provider = DndBeyondCharacterSheetProvider(fetcher, service, ttlSeconds = 3600)
        val sheet = provider.getCharacterSheet(1L)

        assertEquals("Fresh", sheet?.name)
        verify { service.saveSheet(1L, freshJson) }
    }

    @Test
    fun `fetch failure with stale cache returns stale sheet without overwriting`() = runTest {
        val fetcher = mockk<DndBeyondCharacterFetcher>()
        val service = mockk<CharacterSheetService>()
        every { service.getCachedSheet(1L) } returns CharacterSheetPersistence.CachedSheet(
            freshJson, LocalDateTime.now().minusDays(1)
        )
        coEvery { fetcher.fetch(1L) } returns FetchResult.Unavailable()

        val provider = DndBeyondCharacterSheetProvider(fetcher, service, ttlSeconds = 3600)
        val sheet = provider.getCharacterSheet(1L)

        assertEquals("Fresh", sheet?.name)
        verify(exactly = 0) { service.saveSheet(any(), any()) }
    }

    @Test
    fun `fetch failure with no cache returns null`() = runTest {
        val fetcher = mockk<DndBeyondCharacterFetcher>()
        val service = mockk<CharacterSheetService>()
        every { service.getCachedSheet(1L) } returns null
        coEvery { fetcher.fetch(1L) } returns FetchResult.Unavailable()

        val provider = DndBeyondCharacterSheetProvider(fetcher, service, ttlSeconds = 3600)
        assertNull(provider.getCharacterSheet(1L))
    }

    @Test
    fun `fetchCharacterSheet always hits fetcher and persists on success`() = runTest {
        val fetcher = mockk<DndBeyondCharacterFetcher>()
        val service = mockk<CharacterSheetService>(relaxed = true)
        coEvery { fetcher.fetch(1L) } returns FetchResult.Success(fetchedSheet, freshJson)

        val provider = DndBeyondCharacterSheetProvider(fetcher, service, ttlSeconds = 3600)
        val result = provider.fetchCharacterSheet(1L)

        assertEquals(FetchResult.Success(fetchedSheet, freshJson), result)
        coVerify { fetcher.fetch(1L) }
        verify { service.saveSheet(1L, freshJson) }
    }

    @Test
    fun `fetchCharacterSheet does not persist on forbidden`() = runTest {
        val fetcher = mockk<DndBeyondCharacterFetcher>()
        val service = mockk<CharacterSheetService>(relaxed = true)
        coEvery { fetcher.fetch(1L) } returns FetchResult.Forbidden

        val provider = DndBeyondCharacterSheetProvider(fetcher, service, ttlSeconds = 3600)
        val result = provider.fetchCharacterSheet(1L)

        assertEquals(FetchResult.Forbidden, result)
        verify(exactly = 0) { service.saveSheet(any(), any()) }
    }

    @Test
    fun `ttl of zero treats all caches as stale`() = runTest {
        val fetcher = mockk<DndBeyondCharacterFetcher>()
        val service = mockk<CharacterSheetService>(relaxed = true)
        every { service.getCachedSheet(1L) } returns CharacterSheetPersistence.CachedSheet(
            freshJson, LocalDateTime.now()
        )
        coEvery { fetcher.fetch(1L) } returns FetchResult.Success(fetchedSheet, freshJson)

        val provider = DndBeyondCharacterSheetProvider(fetcher, service, ttlSeconds = 0)
        provider.getCharacterSheet(1L)

        coVerify { fetcher.fetch(1L) }
    }
}
