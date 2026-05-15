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
    private val memberLookup: MemberLookupHelper,
) {
    data class PendingDuelView(
        val duelId: Long,
        // Stringified so the 18-digit Discord snowflake survives JS's 53-bit
        // Number precision. duel.js renders these into the row text directly,
        // so a numeric round-trip would print a rounded id.
        val initiatorDiscordId: String,
        val initiatorName: String,
        val initiatorAvatarUrl: String?,
        val opponentDiscordId: String,
        val opponentName: String,
        val opponentAvatarUrl: String?,
        val stake: Long,
        val createdAtEpochSeconds: Long,
    )

    fun pendingForOpponent(discordId: Long, guildId: Long): List<PendingDuelView> {
        val rows = pendingDuelRegistry.pendingForOpponent(discordId, guildId)
        return project(rows, guildId)
    }

    fun pendingForInitiator(discordId: Long, guildId: Long): List<PendingDuelView> {
        val rows = pendingDuelRegistry.pendingForInitiator(discordId, guildId)
        return project(rows, guildId)
    }

    private fun project(rows: List<PendingDuelRegistry.PendingDuel>, guildId: Long): List<PendingDuelView> {
        if (rows.isEmpty()) return emptyList()
        val ids = rows.flatMapTo(HashSet()) { listOf(it.initiatorDiscordId, it.opponentDiscordId) }
        val members = memberLookup.resolveAll(guildId, ids)
        return rows.map { d ->
            val initiator = members[d.initiatorDiscordId]
            val opponent = members[d.opponentDiscordId]
            PendingDuelView(
                duelId = d.id,
                initiatorDiscordId = d.initiatorDiscordId.toString(),
                initiatorName = initiator?.name ?: memberLookup.fallbackName(d.initiatorDiscordId),
                initiatorAvatarUrl = initiator?.avatarUrl,
                opponentDiscordId = d.opponentDiscordId.toString(),
                opponentName = opponent?.name ?: memberLookup.fallbackName(d.opponentDiscordId),
                opponentAvatarUrl = opponent?.avatarUrl,
                stake = d.stake,
                createdAtEpochSeconds = d.createdAt.epochSecond,
            )
        }
    }

    fun ensureOpponent(opponentDiscordId: Long, guildId: Long): UserDto {
        return userService.getUserById(opponentDiscordId, guildId)
            ?: userService.createNewUser(UserDto(opponentDiscordId, guildId))
    }
}
