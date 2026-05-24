package database.service.impl

import database.dto.ConfigDto
import database.persistence.guild.ConfigPersistence
import database.service.guild.ConfigService
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.transaction.annotation.Transactional
import database.service.guild.impl.DefaultConfigService

/**
 * Unit tests for [DefaultConfigService.upsertAll].
 *
 * Direct-instantiation tests bypass Spring's `@Transactional` /
 * `@CachePut` AOP — what we exercise here is the in-method delegation:
 * does the batch call `upsertConfig` for every row, in input order, and
 * does it return results that correctly distinguish `Created` from
 * `Updated`?
 *
 * The transactional commit-once semantics are an annotation contract;
 * a separate assertion verifies the method is annotated.
 */
class DefaultConfigServiceUpsertAllTest {

    private lateinit var persistence: ConfigPersistence
    private lateinit var service: DefaultConfigService

    @BeforeEach
    fun setUp() {
        persistence = mockk(relaxed = true)
        service = DefaultConfigService()
        // The persistence field is `@Autowired lateinit var configService`;
        // unit tests don't run Spring AOP so we inject it manually.
        DefaultConfigService::class.java.getDeclaredField("configService").apply {
            isAccessible = true
            set(service, persistence)
        }
    }

    @Test
    fun `empty input returns empty and writes nothing`() {
        val results = service.upsertAll("g1", emptyList())

        assertEquals(emptyList<ConfigService.UpsertResult>(), results)
        verify(exactly = 0) { persistence.createNewConfig(any()) }
        verify(exactly = 0) { persistence.updateConfig(any()) }
    }

    @Test
    fun `all-fresh rows yield Created results in input order`() {
        // No pre-existing rows.
        every { persistence.getConfigByName(any(), any()) } returns null
        every { persistence.createNewConfig(any()) } answers { firstArg() }

        val rows = listOf(
            "VOLUME" to "75",
            "DELETE_DELAY" to "15",
            "INTRO_VOLUME" to "50",
        )
        val results = service.upsertAll("g1", rows)

        assertEquals(3, results.size)
        results.forEachIndexed { i, result ->
            assertInstanceOf(ConfigService.UpsertResult.Created::class.java, result)
            assertEquals(rows[i].first, result.dto.name)
            assertEquals(rows[i].second, result.dto.value)
            assertEquals("g1", result.dto.guildId)
        }
        verify(exactly = 3) { persistence.createNewConfig(any()) }
        verify(exactly = 0) { persistence.updateConfig(any()) }
    }

    @Test
    fun `existing rows yield Updated with the right previousValue`() {
        every {
            persistence.getConfigByName("VOLUME", "g1")
        } returns ConfigDto("VOLUME", "100", "g1")
        every {
            persistence.getConfigByName("DELETE_DELAY", "g1")
        } returns ConfigDto("DELETE_DELAY", "30", "g1")
        every { persistence.updateConfig(any()) } answers { firstArg() }

        val rows = listOf(
            "VOLUME" to "75",
            "DELETE_DELAY" to "15",
        )
        val results = service.upsertAll("g1", rows)

        assertEquals(2, results.size)
        val first = results[0] as ConfigService.UpsertResult.Updated
        assertEquals("VOLUME", first.dto.name)
        assertEquals("75", first.dto.value)
        assertEquals("100", first.previousValue)

        val second = results[1] as ConfigService.UpsertResult.Updated
        assertEquals("DELETE_DELAY", second.dto.name)
        assertEquals("15", second.dto.value)
        assertEquals("30", second.previousValue)

        verify(exactly = 2) { persistence.updateConfig(any()) }
        verify(exactly = 0) { persistence.createNewConfig(any()) }
    }

    @Test
    fun `mixed fresh and existing rows interleave Created and Updated correctly`() {
        // VOLUME exists, INTRO_VOLUME is fresh, MOVE exists.
        every { persistence.getConfigByName("VOLUME", "g1") } returns ConfigDto("VOLUME", "50", "g1")
        every { persistence.getConfigByName("INTRO_VOLUME", "g1") } returns null
        every { persistence.getConfigByName("MOVE", "g1") } returns ConfigDto("MOVE", "Lobby", "g1")
        every { persistence.updateConfig(any()) } answers { firstArg() }
        every { persistence.createNewConfig(any()) } answers { firstArg() }

        val results = service.upsertAll(
            "g1",
            listOf(
                "VOLUME" to "75",
                "INTRO_VOLUME" to "60",
                "MOVE" to "General",
            ),
        )

        assertEquals(3, results.size)
        assertInstanceOf(ConfigService.UpsertResult.Updated::class.java, results[0])
        assertInstanceOf(ConfigService.UpsertResult.Created::class.java, results[1])
        assertInstanceOf(ConfigService.UpsertResult.Updated::class.java, results[2])
        assertEquals("50", (results[0] as ConfigService.UpsertResult.Updated).previousValue)
        assertEquals("Lobby", (results[2] as ConfigService.UpsertResult.Updated).previousValue)
    }

    @Test
    fun `existing row from a different guild is treated as fresh and creates`() {
        // Matches the same-guildId guard inside upsertConfig: a row that
        // happens to share the name but belongs to another guild does not
        // count as an existing row for this guild's upsert.
        every {
            persistence.getConfigByName("VOLUME", "g1")
        } returns ConfigDto("VOLUME", "200", "OTHER_GUILD")
        every { persistence.createNewConfig(any()) } answers { firstArg() }

        val results = service.upsertAll("g1", listOf("VOLUME" to "75"))

        assertEquals(1, results.size)
        assertInstanceOf(ConfigService.UpsertResult.Created::class.java, results[0])
        verify(exactly = 1) { persistence.createNewConfig(any()) }
        verify(exactly = 0) { persistence.updateConfig(any()) }
    }

    @Test
    fun `rows are processed in input order`() {
        every { persistence.getConfigByName(any(), any()) } returns null
        every { persistence.createNewConfig(any()) } answers { firstArg() }

        service.upsertAll(
            "g1",
            listOf(
                "A" to "1",
                "B" to "2",
                "C" to "3",
            ),
        )

        // Catches a reordering bug — input order must be preserved so
        // SetConfigCategoryModal's `zip` against outcome.pairs lines up.
        verifyOrder {
            persistence.createNewConfig(match { it.name == "A" })
            persistence.createNewConfig(match { it.name == "B" })
            persistence.createNewConfig(match { it.name == "C" })
        }
    }

    @Test
    fun `upsertAll is annotated Transactional`() {
        // Sanity check: if someone removes the @Transactional annotation,
        // the batch's "one commit" guarantee silently breaks. Catch that
        // at test time rather than in production.
        val method = DefaultConfigService::class.java.getDeclaredMethod(
            "upsertAll",
            String::class.java,
            List::class.java,
        )
        assertTrue(
            method.isAnnotationPresent(Transactional::class.java),
            "DefaultConfigService.upsertAll must be @Transactional so all rows commit together",
        )
    }

    @Test
    fun `non-empty input does not touch deleteAll or deleteConfig`() {
        every { persistence.getConfigByName(any(), any()) } returns null
        every { persistence.createNewConfig(any()) } answers { firstArg() }

        service.upsertAll("g1", listOf("X" to "y"))

        verify(exactly = 0) { persistence.deleteAll(any()) }
        verify(exactly = 0) { persistence.deleteConfig(any(), any()) }
    }
}
