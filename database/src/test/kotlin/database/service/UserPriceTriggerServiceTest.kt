package database.service

import database.dto.UserPriceTriggerDto
import database.persistence.UserPriceTriggerPersistence
import database.service.impl.DefaultUserPriceTriggerService
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant

class UserPriceTriggerServiceTest {

    private val guildId = 100L
    private val discordId = 42L
    private lateinit var persistence: UserPriceTriggerPersistence
    private lateinit var service: DefaultUserPriceTriggerService

    @BeforeEach
    fun setUp() {
        persistence = mockk(relaxed = true)
        service = DefaultUserPriceTriggerService(persistence)
    }

    private fun row(
        id: Long,
        threshold: Double,
        priceAtCreation: Double,
        side: UserPriceTriggerDto.Side = UserPriceTriggerDto.Side.BUY,
        amount: Long = 10,
    ) = UserPriceTriggerDto(
        id = id,
        discordId = discordId,
        guildId = guildId,
        thresholdPrice = threshold,
        priceAtCreation = priceAtCreation,
        side = side.name,
        amount = amount,
        enabled = true,
    )

    @Test
    fun `downward target fires when newPrice reaches or passes through threshold`() {
        // Buy at 100 when price was 120 — fires the first time price hits 100 or below.
        val trigger = row(id = 1, threshold = 100.0, priceAtCreation = 120.0)
        every { persistence.listEnabledByGuild(guildId) } returns listOf(trigger)

        assertTrue(service.findTriggered(guildId, newPrice = 100.0).isNotEmpty())
        assertTrue(service.findTriggered(guildId, newPrice = 99.5).isNotEmpty())
        assertTrue(service.findTriggered(guildId, newPrice = 50.0).isNotEmpty())
    }

    @Test
    fun `downward target does not fire while price stays above threshold`() {
        val trigger = row(id = 1, threshold = 100.0, priceAtCreation = 120.0)
        every { persistence.listEnabledByGuild(guildId) } returns listOf(trigger)

        assertTrue(service.findTriggered(guildId, newPrice = 110.0).isEmpty())
        assertTrue(service.findTriggered(guildId, newPrice = 120.0).isEmpty())
        assertTrue(service.findTriggered(guildId, newPrice = 200.0).isEmpty())
    }

    @Test
    fun `upward target fires when newPrice reaches or passes through threshold`() {
        // Sell at 150 when price was 120 — fires the first time price hits 150 or above.
        val trigger = row(
            id = 2,
            threshold = 150.0,
            priceAtCreation = 120.0,
            side = UserPriceTriggerDto.Side.SELL,
        )
        every { persistence.listEnabledByGuild(guildId) } returns listOf(trigger)

        assertTrue(service.findTriggered(guildId, newPrice = 150.0).isNotEmpty())
        assertTrue(service.findTriggered(guildId, newPrice = 151.0).isNotEmpty())
        assertTrue(service.findTriggered(guildId, newPrice = 999.0).isNotEmpty())
    }

    @Test
    fun `upward target does not fire while price stays below threshold`() {
        val trigger = row(
            id = 2,
            threshold = 150.0,
            priceAtCreation = 120.0,
            side = UserPriceTriggerDto.Side.SELL,
        )
        every { persistence.listEnabledByGuild(guildId) } returns listOf(trigger)

        assertTrue(service.findTriggered(guildId, newPrice = 149.99).isEmpty())
        assertTrue(service.findTriggered(guildId, newPrice = 120.0).isEmpty())
        assertTrue(service.findTriggered(guildId, newPrice = 50.0).isEmpty())
    }

    @Test
    fun `price gap past threshold in a single tick still fires`() {
        // Created when price was 120; tick goes 120 → 95 (gaps past 100).
        val trigger = row(id = 3, threshold = 100.0, priceAtCreation = 120.0)
        every { persistence.listEnabledByGuild(guildId) } returns listOf(trigger)

        assertTrue(service.findTriggered(guildId, newPrice = 95.0).isNotEmpty())
    }

    @Test
    fun `enabled rows from listEnabledByGuild are the only candidates`() {
        // Service delegates to listEnabledByGuild — disabled rows are
        // never returned by that query, so we just verify both rows
        // returned by the mock get evaluated against the crossing check.
        val armed = row(id = 1, threshold = 100.0, priceAtCreation = 120.0)
        val notReached = row(id = 2, threshold = 50.0, priceAtCreation = 120.0)
        every { persistence.listEnabledByGuild(guildId) } returns listOf(armed, notReached)

        val fired = service.findTriggered(guildId, newPrice = 99.0)
        assertEquals(listOf(1L), fired.map { it.id })
    }

    @Test
    fun `multiple triggers for same user can fire on same tick`() {
        val a = row(id = 1, threshold = 100.0, priceAtCreation = 120.0)
        val b = row(id = 2, threshold = 110.0, priceAtCreation = 120.0)
        every { persistence.listEnabledByGuild(guildId) } returns listOf(a, b)

        val fired = service.findTriggered(guildId, newPrice = 99.0)
        assertEquals(listOf(1L, 2L), fired.map { it.id })
    }

    @Test
    fun `create rejects non-positive amount and threshold`() {
        val ex1 = runCatching {
            service.create(
                discordId, guildId, threshold = 100.0, priceAtCreation = 120.0,
                side = UserPriceTriggerDto.Side.BUY, amount = 0L
            )
        }.exceptionOrNull()
        assertTrue(ex1 is IllegalArgumentException)

        val ex2 = runCatching {
            service.create(
                discordId, guildId, threshold = 0.0, priceAtCreation = 120.0,
                side = UserPriceTriggerDto.Side.BUY, amount = 1L
            )
        }.exceptionOrNull()
        assertTrue(ex2 is IllegalArgumentException)
    }

    @Test
    fun `remove enforces ownership`() {
        val row = row(id = 7, threshold = 100.0, priceAtCreation = 120.0)
        every { persistence.findById(7L) } returns row
        every { persistence.deleteById(7L) } returns true

        // Wrong owner — must not delete.
        assertFalse(service.remove(7L, requestingDiscordId = 9999L))
        // Correct owner — proceeds.
        assertTrue(service.remove(7L, requestingDiscordId = discordId))
    }

    @Test
    fun `remove returns false when no row exists`() {
        every { persistence.findById(404L) } returns null

        assertFalse(service.remove(404L, requestingDiscordId = discordId))
        verify(exactly = 0) { persistence.deleteById(any()) }
    }

    @Test
    fun `create persists a row with the enum stored as its name and enabled=true`() {
        val saved = slot<UserPriceTriggerDto>()
        every { persistence.save(capture(saved)) } answers { firstArg() }

        val result = service.create(
            discordId, guildId,
            threshold = 90.0, priceAtCreation = 120.0,
            side = UserPriceTriggerDto.Side.SELL, amount = 7L,
        )

        assertEquals("SELL", saved.captured.side)
        assertEquals(7L, saved.captured.amount)
        assertEquals(90.0, saved.captured.thresholdPrice)
        assertEquals(120.0, saved.captured.priceAtCreation)
        assertTrue(saved.captured.enabled)
        assertNotNull(result)
        // Sanity-check the typed accessor — refactor R1.
        assertEquals(UserPriceTriggerDto.Side.SELL, saved.captured.sideEnum)
    }

    @Test
    fun `listForUser delegates straight to persistence`() {
        val rows = listOf(row(id = 1, threshold = 50.0, priceAtCreation = 100.0))
        every { persistence.listByUser(discordId, guildId) } returns rows

        assertEquals(rows, service.listForUser(discordId, guildId))
        verify(exactly = 1) { persistence.listByUser(discordId, guildId) }
    }

    @Test
    fun `markFired stamps firedAt and disables, then saves`() {
        val row = row(id = 5, threshold = 100.0, priceAtCreation = 120.0)
        every { persistence.findById(5L) } returns row
        val saved = slot<UserPriceTriggerDto>()
        every { persistence.save(capture(saved)) } answers { firstArg() }

        val now = Instant.parse("2026-05-19T12:00:00Z")
        service.markFired(5L, now)

        assertEquals(now, saved.captured.firedAt)
        assertFalse(saved.captured.enabled)
    }

    @Test
    fun `markFired on missing id is a silent no-op`() {
        every { persistence.findById(404L) } returns null

        service.markFired(404L, Instant.now())

        verify(exactly = 0) { persistence.save(any()) }
    }

    @Test
    fun `sideEnum accessor round-trips through the string column`() {
        // R1 refactor: verify the typed accessor reads what setter wrote.
        val dto = UserPriceTriggerDto(
            discordId = discordId, guildId = guildId,
            thresholdPrice = 100.0, priceAtCreation = 120.0,
            side = UserPriceTriggerDto.Side.BUY.name, amount = 1L,
        )
        assertEquals(UserPriceTriggerDto.Side.BUY, dto.sideEnum)

        dto.sideEnum = UserPriceTriggerDto.Side.SELL
        assertEquals("SELL", dto.side)
        assertEquals(UserPriceTriggerDto.Side.SELL, dto.sideEnum)
    }

    @Test
    fun `sideEnum getter throws when column holds garbage`() {
        val dto = UserPriceTriggerDto(side = "WOBBLE")
        val ex = runCatching { dto.sideEnum }.exceptionOrNull()
        assertTrue(ex is IllegalArgumentException, "expected IAE, got $ex")
    }
}
