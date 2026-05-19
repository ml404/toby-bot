package database.service

import database.dto.UserPriceTriggerDto
import java.time.Instant

interface UserPriceTriggerService {
    fun create(
        discordId: Long,
        guildId: Long,
        threshold: Double,
        priceAtCreation: Double,
        side: UserPriceTriggerDto.Side,
        amount: Long,
    ): UserPriceTriggerDto

    fun listForUser(discordId: Long, guildId: Long): List<UserPriceTriggerDto>

    /**
     * Delete trigger [id] only if it belongs to [requestingDiscordId]
     * — guards against one user removing another's row.
     */
    fun remove(id: Long, requestingDiscordId: Long): Boolean

    /**
     * Returns every enabled trigger in [guildId] whose target was
     * reached by [newPrice] from the side the price was on at the
     * trigger's creation. Direction is implicit:
     *   (priceAtCreation - threshold) * (newPrice - threshold) <= 0.
     */
    fun findTriggered(guildId: Long, newPrice: Double): List<UserPriceTriggerDto>

    /** Mark [id] fired at [firedAt] and disable it. */
    fun markFired(id: Long, firedAt: Instant)
}
