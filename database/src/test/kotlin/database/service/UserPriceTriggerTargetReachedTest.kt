package database.service

import database.dto.UserPriceTriggerDto
import database.persistence.UserPriceTriggerPersistence
import database.service.impl.DefaultUserPriceTriggerService
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class UserPriceTriggerTargetReachedTest {

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
}
