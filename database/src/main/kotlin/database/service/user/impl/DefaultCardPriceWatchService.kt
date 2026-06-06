package database.service.user.impl

import database.dto.user.CardPriceWatchDto
import database.persistence.user.CardPriceWatchPersistence
import database.service.user.CardPriceWatchService
import org.springframework.stereotype.Service
import java.time.Instant

@Service
class DefaultCardPriceWatchService(
    private val persistence: CardPriceWatchPersistence,
) : CardPriceWatchService {

    override val maxPerUser: Int = MAX_PER_USER

    override fun create(
        discordId: Long,
        guildId: Long,
        cardName: String,
        currency: String,
        direction: CardPriceWatchDto.Direction,
        threshold: Double,
        priceAtCreation: Double?,
    ): CardPriceWatchDto? {
        require(cardName.isNotBlank()) { "cardName must not be blank" }
        require(threshold > 0.0) { "threshold must be positive (was $threshold)" }
        if (persistence.listByUser(discordId).size >= MAX_PER_USER) return null
        return persistence.save(
            CardPriceWatchDto(
                discordId = discordId,
                guildId = guildId,
                cardName = cardName,
                currency = currency,
                direction = direction.name,
                threshold = threshold,
                priceAtCreation = priceAtCreation,
                enabled = true,
                createdAt = Instant.now(),
            )
        )
    }

    override fun listForUser(discordId: Long): List<CardPriceWatchDto> =
        persistence.listByUser(discordId)

    override fun listEnabled(): List<CardPriceWatchDto> = persistence.listEnabled()

    override fun remove(id: Long, requestingDiscordId: Long): Boolean {
        val row = persistence.findById(id) ?: return false
        if (row.discordId != requestingDiscordId) return false
        return persistence.deleteById(id)
    }

    override fun markFired(id: Long, firedAt: Instant) {
        val row = persistence.findById(id) ?: return
        row.firedAt = firedAt
        row.enabled = false
        persistence.save(row)
    }

    private companion object {
        const val MAX_PER_USER = 25
    }
}
