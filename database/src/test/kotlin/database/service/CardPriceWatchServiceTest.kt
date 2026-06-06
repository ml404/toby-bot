package database.service

import database.dto.user.CardPriceWatchDto
import database.dto.user.CardPriceWatchDto.Direction
import database.persistence.user.CardPriceWatchPersistence
import database.service.user.impl.DefaultCardPriceWatchService
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant

class CardPriceWatchServiceTest {

    private val discordId = 42L
    private val guildId = 100L
    private lateinit var persistence: CardPriceWatchPersistence
    private lateinit var service: DefaultCardPriceWatchService

    @BeforeEach
    fun setUp() {
        persistence = mockk(relaxed = true)
        service = DefaultCardPriceWatchService(persistence)
    }

    @Test
    fun `create persists an enabled watch with the direction stored as its name`() {
        every { persistence.listByUser(discordId) } returns emptyList()
        val saved = slot<CardPriceWatchDto>()
        every { persistence.save(capture(saved)) } answers { firstArg() }

        val result = service.create(discordId, guildId, "Ragavan", "usd", Direction.BELOW, 30.0, 45.0)

        assertNotNull(result)
        assertEquals("Ragavan", saved.captured.cardName)
        assertEquals("usd", saved.captured.currency)
        assertEquals("BELOW", saved.captured.direction)
        assertEquals(30.0, saved.captured.threshold)
        assertEquals(45.0, saved.captured.priceAtCreation)
        assertTrue(saved.captured.enabled)
    }

    @Test
    fun `create returns null when the user is at the watch cap`() {
        every { persistence.listByUser(discordId) } returns
            (1..service.maxPerUser).map { CardPriceWatchDto(id = it.toLong(), discordId = discordId) }

        assertNull(service.create(discordId, guildId, "Ragavan", "usd", Direction.BELOW, 30.0, null))
        verify(exactly = 0) { persistence.save(any()) }
    }

    @Test
    fun `create rejects a blank name or non-positive threshold`() {
        every { persistence.listByUser(discordId) } returns emptyList()
        assertTrue(runCatching { service.create(discordId, guildId, "  ", "usd", Direction.BELOW, 30.0, null) }
            .exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { service.create(discordId, guildId, "Bolt", "usd", Direction.BELOW, 0.0, null) }
            .exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun `remove enforces ownership`() {
        val row = CardPriceWatchDto(id = 7, discordId = discordId)
        every { persistence.findById(7L) } returns row
        every { persistence.deleteById(7L) } returns true

        assertFalse(service.remove(7L, requestingDiscordId = 9999L))
        assertTrue(service.remove(7L, requestingDiscordId = discordId))
    }

    @Test
    fun `remove returns false when the row is missing`() {
        every { persistence.findById(404L) } returns null
        assertFalse(service.remove(404L, discordId))
        verify(exactly = 0) { persistence.deleteById(any()) }
    }

    @Test
    fun `markFired stamps firedAt and disables`() {
        val row = CardPriceWatchDto(id = 5, discordId = discordId, enabled = true)
        every { persistence.findById(5L) } returns row
        val saved = slot<CardPriceWatchDto>()
        every { persistence.save(capture(saved)) } answers { firstArg() }

        val now = Instant.parse("2026-06-06T12:00:00Z")
        service.markFired(5L, now)

        assertEquals(now, saved.captured.firedAt)
        assertFalse(saved.captured.enabled)
    }

    @Test
    fun `listForUser and listEnabled delegate to persistence`() {
        every { persistence.listByUser(discordId) } returns listOf(CardPriceWatchDto(id = 1, discordId = discordId))
        every { persistence.listEnabled() } returns listOf(CardPriceWatchDto(id = 2))
        assertEquals(1, service.listForUser(discordId).size)
        assertEquals(1, service.listEnabled().size)
    }

    // --- DTO logic ----------------------------------------------------

    @Test
    fun `isTriggeredBy fires below or above the threshold per direction`() {
        val below = CardPriceWatchDto(direction = Direction.BELOW.name, threshold = 30.0)
        assertTrue(below.isTriggeredBy(30.0))
        assertTrue(below.isTriggeredBy(29.0))
        assertFalse(below.isTriggeredBy(31.0))

        val above = CardPriceWatchDto(direction = Direction.ABOVE.name, threshold = 30.0)
        assertTrue(above.isTriggeredBy(30.0))
        assertTrue(above.isTriggeredBy(31.0))
        assertFalse(above.isTriggeredBy(29.0))
    }

    @Test
    fun `directionEnum round-trips through the string column`() {
        val dto = CardPriceWatchDto(direction = Direction.BELOW.name)
        assertEquals(Direction.BELOW, dto.directionEnum)
        dto.directionEnum = Direction.ABOVE
        assertEquals("ABOVE", dto.direction)
    }
}
