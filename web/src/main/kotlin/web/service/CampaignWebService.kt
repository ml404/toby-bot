package web.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import common.events.CampaignEventType
import common.helpers.parseDndBeyondCharacterId
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
    val kind: String?
)

data class MonsterTemplateView(
    val id: Long,
    val name: String,
    val initiativeModifier: Int,
    val maxHp: Int?,
    val ac: Int?
)

data class AdhocMonster(val name: String, val initiativeModifier: Int)

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
enum class SaveTemplateResult { SAVED, NAME_BLANK, NAME_TOO_LONG, NOT_FOUND, NOT_OWNER }
enum class DeleteTemplateResult { DELETED, NOT_FOUND, NOT_OWNER }
enum class RollInitiativeResult { ROLLED, NO_ACTIVE_CAMPAIGN, NOT_DM, EMPTY_ROSTER, TEMPLATE_NOT_FOUND }

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
    private val initiativeStore: InitiativeStore,
    private val sessionLog: SessionLogPublisher,
    private val jda: JDA
) {

    private val objectMapper = ObjectMapper()
    private val payloadTypeRef =
        object : com.fasterxml.jackson.core.type.TypeReference<Map<String, Any?>>() {}

    companion object {
        const val MAX_NOTE_BODY_LENGTH = 2000
        const val MAX_NARRATE_BODY_LENGTH = 1000
        const val DEFAULT_EVENT_LIMIT = 100
        const val MAX_TEMPLATE_NAME_LENGTH = 100
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

        val notes = sessionNoteService.getNotesForCampaign(campaign.id).map { note ->
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

        val recentEvents = campaignEventService
            .listRecent(campaign.id, DEFAULT_EVENT_LIMIT)
            .map(::toSessionEventView)

        val initiativeState = if (initiativeStore.isActive(guildId)) {
            InitiativeStateView(
                entries = initiativeStore.currentEntries(guildId).map {
                    RolledEntryView(it.name, it.roll, it.kind)
                },
                currentIndex = initiativeStore.currentIndex(guildId)
            )
        } else null

        val monsterLibrary = if (isDm) {
            monsterTemplateService.listByDm(requestingDiscordId).map(::toMonsterTemplateView)
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

    private fun toMonsterTemplateView(dto: MonsterTemplateDto): MonsterTemplateView =
        MonsterTemplateView(
            id = dto.id,
            name = dto.name,
            initiativeModifier = dto.initiativeModifier,
            maxHp = dto.maxHp,
            ac = dto.ac
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

    fun listTemplatesForDm(dmDiscordId: Long): List<MonsterTemplateView> =
        monsterTemplateService.listByDm(dmDiscordId).map(::toMonsterTemplateView)

    fun saveTemplate(
        dmDiscordId: Long,
        id: Long?,
        name: String,
        initiativeModifier: Int,
        maxHp: Int?,
        ac: Int?
    ): SaveTemplateResult {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return SaveTemplateResult.NAME_BLANK
        if (trimmed.length > MAX_TEMPLATE_NAME_LENGTH) return SaveTemplateResult.NAME_TOO_LONG

        val dto = if (id != null && id > 0) {
            val existing = monsterTemplateService.getById(id) ?: return SaveTemplateResult.NOT_FOUND
            if (existing.dmDiscordId != dmDiscordId) return SaveTemplateResult.NOT_OWNER
            existing.apply {
                this.name = trimmed
                this.initiativeModifier = initiativeModifier
                this.maxHp = maxHp
                this.ac = ac
            }
        } else {
            MonsterTemplateDto(
                dmDiscordId = dmDiscordId,
                name = trimmed,
                initiativeModifier = initiativeModifier,
                maxHp = maxHp,
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
            entries += InitiativeEntryData(
                name = template.name,
                roll = rollD20() + template.initiativeModifier,
                kind = "MONSTER",
                modifier = template.initiativeModifier
            )
        }

        request.adhocMonsters.forEach { monster ->
            val cleanName = monster.name.trim().ifEmpty { "Monster" }
            entries += InitiativeEntryData(
                name = cleanName,
                roll = rollD20() + monster.initiativeModifier,
                kind = "MONSTER",
                modifier = monster.initiativeModifier
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
                        "modifier" to it.modifier
                    )
                }
            )
        )
        return RollInitiativeResult.ROLLED
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
