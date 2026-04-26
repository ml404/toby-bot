package web.service

import database.duel.PendingDuelRegistry
import database.dto.UserDto
import database.service.UserService
import org.springframework.stereotype.Service

/**
 * Read-only projection helpers around the in-memory
 * [PendingDuelRegistry] for the web UI plus a lazy-create for the
 * opponent's [UserDto] row.
 */
@Service
class DuelWebService(
    private val pendingDuelRegistry: PendingDuelRegistry,
    private val userService: UserService,
) {
    data class PendingDuelView(
        val duelId: Long,
        val initiatorDiscordId: Long,
        val opponentDiscordId: Long,
        val stake: Long
    )

    fun pendingForOpponent(discordId: Long, guildId: Long): List<PendingDuelView> =
        pendingDuelRegistry.pendingForOpponent(discordId, guildId).map(::toView)

    fun pendingForInitiator(discordId: Long, guildId: Long): List<PendingDuelView> =
        pendingDuelRegistry.pendingForInitiator(discordId, guildId).map(::toView)

    private fun toView(d: PendingDuelRegistry.PendingDuel): PendingDuelView =
        PendingDuelView(
            duelId = d.id,
            initiatorDiscordId = d.initiatorDiscordId,
            opponentDiscordId = d.opponentDiscordId,
            stake = d.stake
        )

    fun ensureOpponent(opponentDiscordId: Long, guildId: Long): UserDto {
        return userService.getUserById(opponentDiscordId, guildId)
            ?: userService.createNewUser(UserDto(opponentDiscordId, guildId))
    }
}
