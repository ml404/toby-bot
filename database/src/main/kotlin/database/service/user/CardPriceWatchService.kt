package database.service.user

import database.dto.user.CardPriceWatchDto
import java.time.Instant

interface CardPriceWatchService {
    /** Max watches one account may hold, to bound the job's fetch fan-out. */
    val maxPerUser: Int

    /**
     * Create a watch. Returns null when the user is already at [maxPerUser].
     * [priceAtCreation] is the card's price when the watch was set (context
     * for the alert), or null when unknown.
     */
    fun create(
        discordId: Long,
        guildId: Long,
        cardName: String,
        currency: String,
        direction: CardPriceWatchDto.Direction,
        threshold: Double,
        priceAtCreation: Double? = null,
    ): CardPriceWatchDto?

    fun listForUser(discordId: Long): List<CardPriceWatchDto>

    /** Every enabled watch across all users (the scheduler's scan). */
    fun listEnabled(): List<CardPriceWatchDto>

    /** Delete watch [id] only if it belongs to [requestingDiscordId]. */
    fun remove(id: Long, requestingDiscordId: Long): Boolean

    /** Mark [id] fired at [firedAt] and disable it (one-shot). */
    fun markFired(id: Long, firedAt: Instant)
}
