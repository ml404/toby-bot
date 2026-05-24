package database.service.economy.impl

import database.dto.UserPriceTriggerDto
import database.persistence.economy.UserPriceTriggerPersistence
import database.service.economy.UserPriceTriggerService
import org.springframework.stereotype.Service
import java.time.Instant

@Service
class DefaultUserPriceTriggerService(
    private val persistence: UserPriceTriggerPersistence,
) : UserPriceTriggerService {

    override fun create(
        discordId: Long,
        guildId: Long,
        threshold: Double,
        priceAtCreation: Double,
        side: UserPriceTriggerDto.Side,
        amount: Long,
    ): UserPriceTriggerDto {
        require(amount > 0L) { "amount must be positive (was $amount)" }
        require(threshold > 0.0) { "threshold must be positive (was $threshold)" }
        return persistence.save(
            UserPriceTriggerDto(
                discordId = discordId,
                guildId = guildId,
                thresholdPrice = threshold,
                priceAtCreation = priceAtCreation,
                side = side.name,
                amount = amount,
                enabled = true,
                createdAt = Instant.now(),
            )
        )
    }

    override fun listForUser(discordId: Long, guildId: Long): List<UserPriceTriggerDto> =
        persistence.listByUser(discordId, guildId)

    override fun remove(id: Long, requestingDiscordId: Long): Boolean {
        val row = persistence.findById(id) ?: return false
        if (row.discordId != requestingDiscordId) return false
        return persistence.deleteById(id)
    }

    override fun findTriggered(guildId: Long, newPrice: Double): List<UserPriceTriggerDto> {
        return persistence.listEnabledByGuild(guildId).filter { row ->
            // Target reached from the original side: equivalent to
            //   (priceAtCreation > threshold && newPrice <= threshold) ||
            //   (priceAtCreation < threshold && newPrice >= threshold).
            // The single-line product check below collapses both cases
            // and stays well-behaved at the equality boundary because
            // we reject `threshold == priceAtCreation` upstream in the
            // slash command.
            (row.priceAtCreation - row.thresholdPrice) *
                (newPrice - row.thresholdPrice) <= 0.0
        }
    }

    override fun markFired(id: Long, firedAt: Instant) {
        val row = persistence.findById(id) ?: return
        row.firedAt = firedAt
        row.enabled = false
        persistence.save(row)
    }
}
