package web.service

import database.duel.PendingDuelRegistry
import database.duel.RecentDuelResolutions
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
    private val recentDuelResolutions: RecentDuelResolutions,
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

    data class ResolutionView(
        val initiatorDiscordId: String,
        val initiatorName: String,
        val initiatorAvatarUrl: String?,
        val opponentDiscordId: String,
        val opponentName: String,
        val opponentAvatarUrl: String?,
        val winnerDiscordId: String,
        val pot: Long,
        val lossTribute: Long,
    )

    /** Combined `/outgoing` payload: still-pending offers plus any
     *  just-resolved duels the initiator hasn't seen yet (read-once;
     *  consumed from [RecentDuelResolutions]). */
    data class OutgoingPayload(
        val pending: List<PendingDuelView>,
        val resolutions: List<ResolutionView>,
    )

    fun outgoingPayload(discordId: Long, guildId: Long): OutgoingPayload {
        val pending = pendingForInitiator(discordId, guildId)
        val resolved = recentDuelResolutions.consumeForInitiator(discordId, guildId)
        if (resolved.isEmpty()) return OutgoingPayload(pending, emptyList())
        val ids = resolved.flatMapTo(HashSet()) { listOf(it.initiatorDiscordId, it.opponentDiscordId) }
        val members = memberLookup.resolveAll(guildId, ids)
        val resolutions = resolved.map { r ->
            val initiator = members[r.initiatorDiscordId]
            val opponent = members[r.opponentDiscordId]
            ResolutionView(
                initiatorDiscordId = r.initiatorDiscordId.toString(),
                initiatorName = initiator?.name ?: memberLookup.fallbackName(r.initiatorDiscordId),
                initiatorAvatarUrl = initiator?.avatarUrl,
                opponentDiscordId = r.opponentDiscordId.toString(),
                opponentName = opponent?.name ?: memberLookup.fallbackName(r.opponentDiscordId),
                opponentAvatarUrl = opponent?.avatarUrl,
                winnerDiscordId = r.winnerDiscordId.toString(),
                pot = r.pot,
                lossTribute = r.lossTribute,
            )
        }
        return OutgoingPayload(pending, resolutions)
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
