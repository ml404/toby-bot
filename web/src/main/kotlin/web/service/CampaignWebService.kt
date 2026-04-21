package web.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import common.events.CampaignEventType
import common.helpers.parseDndBeyondCharacterId
import common.logging.DiscordLogger
import database.dto.CampaignDto
import database.dto.CampaignPlayerDto
import database.dto.CampaignPlayerId
import database.dto.MonsterTemplateDto
import database.dto.SessionNoteDto
import database.dto.UserDto
import database.service.CampaignEventService
import database.service.CampaignPlayerService
import database.service.CampaignService
import database.service.CharacterSheetService
import database.dto.MonsterAttackDto
import database.service.MonsterAttackService
import database.service.MonsterTemplateService
import database.service.SessionNoteService
import database.service.UserService
import net.dv8tion.jda.api.JDA
import org.springframework.stereotype.Service
import java.time.LocalDateTime
import kotlin.random.Random

data class CampaignDetail(
    val campaign: CampaignDto,
    val players: List<PlayerInfo>,
    val dmName: String,
    val isCurrentUserPlayer: Boolean = false,
    val currentUserCharacterId: Long? = null,
    val notes: List<SessionNoteView> = emptyList(),
    val recentEvents: List<SessionEventView> = emptyList(),
    val initiativeState: InitiativeStateView? = null,
    val monsterLibrary: List<MonsterTemplateView> = emptyList()
) {
    fun isDm(discordId: Long): Boolean = campaign.dmDiscordId == discordId
}

data class InitiativeStateView(
    val entries: List<RolledEntryView>,
    val currentIndex: Int
)

data class RolledEntryView(
    val name: String,
    val roll: Int,
    val kind: String?,
    val maxHp: Int? = null,
    val currentHp: Int? = null,
    val ac: Int? = null,
    val defeated: Boolean = false,
    val templateId: Long? = null,
    val attacks: List<MonsterAttackView> = emptyList()
)

data class MonsterAttackView(
    val id: Long,
    val name: String,
    val toHitModifier: Int,
    val damageExpression: String
)

data class MonsterTemplateView(
    val id: Long,
    val name: String,
    val initiativeModifier: Int,
    val hpExpression: String?,
    val ac: Int?,
    val attacks: List<MonsterAttackView> = emptyList()
)

data class AdhocMonster(
    val name: String,
    val initiativeModifier: Int,
    val hpExpression: String? = null,
    val ac: Int? = null
)

data class InitiativeRollRequest(
    val playerDiscordIds: List<Long> = emptyList(),
    val templateIds: List<Long> = emptyList(),
    val adhocMonsters: List<AdhocMonster> = emptyList()
)

data class SessionNoteView(
    val id: Long,
    val authorDiscordId: Long,
    val authorName: String,
    val body: String,
    val createdAt: LocalDateTime,
    val canDelete: Boolean
)

data class SessionEventView(
    val id: Long,
    val type: String,
    val actorDiscordId: Long?,
    val actorName: String?,
    val refEventId: Long?,
    val payload: Map<String, Any?>,
    val createdAt: LocalDateTime
)

data class PlayerInfo(
    val discordId: Long,
    val displayName: String,
    val characterId: Long?,
    val alive: Boolean,
    val characterName: String? = null,
    val characterRace: String? = null,
    val characterClasses: String? = null,
    val characterLevel: Int? = null
) {
    val characterLink: String?
        get() = characterId?.let { "https://www.dndbeyond.com/characters/$it" }
}

data class GuildCampaignInfo(
    val id: String,
    val name: String,
    val iconHash: String?,
    val activeCampaign: CampaignDto?
) {
    val iconUrl: String?
        get() = iconHash?.let { "https://cdn.discordapp.com/icons/$id/$it.png?size=64" }
}

enum class JoinResult { JOINED, NO_ACTIVE_CAMPAIGN, ALREADY_JOINED, IS_DM }
enum class LeaveResult { LEFT, NO_ACTIVE_CAMPAIGN, NOT_A_PLAYER }
enum class SetCharacterResult { UPDATED, CLEARED, INVALID }
enum class EndResult { ENDED, NO_ACTIVE_CAMPAIGN, NOT_DM }
enum class KickResult { KICKED, NO_ACTIVE_CAMPAIGN, NOT_DM, NOT_A_PLAYER, CANNOT_KICK_DM }
enum class SetAliveResult { UPDATED, NO_ACTIVE_CAMPAIGN, NOT_DM, NOT_A_PLAYER }
enum class AddNoteResult { ADDED, NO_ACTIVE_CAMPAIGN, NOT_PARTICIPANT, EMPTY_BODY, BODY_TOO_LONG }
enum class DeleteNoteResult { DELETED, NO_ACTIVE_CAMPAIGN, NOT_FOUND, NOT_ALLOWED }
enum class AnnotateRollResult { ANNOTATED, NO_ACTIVE_CAMPAIGN, NOT_DM, NOT_FOUND, NOT_A_ROLL, INVALID_KIND }
enum class NarrateResult { NARRATED, NO_ACTIVE_CAMPAIGN, NOT_DM, EMPTY_BODY, BODY_TOO_LONG }
enum class SaveTemplateResult { SAVED, NAME_BLANK, NAME_TOO_LONG, INVALID_HP, NOT_FOUND, NOT_OWNER }
enum class DeleteTemplateResult { DELETED, NOT_FOUND, NOT_OWNER }
enum class SaveAttackResult {
    SAVED, NAME_BLANK, NAME_TOO_LONG, INVALID_MODIFIER, INVALID_DAMAGE,
    TOO_MANY, TEMPLATE_NOT_FOUND, NOT_OWNER, ATTACK_NOT_FOUND, ATTACK_TEMPLATE_MISMATCH
}
enum class DeleteAttackResult { DELETED, ATTACK_NOT_FOUND, NOT_OWNER, ATTACK_TEMPLATE_MISMATCH }
enum class MonsterAttackResult {
    HIT, MISS,
    NO_ACTIVE_CAMPAIGN, NO_ACTIVE_COMBAT, NOT_DM,
    CURRENT_NOT_MONSTER, NO_TEMPLATE,
    ATTACK_NOT_FOUND, ATTACK_TEMPLATE_MISMATCH, INVALID_DAMAGE,
    TARGET_NOT_FOUND, TARGET_DEFEATED, CANT_TARGET_SELF
}

data class MonsterAttackOutcome(
    val result: MonsterAttackResult,
    val attacker: String? = null,
    val target: String? = null,
    val attackName: String? = null,
    val rawRoll: Int? = null,
    val toHit: Int? = null,
    val total: Int? = null,
    val targetAc: Int? = null,
    val damageTotal: Int? = null,
    val damageExpression: String? = null,
    val damageRolls: List<Int>? = null,
    val targetDefeated: Boolean = false
)
enum class RollInitiativeResult { ROLLED, NO_ACTIVE_CAMPAIGN, NOT_DM, EMPTY_ROSTER, TEMPLATE_NOT_FOUND }
enum class RollDiceResult { ROLLED, NO_ACTIVE_CAMPAIGN, NOT_PARTICIPANT, INVALID_SIDES, INVALID_COUNT, INVALID_MODIFIER, INVALID_EXPRESSION }

enum class AttackResult {
    HIT, MISS,
    NO_ACTIVE_CAMPAIGN, NO_ACTIVE_COMBAT, NOT_MY_TURN,
    TARGET_NOT_FOUND, TARGET_DEFEATED, CANT_TARGET_SELF,
    INVALID_MODIFIER
}

enum class ApplyDamageResult {
    APPLIED, DEFEATED,
    NO_ACTIVE_CAMPAIGN, NO_ACTIVE_COMBAT, NOT_ATTACKER,
    TARGET_NOT_FOUND, INVALID_AMOUNT
}

enum class ApplyHealResult {
    APPLIED, REVIVED,
    NO_ACTIVE_CAMPAIGN, NO_ACTIVE_COMBAT, NOT_ATTACKER,
    TARGET_NOT_FOUND, TARGET_HAS_NO_HP, INVALID_AMOUNT
}

data class AttackOutcome(
    val result: AttackResult,
    val attacker: String? = null,
    val target: String? = null,
    val rawRoll: Int? = null,
    val modifier: Int? = null,
    val total: Int? = null,
    val targetAc: Int? = null,
    val eventId: Long? = null
)

@Service
class CampaignWebService(
    private val campaignService: CampaignService,
    private val campaignPlayerService: CampaignPlayerService,
    private val introWebService: IntroWebService,
    private val userService: UserService,
    private val characterSheetService: CharacterSheetService,
    private val sessionNoteService: SessionNoteService,
    private val campaignEventService: CampaignEventService,
    private val monsterTemplateService: MonsterTemplateService,
    private val monsterAttackService: MonsterAttackService,
    private val initiativeStore: InitiativeStore,
    private val sessionLog: SessionLogPublisher,
    private val jda: JDA
) {

    private val objectMapper = ObjectMapper()
    private val payloadTypeRef =
        object : com.fasterxml.jackson.core.type.TypeReference<Map<String, Any?>>() {}
    private val logger = DiscordLogger(CampaignWebService::class.java)

    companion object {
        const val MAX_NOTE_BODY_LENGTH = 2000
        const val MAX_NARRATE_BODY_LENGTH = 1000
        const val DEFAULT_EVENT_LIMIT = 100
        const val MAX_TEMPLATE_NAME_LENGTH = 100
        const val MAX_ATTACK_NAME_LENGTH = 60
        const val MAX_ATTACKS_PER_TEMPLATE = 12
        const val MAX_ATTACK_MODIFIER = 30
        const val MAX_DICE_COUNT = DiceExpressionRoller.MAX_DICE_COUNT
        const val MAX_DICE_MODIFIER = DiceExpressionRoller.MAX_DICE_MODIFIER
        const val MAX_DAMAGE_AMOUNT = DiceExpressionRoller.MAX_LITERAL_AMOUNT
        val ALLOWED_DIE_SIDES = DiceExpressionRoller.ALLOWED_DIE_SIDES
    }

    fun getMutualGuildsWithCampaigns(accessToken: String): List<GuildCampaignInfo> {
        val mutualGuilds = introWebService.getMutualGuilds(accessToken)
        return mutualGuilds.map { guild ->
            val guildId = guild.id.toLongOrNull()
            val campaign = guildId?.let { campaignService.getActiveCampaignForGuild(it) }
            GuildCampaignInfo(
                id = guild.id,
                name = guild.name,
                iconHash = guild.iconHash,
                activeCampaign = campaign
            )
        }
    }

    fun getGuildName(guildId: Long): String? = jda.getGuildById(guildId)?.name

    fun getActiveCampaignId(guildId: Long): Long? =
        campaignService.getActiveCampaignForGuild(guildId)?.id

    private fun resolveMemberName(guildId: Long, discordId: Long): String? =
        jda.getGuildById(guildId)?.getMemberById(discordId)?.effectiveName

    fun getCampaignDetail(guildId: Long, requestingDiscordId: Long): CampaignDetail? {
        val campaign = campaignService.getActiveCampaignForGuild(guildId) ?: return null
        val guild = jda.getGuildById(guildId)

        val dmName = guild?.getMemberById(campaign.dmDiscordId)?.effectiveName
            ?: "Unknown (ID: ${campaign.dmDiscordId})"

        val players = campaignPlayerService.getPlayersForCampaign(campaign.id).map { player ->
            val memberName = guild?.getMemberById(player.id.playerDiscordId)?.effectiveName
                ?: "Unknown (ID: ${player.id.playerDiscordId})"
            val summary = player.characterId?.let(::loadCharacterSummary)
            PlayerInfo(
                discordId = player.id.playerDiscordId,
                displayName = memberName,
                characterId = player.characterId,
                alive = player.alive,
                characterName = summary?.name,
                characterRace = summary?.raceName,
                characterClasses = summary?.classesString,
                characterLevel = summary?.totalLevel
            )
        }

        val isCurrentUserPlayer = players.any { it.discordId == requestingDiscordId }
        val currentUserCharacterId = userService.getUserById(requestingDiscordId, guildId)?.dndBeyondCharacterId
        val isDm = campaign.dmDiscordId == requestingDiscordId

        val notes = safeFetch("session notes", campaign.id) {
            sessionNoteService.getNotesForCampaign(campaign.id).map { note ->
                val authorName = guild?.getMemberById(note.authorDiscordId)?.effectiveName
                    ?: "Unknown (ID: ${note.authorDiscordId})"
                SessionNoteView(
                    id = note.id,
                    authorDiscordId = note.authorDiscordId,
                    authorName = authorName,
                    body = note.body,
                    createdAt = note.createdAt,
                    canDelete = isDm || note.authorDiscordId == requestingDiscordId
                )
            }
        }

        val recentEvents = safeFetch("recent events", campaign.id) {
            campaignEventService.listRecent(campaign.id, DEFAULT_EVENT_LIMIT).map(::toSessionEventView)
        }

        val initiativeState = if (initiativeStore.isActive(guildId)) {
            InitiativeStateView(
                entries = initiativeStore.currentEntries(guildId).map {
                    RolledEntryView(
                        name = it.name,
                        roll = it.roll,
                        kind = it.kind,
                        maxHp = it.maxHp,
                        currentHp = it.currentHp,
                        ac = it.ac,
                        defeated = it.defeated,
                        templateId = it.templateId,
                        attacks = it.templateId?.let { tid ->
                            monsterAttackService.listByTemplate(tid).map(::toMonsterAttackView)
                        } ?: emptyList()
                    )
                },
                currentIndex = initiativeStore.currentIndex(guildId)
            )
        } else null

        val monsterLibrary = if (isDm) {
            safeFetch("monster library", requestingDiscordId) {
                monsterTemplateService.listByDm(requestingDiscordId).map(::toMonsterTemplateView)
            }
        } else emptyList()

        return CampaignDetail(
            campaign = campaign,
            players = players,
            dmName = dmName,
            isCurrentUserPlayer = isCurrentUserPlayer,
            currentUserCharacterId = currentUserCharacterId,
            notes = notes,
            recentEvents = recentEvents,
            initiativeState = initiativeState,
            monsterLibrary = monsterLibrary
        )
    }

    /**
     * Fetch an optional side-section of the campaign detail page. If the
     * backing table doesn't exist yet (e.g. a migration hasn't applied on this
     * environment), return an empty list rather than 500-ing the whole page.
     * The main [CampaignDto] query still governs hard failures.
     */
    private fun <T> safeFetch(label: String, scope: Any, block: () -> List<T>): List<T> =
        runCatching(block).getOrElse { t ->
            logger.warn("Dropping $label for $scope: ${t::class.simpleName}: ${t.message}")
            emptyList()
        }

    private fun toMonsterTemplateView(dto: MonsterTemplateDto): MonsterTemplateView =
        MonsterTemplateView(
            id = dto.id,
            name = dto.name,
            initiativeModifier = dto.initiativeModifier,
            hpExpression = dto.hpExpression,
            ac = dto.ac,
            attacks = monsterAttackService.listByTemplate(dto.id).map(::toMonsterAttackView)
        )

    private fun toMonsterAttackView(dto: MonsterAttackDto): MonsterAttackView =
        MonsterAttackView(
            id = dto.id,
            name = dto.name,
            toHitModifier = dto.toHitModifier,
            damageExpression = dto.damageExpression
        )

    fun listRecentEvents(guildId: Long, sinceId: Long?, limit: Int): List<SessionEventView> {
        val campaign = campaignService.getActiveCampaignForGuild(guildId) ?: return emptyList()
        val bounded = limit.coerceIn(1, DEFAULT_EVENT_LIMIT * 10)
        val events = if (sinceId != null) {
            campaignEventService.listSince(campaign.id, sinceId, bounded)
        } else {
            campaignEventService.listRecent(campaign.id, bounded)
        }
        return events.map(::toSessionEventView)
    }

    private fun toSessionEventView(dto: database.dto.CampaignEventDto): SessionEventView {
        val parsed = runCatching { objectMapper.readValue(dto.payload, payloadTypeRef) }
            .getOrDefault(emptyMap())
        return SessionEventView(
            id = dto.id,
            type = dto.eventType,
            actorDiscordId = dto.actorDiscordId,
            actorName = dto.actorName,
            refEventId = dto.refEventId,
            payload = parsed,
            createdAt = dto.createdAt
        )
    }

    fun createCampaign(guildId: Long, dmDiscordId: Long, name: String): CampaignDto? {
        if (campaignService.getActiveCampaignForGuild(guildId) != null) return null
        return campaignService.createCampaign(
            CampaignDto(guildId = guildId, channelId = 0L, dmDiscordId = dmDiscordId, name = name)
        )
    }

    fun joinCampaign(guildId: Long, discordId: Long): JoinResult {
        val campaign = campaignService.getActiveCampaignForGuild(guildId)
            ?: return JoinResult.NO_ACTIVE_CAMPAIGN
        if (campaign.dmDiscordId == discordId) return JoinResult.IS_DM

        val playerId = CampaignPlayerId(campaign.id, discordId)
        if (campaignPlayerService.getPlayer(playerId) != null) return JoinResult.ALREADY_JOINED

        val characterId = userService.getUserById(discordId, guildId)?.dndBeyondCharacterId
        campaignPlayerService.addPlayer(
            CampaignPlayerDto(
                id = playerId,
                guildId = guildId,
                characterId = characterId,
                alive = true
            )
        )
        sessionLog.publish(
            guildId = guildId,
            type = CampaignEventType.PLAYER_JOINED,
            actorDiscordId = discordId,
            actorName = resolveMemberName(guildId, discordId)
        )
        return JoinResult.JOINED
    }

    fun leaveCampaign(guildId: Long, discordId: Long): LeaveResult {
        val campaign = campaignService.getActiveCampaignForGuild(guildId)
            ?: return LeaveResult.NO_ACTIVE_CAMPAIGN
        val playerId = CampaignPlayerId(campaign.id, discordId)
        if (campaignPlayerService.getPlayer(playerId) == null) return LeaveResult.NOT_A_PLAYER
        campaignPlayerService.removePlayer(playerId)
        sessionLog.publish(
            guildId = guildId,
            type = CampaignEventType.PLAYER_LEFT,
            actorDiscordId = discordId,
            actorName = resolveMemberName(guildId, discordId)
        )
        return LeaveResult.LEFT
    }

    fun setLinkedCharacter(guildId: Long, discordId: Long, input: String): SetCharacterResult {
        val trimmed = input.trim()
        val user = userService.getUserById(discordId, guildId)
            ?: userService.createNewUser(UserDto(discordId = discordId, guildId = guildId))

        if (trimmed.isEmpty()) {
            user.dndBeyondCharacterId = null
            userService.updateUser(user)
            return SetCharacterResult.CLEARED
        }

        val id = parseDndBeyondCharacterId(trimmed) ?: return SetCharacterResult.INVALID
        user.dndBeyondCharacterId = id
        userService.updateUser(user)
        return SetCharacterResult.UPDATED
    }

    fun endCampaign(guildId: Long, requestingDiscordId: Long): EndResult {
        val campaign = campaignService.getActiveCampaignForGuild(guildId)
            ?: return EndResult.NO_ACTIVE_CAMPAIGN
        if (campaign.dmDiscordId != requestingDiscordId) return EndResult.NOT_DM
        // Publish before deactivation so the listener can still resolve the active campaign.
        sessionLog.publish(
            guildId = guildId,
            type = CampaignEventType.CAMPAIGN_ENDED,
            actorDiscordId = requestingDiscordId,
            actorName = resolveMemberName(guildId, requestingDiscordId),
            payload = mapOf("campaignName" to campaign.name)
        )
        campaignService.deactivateCampaignForGuild(guildId)
        return EndResult.ENDED
    }

    fun kickPlayer(guildId: Long, requestingDiscordId: Long, targetDiscordId: Long): KickResult {
        val campaign = campaignService.getActiveCampaignForGuild(guildId)
            ?: return KickResult.NO_ACTIVE_CAMPAIGN
        if (campaign.dmDiscordId != requestingDiscordId) return KickResult.NOT_DM
        if (campaign.dmDiscordId == targetDiscordId) return KickResult.CANNOT_KICK_DM

        val playerId = CampaignPlayerId(campaign.id, targetDiscordId)
        if (campaignPlayerService.getPlayer(playerId) == null) return KickResult.NOT_A_PLAYER
        campaignPlayerService.removePlayer(playerId)
        sessionLog.publish(
            guildId = guildId,
            type = CampaignEventType.PLAYER_KICKED,
            actorDiscordId = requestingDiscordId,
            actorName = resolveMemberName(guildId, requestingDiscordId),
            payload = mapOf(
                "targetDiscordId" to targetDiscordId,
                "targetName" to resolveMemberName(guildId, targetDiscordId)
            )
        )
        return KickResult.KICKED
    }

    fun setPlayerAlive(
        guildId: Long,
        requestingDiscordId: Long,
        targetDiscordId: Long,
        alive: Boolean
    ): SetAliveResult {
        val campaign = campaignService.getActiveCampaignForGuild(guildId)
            ?: return SetAliveResult.NO_ACTIVE_CAMPAIGN
        if (campaign.dmDiscordId != requestingDiscordId) return SetAliveResult.NOT_DM

        val playerId = CampaignPlayerId(campaign.id, targetDiscordId)
        val player = campaignPlayerService.getPlayer(playerId) ?: return SetAliveResult.NOT_A_PLAYER
        val previouslyAlive = player.alive
        player.alive = alive
        campaignPlayerService.updatePlayer(player)
        if (previouslyAlive != alive) {
            sessionLog.publish(
                guildId = guildId,
                type = if (alive) CampaignEventType.PLAYER_REVIVED else CampaignEventType.PLAYER_DIED,
                actorDiscordId = requestingDiscordId,
                actorName = resolveMemberName(guildId, requestingDiscordId),
                payload = mapOf(
                    "targetDiscordId" to targetDiscordId,
                    "targetName" to resolveMemberName(guildId, targetDiscordId)
                )
            )
        }
        return SetAliveResult.UPDATED
    }

    fun addNote(guildId: Long, authorDiscordId: Long, body: String): AddNoteResult {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return AddNoteResult.EMPTY_BODY
        if (trimmed.length > MAX_NOTE_BODY_LENGTH) return AddNoteResult.BODY_TOO_LONG

        val campaign = campaignService.getActiveCampaignForGuild(guildId)
            ?: return AddNoteResult.NO_ACTIVE_CAMPAIGN

        if (!isCampaignParticipant(campaign, authorDiscordId)) return AddNoteResult.NOT_PARTICIPANT

        sessionNoteService.createNote(
            SessionNoteDto(
                campaignId = campaign.id,
                authorDiscordId = authorDiscordId,
                body = trimmed,
                createdAt = LocalDateTime.now()
            )
        )
        return AddNoteResult.ADDED
    }

    fun deleteNote(guildId: Long, requestingDiscordId: Long, noteId: Long): DeleteNoteResult {
        val campaign = campaignService.getActiveCampaignForGuild(guildId)
            ?: return DeleteNoteResult.NO_ACTIVE_CAMPAIGN

        val note = sessionNoteService.getNoteById(noteId) ?: return DeleteNoteResult.NOT_FOUND
        if (note.campaignId != campaign.id) return DeleteNoteResult.NOT_FOUND

        val isDm = campaign.dmDiscordId == requestingDiscordId
        val isAuthor = note.authorDiscordId == requestingDiscordId
        if (!isDm && !isAuthor) return DeleteNoteResult.NOT_ALLOWED

        sessionNoteService.deleteNoteById(noteId)
        return DeleteNoteResult.DELETED
    }

    private fun isCampaignParticipant(campaign: CampaignDto, discordId: Long): Boolean {
        if (campaign.dmDiscordId == discordId) return true
        val playerId = CampaignPlayerId(campaign.id, discordId)
        return campaignPlayerService.getPlayer(playerId) != null
    }

    fun annotateRoll(
        guildId: Long,
        requestingDiscordId: Long,
        refEventId: Long,
        kind: String,
        target: String?
    ): AnnotateRollResult {
        val type = when (kind.uppercase()) {
            "HIT" -> CampaignEventType.HIT
            "MISS" -> CampaignEventType.MISS
            else -> return AnnotateRollResult.INVALID_KIND
        }
        val campaign = campaignService.getActiveCampaignForGuild(guildId)
            ?: return AnnotateRollResult.NO_ACTIVE_CAMPAIGN
        if (campaign.dmDiscordId != requestingDiscordId) return AnnotateRollResult.NOT_DM

        val referenced = campaignEventService.getById(refEventId)
            ?: return AnnotateRollResult.NOT_FOUND
        if (referenced.campaignId != campaign.id) return AnnotateRollResult.NOT_FOUND
        if (referenced.eventType != CampaignEventType.ROLL.name) return AnnotateRollResult.NOT_A_ROLL

        val trimmedTarget = target?.trim()?.takeIf { it.isNotEmpty() }
        sessionLog.publish(
            guildId = guildId,
            type = type,
            actorDiscordId = requestingDiscordId,
            actorName = resolveMemberName(guildId, requestingDiscordId),
            payload = if (trimmedTarget != null) mapOf("target" to trimmedTarget) else emptyMap(),
            refEventId = refEventId
        )
        return AnnotateRollResult.ANNOTATED
    }

    fun narrate(guildId: Long, requestingDiscordId: Long, body: String): NarrateResult {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return NarrateResult.EMPTY_BODY
        if (trimmed.length > MAX_NARRATE_BODY_LENGTH) return NarrateResult.BODY_TOO_LONG

        val campaign = campaignService.getActiveCampaignForGuild(guildId)
            ?: return NarrateResult.NO_ACTIVE_CAMPAIGN
        if (campaign.dmDiscordId != requestingDiscordId) return NarrateResult.NOT_DM

        sessionLog.publish(
            guildId = guildId,
            type = CampaignEventType.DM_NOTE,
            actorDiscordId = requestingDiscordId,
            actorName = resolveMemberName(guildId, requestingDiscordId),
            payload = mapOf("body" to trimmed)
        )
        return NarrateResult.NARRATED
    }

    /**
     * Web dice roll: validates inputs, rolls `count`d`sides`+`modifier`, and
     * publishes a ROLL event so every subscriber (web session log, future
     * widgets) sees the same result. Players and the DM of the active
     * campaign may roll; outsiders are rejected.
     *
     * When [expression] is non-null, it takes precedence over [count]/[sides]/
     * [modifier] and is parsed as `NdS[+|-]M` (whitespace ignored).
     */
    fun rollDice(
        guildId: Long,
        requestingDiscordId: Long,
        count: Int,
        sides: Int,
        modifier: Int,
        expression: String? = null
    ): RollDiceResult {
        val trimmedExpr = expression?.trim()?.takeIf { it.isNotEmpty() }
        val parsed = trimmedExpr?.let {
            DiceExpressionRoller.parseDiceExpression(it) ?: return RollDiceResult.INVALID_EXPRESSION
        }
        val finalCount = parsed?.count ?: count
        val finalSides = parsed?.sides ?: sides
        val finalMod = parsed?.modifier ?: modifier

        if (finalSides !in ALLOWED_DIE_SIDES) return RollDiceResult.INVALID_SIDES
        if (finalCount !in 1..MAX_DICE_COUNT) return RollDiceResult.INVALID_COUNT
        if (finalMod !in -MAX_DICE_MODIFIER..MAX_DICE_MODIFIER) return RollDiceResult.INVALID_MODIFIER

        val campaign = campaignService.getActiveCampaignForGuild(guildId)
            ?: return RollDiceResult.NO_ACTIVE_CAMPAIGN
        if (!isCampaignParticipant(campaign, requestingDiscordId)) return RollDiceResult.NOT_PARTICIPANT

        val rawTotal = (0 until finalCount).sumOf { Random.nextInt(1, finalSides + 1) }
        sessionLog.publish(
            guildId = guildId,
            type = CampaignEventType.ROLL,
            actorDiscordId = requestingDiscordId,
            actorName = resolveMemberName(guildId, requestingDiscordId),
            payload = mapOf(
                "sides" to finalSides,
                "count" to finalCount,
                "modifier" to finalMod,
                "rawTotal" to rawTotal,
                "total" to rawTotal + finalMod
            )
        )
        return RollDiceResult.ROLLED
    }

    fun listTemplatesForDm(dmDiscordId: Long): List<MonsterTemplateView> =
        monsterTemplateService.listByDm(dmDiscordId).map(::toMonsterTemplateView)

    fun saveTemplate(
        dmDiscordId: Long,
        id: Long?,
        name: String,
        initiativeModifier: Int,
        hpExpression: String?,
        ac: Int?
    ): SaveTemplateResult {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return SaveTemplateResult.NAME_BLANK
        if (trimmed.length > MAX_TEMPLATE_NAME_LENGTH) return SaveTemplateResult.NAME_TOO_LONG

        val cleanedHp = hpExpression?.trim()?.takeIf { it.isNotEmpty() }
        if (cleanedHp != null && DiceExpressionRoller.parseAmount(cleanedHp) == null) {
            return SaveTemplateResult.INVALID_HP
        }

        val dto = if (id != null && id > 0) {
            val existing = monsterTemplateService.getById(id) ?: return SaveTemplateResult.NOT_FOUND
            if (existing.dmDiscordId != dmDiscordId) return SaveTemplateResult.NOT_OWNER
            existing.apply {
                this.name = trimmed
                this.initiativeModifier = initiativeModifier
                this.hpExpression = cleanedHp
                this.ac = ac
            }
        } else {
            MonsterTemplateDto(
                dmDiscordId = dmDiscordId,
                name = trimmed,
                initiativeModifier = initiativeModifier,
                hpExpression = cleanedHp,
                ac = ac
            )
        }
        monsterTemplateService.save(dto)
        return SaveTemplateResult.SAVED
    }

    fun deleteTemplate(dmDiscordId: Long, id: Long): DeleteTemplateResult {
        val existing = monsterTemplateService.getById(id) ?: return DeleteTemplateResult.NOT_FOUND
        if (existing.dmDiscordId != dmDiscordId) return DeleteTemplateResult.NOT_OWNER
        monsterTemplateService.deleteById(id)
        return DeleteTemplateResult.DELETED
    }

    /**
     * Create or update an attack on a monster template. Validates that the
     * caller owns the template, the name and damage expression are sane, and
     * the per-template attack cap isn't exceeded. The attack's
     * [damageExpression] is stored verbatim and rolled fresh on each use.
     */
    fun saveAttack(
        dmDiscordId: Long,
        templateId: Long,
        attackId: Long?,
        name: String,
        toHitModifier: Int,
        damageExpression: String
    ): SaveAttackResult {
        val template = monsterTemplateService.getById(templateId)
            ?: return SaveAttackResult.TEMPLATE_NOT_FOUND
        if (template.dmDiscordId != dmDiscordId) return SaveAttackResult.NOT_OWNER

        val trimmedName = name.trim()
        if (trimmedName.isEmpty()) return SaveAttackResult.NAME_BLANK
        if (trimmedName.length > MAX_ATTACK_NAME_LENGTH) return SaveAttackResult.NAME_TOO_LONG
        if (toHitModifier !in -MAX_ATTACK_MODIFIER..MAX_ATTACK_MODIFIER) {
            return SaveAttackResult.INVALID_MODIFIER
        }
        val trimmedDamage = damageExpression.trim()
        if (trimmedDamage.isEmpty() || DiceExpressionRoller.parseAmount(trimmedDamage) == null) {
            return SaveAttackResult.INVALID_DAMAGE
        }

        val dto = if (attackId != null && attackId > 0) {
            val existing = monsterAttackService.getById(attackId)
                ?: return SaveAttackResult.ATTACK_NOT_FOUND
            if (existing.monsterTemplateId != templateId) {
                return SaveAttackResult.ATTACK_TEMPLATE_MISMATCH
            }
            existing.apply {
                this.name = trimmedName
                this.toHitModifier = toHitModifier
                this.damageExpression = trimmedDamage
            }
        } else {
            if (monsterAttackService.countByTemplate(templateId) >= MAX_ATTACKS_PER_TEMPLATE) {
                return SaveAttackResult.TOO_MANY
            }
            MonsterAttackDto(
                monsterTemplateId = templateId,
                name = trimmedName,
                toHitModifier = toHitModifier,
                damageExpression = trimmedDamage
            )
        }
        monsterAttackService.save(dto)
        return SaveAttackResult.SAVED
    }

    fun deleteAttack(dmDiscordId: Long, templateId: Long, attackId: Long): DeleteAttackResult {
        val existing = monsterAttackService.getById(attackId)
            ?: return DeleteAttackResult.ATTACK_NOT_FOUND
        if (existing.monsterTemplateId != templateId) {
            return DeleteAttackResult.ATTACK_TEMPLATE_MISMATCH
        }
        val template = monsterTemplateService.getById(existing.monsterTemplateId)
            ?: return DeleteAttackResult.ATTACK_NOT_FOUND
        if (template.dmDiscordId != dmDiscordId) return DeleteAttackResult.NOT_OWNER
        monsterAttackService.deleteById(attackId)
        return DeleteAttackResult.DELETED
    }

    /**
     * Execute one of the current monster's attacks against [targetName]. The
     * DM drives this on the monster's turn. Rolls 1d20 + the attack's to-hit
     * modifier vs target AC (auto-hit when target.ac is null), publishes
     * ATTACK_HIT/MISS, and on hit rolls the attack's damage expression and
     * applies it via the existing [InitiativeStore.applyDamage] (publishing
     * DAMAGE_DEALT and PARTICIPANT_DEFEATED as appropriate).
     */
    fun monsterAttack(
        guildId: Long,
        requestingDiscordId: Long,
        attackId: Long,
        targetName: String
    ): MonsterAttackOutcome {
        val campaign = campaignService.getActiveCampaignForGuild(guildId)
            ?: return MonsterAttackOutcome(MonsterAttackResult.NO_ACTIVE_CAMPAIGN)
        if (campaign.dmDiscordId != requestingDiscordId) {
            return MonsterAttackOutcome(MonsterAttackResult.NOT_DM)
        }
        if (!initiativeStore.isActive(guildId)) {
            return MonsterAttackOutcome(MonsterAttackResult.NO_ACTIVE_COMBAT)
        }
        val current = initiativeStore.currentEntry(guildId)
            ?: return MonsterAttackOutcome(MonsterAttackResult.NO_ACTIVE_COMBAT)
        if (current.kind != "MONSTER") {
            return MonsterAttackOutcome(MonsterAttackResult.CURRENT_NOT_MONSTER)
        }
        val templateId = current.templateId
            ?: return MonsterAttackOutcome(MonsterAttackResult.NO_TEMPLATE)

        val attack = monsterAttackService.getById(attackId)
            ?: return MonsterAttackOutcome(MonsterAttackResult.ATTACK_NOT_FOUND)
        if (attack.monsterTemplateId != templateId) {
            return MonsterAttackOutcome(MonsterAttackResult.ATTACK_TEMPLATE_MISMATCH)
        }

        val target = initiativeStore.currentEntries(guildId).firstOrNull { it.name == targetName }
            ?: return MonsterAttackOutcome(MonsterAttackResult.TARGET_NOT_FOUND)
        if (target.name == current.name) {
            return MonsterAttackOutcome(MonsterAttackResult.CANT_TARGET_SELF)
        }
        if (target.defeated) return MonsterAttackOutcome(MonsterAttackResult.TARGET_DEFEATED)

        val raw = Random.nextInt(1, 21)
        val total = raw + attack.toHitModifier
        val hit = target.ac?.let { total >= it } ?: true
        val type = if (hit) CampaignEventType.ATTACK_HIT else CampaignEventType.ATTACK_MISS
        val requesterName = resolveMemberName(guildId, requestingDiscordId)

        sessionLog.publish(
            guildId = guildId,
            type = type,
            actorDiscordId = requestingDiscordId,
            actorName = requesterName,
            payload = mapOf(
                "attacker" to current.name,
                "target" to target.name,
                "attackName" to attack.name,
                "roll" to raw,
                "modifier" to attack.toHitModifier,
                "total" to total,
                "targetAc" to target.ac
            )
        )

        if (!hit) {
            return MonsterAttackOutcome(
                result = MonsterAttackResult.MISS,
                attacker = current.name,
                target = target.name,
                attackName = attack.name,
                rawRoll = raw,
                toHit = attack.toHitModifier,
                total = total,
                targetAc = target.ac
            )
        }

        val rolledDamage = DiceExpressionRoller.parseAmount(attack.damageExpression)
            ?: return MonsterAttackOutcome(
                result = MonsterAttackResult.INVALID_DAMAGE,
                attacker = current.name,
                target = target.name,
                attackName = attack.name
            )
        val updated = initiativeStore.applyDamage(guildId, target.name, rolledDamage.total)
            ?: return MonsterAttackOutcome(MonsterAttackResult.TARGET_NOT_FOUND)

        sessionLog.publish(
            guildId = guildId,
            type = CampaignEventType.DAMAGE_DEALT,
            actorDiscordId = requestingDiscordId,
            actorName = requesterName,
            payload = buildCombatAmountPayload(
                base = mapOf(
                    "attacker" to current.name,
                    "target" to updated.name,
                    "attackName" to attack.name,
                    "amount" to rolledDamage.total,
                    "remainingHp" to updated.currentHp,
                    "maxHp" to updated.maxHp
                ),
                parsed = rolledDamage
            )
        )
        if (updated.defeated) {
            sessionLog.publish(
                guildId = guildId,
                type = CampaignEventType.PARTICIPANT_DEFEATED,
                actorDiscordId = requestingDiscordId,
                actorName = requesterName,
                payload = mapOf("target" to updated.name)
            )
        }

        return MonsterAttackOutcome(
            result = MonsterAttackResult.HIT,
            attacker = current.name,
            target = updated.name,
            attackName = attack.name,
            rawRoll = raw,
            toHit = attack.toHitModifier,
            total = total,
            targetAc = target.ac,
            damageTotal = rolledDamage.total,
            damageExpression = rolledDamage.expression,
            damageRolls = rolledDamage.rolls,
            targetDefeated = updated.defeated
        )
    }

    fun rollInitiative(
        guildId: Long,
        requestingDiscordId: Long,
        request: InitiativeRollRequest
    ): RollInitiativeResult {
        val campaign = campaignService.getActiveCampaignForGuild(guildId)
            ?: return RollInitiativeResult.NO_ACTIVE_CAMPAIGN
        if (campaign.dmDiscordId != requestingDiscordId) return RollInitiativeResult.NOT_DM
        if (request.playerDiscordIds.isEmpty() &&
            request.templateIds.isEmpty() &&
            request.adhocMonsters.isEmpty()
        ) {
            return RollInitiativeResult.EMPTY_ROSTER
        }

        val entries = mutableListOf<InitiativeEntryData>()

        request.playerDiscordIds.forEach { playerDiscordId ->
            val modifier = userService.getUserById(playerDiscordId, guildId)?.initiativeModifier ?: 0
            val displayName = resolveMemberName(guildId, playerDiscordId) ?: "Player $playerDiscordId"
            entries += InitiativeEntryData(
                name = displayName,
                roll = rollD20() + modifier,
                kind = "PLAYER",
                modifier = modifier
            )
        }

        request.templateIds.forEach { templateId ->
            val template = monsterTemplateService.getById(templateId)
                ?: return RollInitiativeResult.TEMPLATE_NOT_FOUND
            if (template.dmDiscordId != requestingDiscordId) {
                return RollInitiativeResult.TEMPLATE_NOT_FOUND
            }
            val rolledHp = rollMonsterHp(template.hpExpression)
            entries += InitiativeEntryData(
                name = template.name,
                roll = rollD20() + template.initiativeModifier,
                kind = "MONSTER",
                modifier = template.initiativeModifier,
                maxHp = rolledHp,
                currentHp = rolledHp,
                ac = template.ac,
                templateId = template.id
            )
        }

        request.adhocMonsters.forEach { monster ->
            val cleanName = monster.name.trim().ifEmpty { "Monster" }
            val rolledHp = rollMonsterHp(monster.hpExpression)
            entries += InitiativeEntryData(
                name = cleanName,
                roll = rollD20() + monster.initiativeModifier,
                kind = "MONSTER",
                modifier = monster.initiativeModifier,
                maxHp = rolledHp,
                currentHp = rolledHp,
                ac = monster.ac
            )
        }

        val disambiguated = disambiguateDuplicates(entries)
        val sorted = disambiguated.sortedByDescending { it.roll }
        initiativeStore.seed(guildId, sorted)

        sessionLog.publish(
            guildId = guildId,
            type = CampaignEventType.INITIATIVE_ROLLED,
            actorDiscordId = requestingDiscordId,
            actorName = resolveMemberName(guildId, requestingDiscordId),
            payload = mapOf(
                "entries" to sorted.map {
                    mapOf(
                        "name" to it.name,
                        "roll" to it.roll,
                        "kind" to it.kind,
                        "modifier" to it.modifier,
                        "maxHp" to it.maxHp,
                        "currentHp" to it.currentHp,
                        "ac" to it.ac
                    )
                }
            )
        )
        return RollInitiativeResult.ROLLED
    }

    /**
     * Web combat attack. The requester must be the current-turn participant
     * (matched by display name), or the DM acting on behalf of the current
     * participant when the current entry is a MONSTER.
     *
     * Rolls 1d20 + [attackModifier] and compares against the target's AC.
     * When AC is unknown the attack always publishes as HIT (DM adjudicates).
     * Publishes ATTACK_HIT / ATTACK_MISS on the session log.
     */
    fun attack(
        guildId: Long,
        requestingDiscordId: Long,
        targetName: String,
        attackModifier: Int
    ): AttackOutcome {
        if (attackModifier !in -MAX_ATTACK_MODIFIER..MAX_ATTACK_MODIFIER) {
            return AttackOutcome(result = AttackResult.INVALID_MODIFIER)
        }
        val campaign = campaignService.getActiveCampaignForGuild(guildId)
            ?: return AttackOutcome(result = AttackResult.NO_ACTIVE_CAMPAIGN)
        if (!initiativeStore.isActive(guildId)) {
            return AttackOutcome(result = AttackResult.NO_ACTIVE_COMBAT)
        }
        val current = initiativeStore.currentEntry(guildId)
            ?: return AttackOutcome(result = AttackResult.NO_ACTIVE_COMBAT)

        val isDm = campaign.dmDiscordId == requestingDiscordId
        val requesterName = resolveMemberName(guildId, requestingDiscordId)
        val authorised = isDm || (current.kind == "PLAYER" && current.name == requesterName)
        if (!authorised) return AttackOutcome(result = AttackResult.NOT_MY_TURN)

        val target = initiativeStore.currentEntries(guildId).firstOrNull { it.name == targetName }
            ?: return AttackOutcome(result = AttackResult.TARGET_NOT_FOUND)
        if (target.name == current.name) {
            return AttackOutcome(result = AttackResult.CANT_TARGET_SELF)
        }
        if (target.defeated) return AttackOutcome(result = AttackResult.TARGET_DEFEATED)

        val raw = Random.nextInt(1, 21)
        val total = raw + attackModifier
        val hit = target.ac?.let { total >= it } ?: true
        val type = if (hit) CampaignEventType.ATTACK_HIT else CampaignEventType.ATTACK_MISS

        sessionLog.publish(
            guildId = guildId,
            type = type,
            actorDiscordId = requestingDiscordId,
            actorName = requesterName,
            payload = mapOf(
                "attacker" to current.name,
                "target" to target.name,
                "roll" to raw,
                "modifier" to attackModifier,
                "total" to total,
                "targetAc" to target.ac
            )
        )
        return AttackOutcome(
            result = if (hit) AttackResult.HIT else AttackResult.MISS,
            attacker = current.name,
            target = target.name,
            rawRoll = raw,
            modifier = attackModifier,
            total = total,
            targetAc = target.ac
        )
    }

    /**
     * Parsed combat amount: a raw integer typed into a form, or a rolled dice
     * expression like `2d6+3`. [expression] is non-null only when the caller
     * typed a dice formula; in that case [rolls] holds the individual rolled
     * faces so the session log can narrate "rolled 2d6+3 = 11".
     */
    private fun parseCombatAmount(raw: String): DiceExpressionRoller.RolledAmount? =
        DiceExpressionRoller.parseAmount(raw)

    /**
     * Applies damage to the named target in the guild's combat tracker.
     * [amountInput] accepts either a plain integer or a dice expression like
     * `2d6+3`. Publishes DAMAGE_DEALT, and if the target's HP drops to 0 or
     * below, also publishes PARTICIPANT_DEFEATED. The current-turn participant
     * (or DM) may apply damage — we don't re-check target AC here since that's
     * the role of [attack].
     */
    fun applyDamage(
        guildId: Long,
        requestingDiscordId: Long,
        targetName: String,
        amountInput: String
    ): ApplyDamageResult {
        val parsed = parseCombatAmount(amountInput) ?: return ApplyDamageResult.INVALID_AMOUNT
        val campaign = campaignService.getActiveCampaignForGuild(guildId)
            ?: return ApplyDamageResult.NO_ACTIVE_CAMPAIGN
        if (!initiativeStore.isActive(guildId)) return ApplyDamageResult.NO_ACTIVE_COMBAT
        val current = initiativeStore.currentEntry(guildId)
            ?: return ApplyDamageResult.NO_ACTIVE_COMBAT

        val isDm = campaign.dmDiscordId == requestingDiscordId
        val requesterName = resolveMemberName(guildId, requestingDiscordId)
        val authorised = isDm || (current.kind == "PLAYER" && current.name == requesterName)
        if (!authorised) return ApplyDamageResult.NOT_ATTACKER

        val updated = initiativeStore.applyDamage(guildId, targetName, parsed.total)
            ?: return ApplyDamageResult.TARGET_NOT_FOUND

        val payload = buildCombatAmountPayload(
            base = mapOf(
                "attacker" to current.name,
                "target" to updated.name,
                "amount" to parsed.total,
                "remainingHp" to updated.currentHp,
                "maxHp" to updated.maxHp
            ),
            parsed = parsed
        )
        sessionLog.publish(
            guildId = guildId,
            type = CampaignEventType.DAMAGE_DEALT,
            actorDiscordId = requestingDiscordId,
            actorName = requesterName,
            payload = payload
        )

        return if (updated.defeated) {
            sessionLog.publish(
                guildId = guildId,
                type = CampaignEventType.PARTICIPANT_DEFEATED,
                actorDiscordId = requestingDiscordId,
                actorName = requesterName,
                payload = mapOf("target" to updated.name)
            )
            ApplyDamageResult.DEFEATED
        } else {
            ApplyDamageResult.APPLIED
        }
    }

    /**
     * Restores HP on the named target. Mirrors [applyDamage]: accepts an integer
     * or a dice expression, requires the caller to be the current-turn
     * participant or DM, and clamps the result to the target's maxHp.
     * Publishes HEAL_APPLIED with `revived=true` when the heal brings a
     * previously-defeated target above 0 HP.
     */
    fun applyHeal(
        guildId: Long,
        requestingDiscordId: Long,
        targetName: String,
        amountInput: String
    ): ApplyHealResult {
        val parsed = parseCombatAmount(amountInput) ?: return ApplyHealResult.INVALID_AMOUNT
        val campaign = campaignService.getActiveCampaignForGuild(guildId)
            ?: return ApplyHealResult.NO_ACTIVE_CAMPAIGN
        if (!initiativeStore.isActive(guildId)) return ApplyHealResult.NO_ACTIVE_COMBAT
        val current = initiativeStore.currentEntry(guildId)
            ?: return ApplyHealResult.NO_ACTIVE_COMBAT

        val isDm = campaign.dmDiscordId == requestingDiscordId
        val requesterName = resolveMemberName(guildId, requestingDiscordId)
        val authorised = isDm || (current.kind == "PLAYER" && current.name == requesterName)
        if (!authorised) return ApplyHealResult.NOT_ATTACKER

        val target = initiativeStore.currentEntries(guildId).firstOrNull { it.name == targetName }
            ?: return ApplyHealResult.TARGET_NOT_FOUND
        if (target.maxHp == null) return ApplyHealResult.TARGET_HAS_NO_HP
        val wasDefeated = target.defeated

        val updated = initiativeStore.applyHeal(guildId, targetName, parsed.total)
            ?: return ApplyHealResult.TARGET_NOT_FOUND

        val revived = wasDefeated && !updated.defeated
        val payload = buildCombatAmountPayload(
            base = mapOf(
                "healer" to current.name,
                "target" to updated.name,
                "amount" to parsed.total,
                "remainingHp" to updated.currentHp,
                "maxHp" to updated.maxHp,
                "revived" to revived
            ),
            parsed = parsed
        )
        sessionLog.publish(
            guildId = guildId,
            type = CampaignEventType.HEAL_APPLIED,
            actorDiscordId = requestingDiscordId,
            actorName = requesterName,
            payload = payload
        )
        return if (revived) ApplyHealResult.REVIVED else ApplyHealResult.APPLIED
    }

    private fun buildCombatAmountPayload(
        base: Map<String, Any?>,
        parsed: DiceExpressionRoller.RolledAmount
    ): Map<String, Any?> {
        if (parsed.expression == null) return base
        return base + mapOf(
            "expression" to parsed.expression,
            "rolls" to parsed.rolls
        )
    }

    /**
     * When the same name appears more than once in the roster (e.g. two Goblins
     * from a template picked with quantity = 2, or two ad-hoc rows named
     * "Kobold"), suffix each occurrence with a 1-based index so the turn table
     * can tell them apart. Names that appear only once are left untouched.
     */
    private fun disambiguateDuplicates(entries: List<InitiativeEntryData>): List<InitiativeEntryData> {
        val counts = entries.groupingBy { it.name }.eachCount()
        val seen = mutableMapOf<String, Int>()
        return entries.map { entry ->
            if ((counts[entry.name] ?: 0) <= 1) entry
            else {
                val idx = (seen[entry.name] ?: 0) + 1
                seen[entry.name] = idx
                entry.copy(name = "${entry.name} #$idx")
            }
        }
    }

    private fun rollD20(): Int = Random.nextInt(1, 21)

    /**
     * Roll a monster's HP from its template/ad-hoc [hpExpression]. Accepts a
     * literal integer (`"45"`) or dice expression (`"3d20+30"`); each call
     * produces an independent roll, so two instances of the same template get
     * different totals. Returns null when the input is null/blank, or when an
     * already-stored expression no longer parses (logged + treated as untracked HP).
     */
    private fun rollMonsterHp(hpExpression: String?): Int? {
        val cleaned = hpExpression?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val rolled = DiceExpressionRoller.parseAmount(cleaned)
        if (rolled == null) {
            logger.warn("Unparseable monster HP expression: $cleaned")
            return null
        }
        return rolled.total
    }

    private fun loadCharacterSummary(characterId: Long): CharacterSummary? {
        val json = characterSheetService.getSheet(characterId) ?: return null
        return runCatching { buildCharacterSummary(objectMapper.readTree(json)) }.getOrNull()
    }

    private fun buildCharacterSummary(root: JsonNode): CharacterSummary {
        val name = root.path("name").textOrNull()

        val race = root.path("race")
        val raceName = race.path("fullName").textOrNull() ?: race.path("baseName").textOrNull()

        val classesNode = root.path("classes").takeIf { it.isArray && it.size() > 0 }
        val totalLevel = classesNode?.sumOf { it.path("level").asInt(0) }?.takeIf { it > 0 }
        val classesString = classesNode?.joinToString(", ") { entry ->
            val base = entry.path("definition").path("name").textOrNull() ?: "?"
            val sub = entry.path("subclassDefinition").path("name").textOrNull()
            if (sub != null) "$base ($sub)" else base
        }?.takeIf { it.isNotBlank() }

        return CharacterSummary(name, raceName, classesString, totalLevel)
    }

    private fun JsonNode.textOrNull(): String? =
        if (isTextual) textValue().takeIf { !it.isNullOrBlank() } else null

    data class CharacterSummary(
        val name: String?,
        val raceName: String?,
        val classesString: String?,
        val totalLevel: Int?
    )
}
