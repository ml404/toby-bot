package web.controller

import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient
import org.springframework.security.oauth2.client.annotation.RegisteredOAuth2AuthorizedClient
import org.springframework.security.oauth2.core.user.OAuth2User
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.*
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import org.springframework.web.servlet.mvc.support.RedirectAttributes
import web.service.AddNoteResult
import web.service.AdhocMonster
import web.service.AnnotateRollResult
import web.service.ApplyDamageResult
import web.service.ApplyHealResult
import web.service.AttackResult
import web.service.CampaignEventBroadcaster
import web.service.CampaignWebService
import web.service.DeleteAttackResult
import web.service.DeleteEncounterEntryResult
import web.service.DeleteEncounterResult
import web.service.DeleteNoteResult
import web.service.DeleteTemplateResult
import web.service.EncounterView
import web.service.MonsterAttackResult
import web.service.EndResult
import web.service.InitiativeRollRequest
import web.service.JoinResult
import web.service.KickResult
import web.service.LeaveResult
import web.service.MonsterTemplateView
import web.service.NarrateResult
import web.service.ReorderEncounterEntriesResult
import web.service.RollDiceResult
import web.service.RollEncounterResult
import web.service.RollInitiativeResult
import web.service.SaveAttackResult
import web.service.SaveEncounterEntryResult
import web.service.SaveEncounterResult
import web.service.SaveTemplateResult
import web.service.SessionEventView
import web.service.SetAliveResult
import web.service.SetCharacterResult
import web.util.WebGuildAccess
import web.util.discordIdOrNull
import web.util.displayName

@Controller
@RequestMapping("/dnd")
class CampaignController(
    private val campaignWebService: CampaignWebService,
    private val campaignEventBroadcaster: CampaignEventBroadcaster
) {

    @GetMapping("/campaign")
    fun campaignList(
        @RegisteredOAuth2AuthorizedClient("discord") client: OAuth2AuthorizedClient,
        @AuthenticationPrincipal user: OAuth2User,
        model: Model
    ): String {
        val accessToken = client.accessToken.tokenValue
        val guilds = campaignWebService.getMutualGuildsWithCampaigns(accessToken)

        model.addAttribute("guilds", guilds)
        model.addAttribute("username", user.displayName())

        return "campaign"
    }

    @GetMapping("/campaign/{guildId}")
    fun campaignDetail(
        @PathVariable guildId: Long,
        @AuthenticationPrincipal user: OAuth2User,
        model: Model,
        ra: RedirectAttributes
    ): String = WebGuildAccess.requireSignedInForPage(user, "/dnd/campaign") { discordId ->
        val guildName = campaignWebService.getGuildName(guildId) ?: run {
            ra.addFlashAttribute("error", "Bot is not in that server.")
            return@requireSignedInForPage "redirect:/dnd/campaign"
        }

        val campaignDetail = campaignWebService.getCampaignDetail(guildId, discordId)

        model.addAttribute("guildId", guildId)
        model.addAttribute("guildName", guildName)
        model.addAttribute("campaign", campaignDetail?.campaign)
        model.addAttribute("players", campaignDetail?.players ?: emptyList<Any>())
        model.addAttribute("dmName", campaignDetail?.dmName)
        model.addAttribute("isUserDm", campaignDetail?.isDm(discordId) ?: false)
        model.addAttribute("isUserPlayer", campaignDetail?.isCurrentUserPlayer ?: false)
        model.addAttribute("currentUserCharacterId", campaignDetail?.currentUserCharacterId)
        model.addAttribute("notes", campaignDetail?.notes ?: emptyList<Any>())
        model.addAttribute("recentEvents", campaignDetail?.recentEvents ?: emptyList<Any>())
        model.addAttribute("initiativeState", campaignDetail?.initiativeState)
        model.addAttribute("monsterLibrary", campaignDetail?.monsterLibrary ?: emptyList<Any>())
        model.addAttribute("encounters", campaignDetail?.encounters ?: emptyList<Any>())
        model.addAttribute("freshEventIds", campaignDetail?.freshEventIds ?: emptyList<Long>())
        model.addAttribute("username", user.displayName())

        "campaignDetail"
    }

    @GetMapping("/campaign/{guildId}/events")
    @ResponseBody
    fun listEvents(
        @PathVariable guildId: Long,
        @RequestParam(name = "since", required = false) since: Long?,
        @RequestParam(name = "limit", required = false, defaultValue = "100") limit: Int,
        @AuthenticationPrincipal user: OAuth2User
    ): List<SessionEventView> {
        user.discordIdOrNull() ?: return emptyList()
        return campaignWebService.listRecentEvents(guildId, since, limit)
    }

    @GetMapping("/campaign/{guildId}/events/stream", produces = ["text/event-stream"])
    @ResponseBody
    fun streamEvents(
        @PathVariable guildId: Long,
        @AuthenticationPrincipal user: OAuth2User
    ): SseEmitter {
        val emitter = SseEmitter(CampaignEventBroadcaster.EMITTER_TIMEOUT_MS)
        if (user.discordIdOrNull() == null) {
            emitter.complete()
            return emitter
        }
        val campaignId = campaignWebService.getActiveCampaignId(guildId)
        if (campaignId == null) {
            emitter.complete()
            return emitter
        }
        return campaignEventBroadcaster.subscribe(campaignId)
    }

    @PostMapping("/campaign/{guildId}/create")
    fun createCampaign(
        @PathVariable guildId: Long,
        @RequestParam name: String,
        @AuthenticationPrincipal user: OAuth2User,
        ra: RedirectAttributes
    ): String = WebGuildAccess.requireSignedInForPage(user, "/dnd/campaign") { discordId ->
        // Special-cased vs. the helper because "Bot not in server" needs to
        // bounce to the campaign list, not the (non-existent) detail page.
        campaignWebService.getGuildName(guildId) ?: run {
            ra.addFlashAttribute("error", "Bot is not in that server.")
            return@requireSignedInForPage "redirect:/dnd/campaign"
        }
        if (campaignWebService.createCampaign(guildId, discordId, name) == null) {
            ra.addFlashAttribute("error", "A campaign is already active in this server.")
        }
        "redirect:/dnd/campaign/$guildId"
    }

    @PostMapping("/campaign/{guildId}/join")
    fun joinCampaign(
        @PathVariable guildId: Long,
        @AuthenticationPrincipal user: OAuth2User,
        ra: RedirectAttributes
    ): String = withCampaignAuth(user, guildId, ra) { discordId ->
        when (campaignWebService.joinCampaign(guildId, discordId)) {
            JoinResult.JOINED -> null
            JoinResult.NO_ACTIVE_CAMPAIGN -> "No active campaign in this server."
            JoinResult.ALREADY_JOINED -> "You are already in this campaign."
            JoinResult.IS_DM -> "You are the DM and cannot join as a player."
        }
    }

    @PostMapping("/campaign/{guildId}/leave")
    fun leaveCampaign(
        @PathVariable guildId: Long,
        @AuthenticationPrincipal user: OAuth2User,
        ra: RedirectAttributes
    ): String = withCampaignAuth(user, guildId, ra) { discordId ->
        when (campaignWebService.leaveCampaign(guildId, discordId)) {
            LeaveResult.LEFT -> null
            LeaveResult.NO_ACTIVE_CAMPAIGN -> "No active campaign in this server."
            LeaveResult.NOT_A_PLAYER -> "You are not in this campaign."
        }
    }

    @PostMapping("/campaign/{guildId}/character")
    fun setLinkedCharacter(
        @PathVariable guildId: Long,
        @RequestParam(name = "characterInput", required = false, defaultValue = "") characterInput: String,
        @AuthenticationPrincipal user: OAuth2User,
        ra: RedirectAttributes
    ): String = withCampaignAuth(user, guildId, ra) { discordId ->
        when (campaignWebService.setLinkedCharacter(guildId, discordId, characterInput)) {
            SetCharacterResult.UPDATED, SetCharacterResult.CLEARED -> null
            SetCharacterResult.INVALID -> "Could not extract a valid character ID. Paste a D&D Beyond URL or numeric ID."
        }
    }

    @PostMapping("/campaign/{guildId}/end")
    fun endCampaign(
        @PathVariable guildId: Long,
        @AuthenticationPrincipal user: OAuth2User,
        ra: RedirectAttributes
    ): String = withCampaignAuth(user, guildId, ra) { discordId ->
        when (campaignWebService.endCampaign(guildId, discordId)) {
            EndResult.ENDED -> null
            EndResult.NO_ACTIVE_CAMPAIGN -> "No active campaign in this server."
            EndResult.NOT_DM -> "Only the Dungeon Master can end the campaign."
        }
    }

    @PostMapping("/campaign/{guildId}/players/{targetDiscordId}/kick")
    fun kickPlayer(
        @PathVariable guildId: Long,
        @PathVariable targetDiscordId: Long,
        @AuthenticationPrincipal user: OAuth2User,
        ra: RedirectAttributes
    ): String = withCampaignAuth(user, guildId, ra) { discordId ->
        when (campaignWebService.kickPlayer(guildId, discordId, targetDiscordId)) {
            KickResult.KICKED -> null
            KickResult.NO_ACTIVE_CAMPAIGN -> "No active campaign in this server."
            KickResult.NOT_DM -> "Only the Dungeon Master can kick players."
            KickResult.NOT_A_PLAYER -> "That user isn't in the campaign."
            KickResult.CANNOT_KICK_DM -> "The DM can't be kicked."
        }
    }

    @PostMapping("/campaign/{guildId}/players/{targetDiscordId}/alive")
    fun setPlayerAlive(
        @PathVariable guildId: Long,
        @PathVariable targetDiscordId: Long,
        @RequestParam alive: Boolean,
        @AuthenticationPrincipal user: OAuth2User,
        ra: RedirectAttributes
    ): String = withCampaignAuth(user, guildId, ra) { discordId ->
        when (campaignWebService.setPlayerAlive(guildId, discordId, targetDiscordId, alive)) {
            SetAliveResult.UPDATED -> null
            SetAliveResult.NO_ACTIVE_CAMPAIGN -> "No active campaign in this server."
            SetAliveResult.NOT_DM -> "Only the Dungeon Master can change player status."
            SetAliveResult.NOT_A_PLAYER -> "That user isn't in the campaign."
        }
    }

    @PostMapping("/campaign/{guildId}/notes")
    fun addNote(
        @PathVariable guildId: Long,
        @RequestParam body: String,
        @AuthenticationPrincipal user: OAuth2User,
        ra: RedirectAttributes
    ): String = withCampaignAuth(user, guildId, ra) { discordId ->
        when (campaignWebService.addNote(guildId, discordId, body)) {
            AddNoteResult.ADDED -> null
            AddNoteResult.NO_ACTIVE_CAMPAIGN -> "No active campaign in this server."
            AddNoteResult.NOT_PARTICIPANT -> "Only the DM and campaign players can add notes."
            AddNoteResult.EMPTY_BODY -> "Note body can't be empty."
            AddNoteResult.BODY_TOO_LONG -> "Note is too long (max 2000 characters)."
        }
    }

    @PostMapping("/campaign/{guildId}/notes/{noteId}/delete")
    fun deleteNote(
        @PathVariable guildId: Long,
        @PathVariable noteId: Long,
        @AuthenticationPrincipal user: OAuth2User,
        ra: RedirectAttributes
    ): String = withCampaignAuth(user, guildId, ra) { discordId ->
        when (campaignWebService.deleteNote(guildId, discordId, noteId)) {
            DeleteNoteResult.DELETED -> null
            DeleteNoteResult.NO_ACTIVE_CAMPAIGN -> "No active campaign in this server."
            DeleteNoteResult.NOT_FOUND -> "That note doesn't exist."
            DeleteNoteResult.NOT_ALLOWED -> "You can only delete your own notes (or any note if you're the DM)."
        }
    }

    @PostMapping("/campaign/{guildId}/events/{eventId}/annotate")
    fun annotateRoll(
        @PathVariable guildId: Long,
        @PathVariable eventId: Long,
        @RequestParam kind: String,
        @RequestParam(name = "target", required = false) target: String?,
        @AuthenticationPrincipal user: OAuth2User,
        ra: RedirectAttributes
    ): String = withCampaignAuth(user, guildId, ra) { discordId ->
        when (campaignWebService.annotateRoll(guildId, discordId, eventId, kind, target)) {
            AnnotateRollResult.ANNOTATED -> null
            AnnotateRollResult.NO_ACTIVE_CAMPAIGN -> "No active campaign in this server."
            AnnotateRollResult.NOT_DM -> "Only the Dungeon Master can annotate rolls."
            AnnotateRollResult.NOT_FOUND -> "That roll doesn't exist in this campaign."
            AnnotateRollResult.NOT_A_ROLL -> "You can only mark Hit/Miss on a roll."
            AnnotateRollResult.INVALID_KIND -> "Annotation kind must be HIT or MISS."
        }
    }

    @PostMapping("/campaign/{guildId}/narrate")
    fun narrate(
        @PathVariable guildId: Long,
        @RequestParam body: String,
        @AuthenticationPrincipal user: OAuth2User,
        ra: RedirectAttributes
    ): String = withCampaignAuth(user, guildId, ra) { discordId ->
        when (campaignWebService.narrate(guildId, discordId, body)) {
            NarrateResult.NARRATED -> null
            NarrateResult.NO_ACTIVE_CAMPAIGN -> "No active campaign in this server."
            NarrateResult.NOT_DM -> "Only the Dungeon Master can narrate."
            NarrateResult.EMPTY_BODY -> "Narration can't be empty."
            NarrateResult.BODY_TOO_LONG -> "Narration is too long (max ${CampaignWebService.MAX_NARRATE_BODY_LENGTH} characters)."
        }
    }

    @GetMapping("/monsters/templates")
    @ResponseBody
    fun listMonsterTemplates(
        @AuthenticationPrincipal user: OAuth2User
    ): List<MonsterTemplateView> {
        val discordId = user.discordIdOrNull() ?: return emptyList()
        return campaignWebService.listTemplatesForDm(discordId)
    }

    @PostMapping("/campaign/{guildId}/monsters/templates")
    fun saveMonsterTemplate(
        @PathVariable guildId: Long,
        @RequestParam(name = "id", required = false) id: Long?,
        @RequestParam("name") name: String,
        @RequestParam("initiativeModifier") initiativeModifier: Int,
        @RequestParam(name = "hpExpression", required = false) hpExpression: String?,
        @RequestParam(name = "ac", required = false) ac: Int?,
        @AuthenticationPrincipal user: OAuth2User,
        ra: RedirectAttributes
    ): String = withCampaignAuth(user, guildId, ra) { discordId ->
        when (campaignWebService.saveTemplate(discordId, id, name, initiativeModifier, hpExpression, ac)) {
            SaveTemplateResult.SAVED -> null
            SaveTemplateResult.NAME_BLANK -> "Monster name can't be empty."
            SaveTemplateResult.NAME_TOO_LONG ->
                "Monster name is too long (max ${CampaignWebService.MAX_TEMPLATE_NAME_LENGTH} characters)."
            SaveTemplateResult.INVALID_HP -> "HP must be a number or a dice expression like '3d20+30'."
            SaveTemplateResult.NOT_FOUND -> "That template doesn't exist."
            SaveTemplateResult.NOT_OWNER -> "You can only edit your own templates."
        }
    }

    @PostMapping("/campaign/{guildId}/monsters/templates/{templateId}/delete")
    fun deleteMonsterTemplate(
        @PathVariable guildId: Long,
        @PathVariable templateId: Long,
        @AuthenticationPrincipal user: OAuth2User,
        ra: RedirectAttributes
    ): String = withCampaignAuth(user, guildId, ra) { discordId ->
        when (campaignWebService.deleteTemplate(discordId, templateId)) {
            DeleteTemplateResult.DELETED -> null
            DeleteTemplateResult.NOT_FOUND -> "That template doesn't exist."
            DeleteTemplateResult.NOT_OWNER -> "You can only delete your own templates."
        }
    }

    @PostMapping("/campaign/{guildId}/monsters/templates/{templateId}/attacks")
    fun saveMonsterAttack(
        @PathVariable guildId: Long,
        @PathVariable templateId: Long,
        @RequestParam(name = "id", required = false) attackId: Long?,
        @RequestParam("name") name: String,
        @RequestParam(name = "toHitModifier", defaultValue = "0") toHitModifier: Int,
        @RequestParam("damageExpression") damageExpression: String,
        @AuthenticationPrincipal user: OAuth2User,
        ra: RedirectAttributes
    ): String = withCampaignAuth(user, guildId, ra) { discordId ->
        when (campaignWebService.saveAttack(discordId, templateId, attackId, name, toHitModifier, damageExpression)) {
            SaveAttackResult.SAVED -> null
            SaveAttackResult.NAME_BLANK -> "Attack name can't be empty."
            SaveAttackResult.NAME_TOO_LONG ->
                "Attack name is too long (max ${CampaignWebService.MAX_ATTACK_NAME_LENGTH} characters)."
            SaveAttackResult.INVALID_MODIFIER ->
                "To-hit modifier must be between -${CampaignWebService.MAX_ATTACK_MODIFIER} and ${CampaignWebService.MAX_ATTACK_MODIFIER}."
            SaveAttackResult.INVALID_DAMAGE -> "Damage must be a number or a dice expression like '2d6+3'."
            SaveAttackResult.TOO_MANY ->
                "This monster already has the maximum of ${CampaignWebService.MAX_ATTACKS_PER_TEMPLATE} attacks."
            SaveAttackResult.TEMPLATE_NOT_FOUND -> "That monster template doesn't exist."
            SaveAttackResult.NOT_OWNER -> "You can only edit your own monsters."
            SaveAttackResult.ATTACK_NOT_FOUND -> "That attack doesn't exist."
            SaveAttackResult.ATTACK_TEMPLATE_MISMATCH -> "That attack belongs to a different monster."
        }
    }

    @PostMapping("/campaign/{guildId}/monsters/templates/{templateId}/attacks/{attackId}/delete")
    fun deleteMonsterAttack(
        @PathVariable guildId: Long,
        @PathVariable templateId: Long,
        @PathVariable attackId: Long,
        @AuthenticationPrincipal user: OAuth2User,
        ra: RedirectAttributes
    ): String = withCampaignAuth(user, guildId, ra) { discordId ->
        when (campaignWebService.deleteAttack(discordId, templateId, attackId)) {
            DeleteAttackResult.DELETED -> null
            DeleteAttackResult.ATTACK_NOT_FOUND -> "That attack doesn't exist."
            DeleteAttackResult.NOT_OWNER -> "You can only delete your own monsters' attacks."
            DeleteAttackResult.ATTACK_TEMPLATE_MISMATCH -> "That attack belongs to a different monster."
        }
    }

    // ----------------------------------------------------------------------
    // Encounter Library endpoints. Form-post + redirect for mutations so the
    // existing flash-message pattern gives the DM inline feedback; JSON GET
    // for the list (used by encounters.js when pre-filling the composer)
    // and a JSON POST for reorder so drag-drop doesn't full-reload the page.
    // ----------------------------------------------------------------------

    @GetMapping("/encounters")
    @ResponseBody
    fun listEncounters(
        @AuthenticationPrincipal user: OAuth2User
    ): List<EncounterView> {
        val discordId = user.discordIdOrNull() ?: return emptyList()
        return campaignWebService.listEncountersForDm(discordId)
    }

    @GetMapping("/encounters/{encounterId}")
    @ResponseBody
    fun getEncounter(
        @PathVariable encounterId: Long,
        @AuthenticationPrincipal user: OAuth2User
    ): EncounterView? {
        val discordId = user.discordIdOrNull() ?: return null
        return campaignWebService.getEncounterForDm(discordId, encounterId)
    }

    @PostMapping("/campaign/{guildId}/encounters")
    fun saveEncounter(
        @PathVariable guildId: Long,
        @RequestParam(name = "id", required = false) id: Long?,
        @RequestParam("name") name: String,
        @RequestParam(name = "notes", required = false) notes: String?,
        @AuthenticationPrincipal user: OAuth2User,
        ra: RedirectAttributes
    ): String = withCampaignAuth(user, guildId, ra) { discordId ->
        when (campaignWebService.saveEncounter(discordId, id, name, notes)) {
            SaveEncounterResult.SAVED -> null
            SaveEncounterResult.NAME_BLANK -> "Encounter name can't be empty."
            SaveEncounterResult.NAME_TOO_LONG ->
                "Encounter name is too long (max ${CampaignWebService.MAX_ENCOUNTER_NAME_LENGTH} characters)."
            SaveEncounterResult.NOTES_TOO_LONG ->
                "Encounter notes are too long (max ${CampaignWebService.MAX_ENCOUNTER_NOTES_LENGTH} characters)."
            SaveEncounterResult.NOT_FOUND -> "That encounter doesn't exist."
            SaveEncounterResult.NOT_OWNER -> "You can only edit your own encounters."
        }
    }

    @PostMapping("/campaign/{guildId}/encounters/{encounterId}/delete")
    fun deleteEncounter(
        @PathVariable guildId: Long,
        @PathVariable encounterId: Long,
        @AuthenticationPrincipal user: OAuth2User,
        ra: RedirectAttributes
    ): String = withCampaignAuth(user, guildId, ra) { discordId ->
        when (campaignWebService.deleteEncounter(discordId, encounterId)) {
            DeleteEncounterResult.DELETED -> null
            DeleteEncounterResult.NOT_FOUND -> "That encounter doesn't exist."
            DeleteEncounterResult.NOT_OWNER -> "You can only delete your own encounters."
        }
    }

    @PostMapping("/campaign/{guildId}/encounters/{encounterId}/entries")
    fun saveEncounterEntry(
        @PathVariable guildId: Long,
        @PathVariable encounterId: Long,
        @RequestParam(name = "id", required = false) entryId: Long?,
        @RequestParam(name = "monsterTemplateId", required = false) monsterTemplateId: Long?,
        @RequestParam(name = "quantity", defaultValue = "1") quantity: Int,
        @RequestParam(name = "adhocName", required = false) adhocName: String?,
        @RequestParam(name = "adhocInitiativeModifier", defaultValue = "0") adhocInitiativeModifier: Int,
        @RequestParam(name = "adhocHpExpression", required = false) adhocHpExpression: String?,
        @RequestParam(name = "adhocAc", required = false) adhocAc: Int?,
        @AuthenticationPrincipal user: OAuth2User,
        ra: RedirectAttributes
    ): String = withCampaignAuth(user, guildId, ra) { discordId ->
        when (campaignWebService.saveEncounterEntry(
            discordId, encounterId, entryId,
            monsterTemplateId, quantity,
            adhocName, adhocInitiativeModifier, adhocHpExpression, adhocAc
        )) {
            SaveEncounterEntryResult.SAVED -> null
            SaveEncounterEntryResult.ENCOUNTER_NOT_FOUND -> "That encounter doesn't exist."
            SaveEncounterEntryResult.NOT_OWNER -> "You can only edit your own encounters."
            SaveEncounterEntryResult.TEMPLATE_NOT_FOUND -> "That monster template doesn't exist."
            SaveEncounterEntryResult.TEMPLATE_NOT_OWNED -> "You can only use your own monster templates."
            SaveEncounterEntryResult.NAME_TOO_LONG ->
                "Ad-hoc monster name is too long (max ${CampaignWebService.MAX_ENCOUNTER_NAME_LENGTH} characters)."
            SaveEncounterEntryResult.INVALID_HP -> "HP must be a number or a dice expression like '3d20+30'."
            SaveEncounterEntryResult.INVALID_QUANTITY ->
                "Quantity must be between 1 and ${CampaignWebService.MAX_QUANTITY_PER_ENTRY}."
            SaveEncounterEntryResult.TOO_MANY_ENTRIES ->
                "This encounter already has the maximum of ${CampaignWebService.MAX_ENTRIES_PER_ENCOUNTER} rows."
            SaveEncounterEntryResult.MISSING_SOURCE -> "Pick a monster from the library or fill in an ad-hoc name."
            SaveEncounterEntryResult.ENTRY_NOT_FOUND -> "That entry doesn't exist."
            SaveEncounterEntryResult.ENTRY_ENCOUNTER_MISMATCH -> "That entry belongs to a different encounter."
        }
    }

    @PostMapping("/campaign/{guildId}/encounters/{encounterId}/entries/{entryId}/delete")
    fun deleteEncounterEntry(
        @PathVariable guildId: Long,
        @PathVariable encounterId: Long,
        @PathVariable entryId: Long,
        @AuthenticationPrincipal user: OAuth2User,
        ra: RedirectAttributes
    ): String = withCampaignAuth(user, guildId, ra) { discordId ->
        when (campaignWebService.deleteEncounterEntry(discordId, encounterId, entryId)) {
            DeleteEncounterEntryResult.DELETED -> null
            DeleteEncounterEntryResult.ENCOUNTER_NOT_FOUND -> "That encounter doesn't exist."
            DeleteEncounterEntryResult.NOT_OWNER -> "You can only edit your own encounters."
            DeleteEncounterEntryResult.ENTRY_NOT_FOUND -> "That entry doesn't exist."
            DeleteEncounterEntryResult.ENTRY_ENCOUNTER_MISMATCH -> "That entry belongs to a different encounter."
        }
    }

    data class ReorderEntriesBody(val orderedIds: List<Long> = emptyList())

    @PostMapping(
        "/campaign/{guildId}/encounters/{encounterId}/entries/reorder",
        consumes = ["application/json"]
    )
    @ResponseBody
    fun reorderEncounterEntries(
        @PathVariable guildId: Long,
        @PathVariable encounterId: Long,
        @RequestBody body: ReorderEntriesBody,
        @AuthenticationPrincipal user: OAuth2User
    ): Map<String, Any?> {
        val discordId = user.discordIdOrNull()
            ?: return mapOf("ok" to false, "error" to "Not authenticated.")

        return when (campaignWebService.reorderEncounterEntries(discordId, encounterId, body.orderedIds)) {
            ReorderEncounterEntriesResult.REORDERED -> mapOf("ok" to true)
            ReorderEncounterEntriesResult.ENCOUNTER_NOT_FOUND ->
                mapOf("ok" to false, "error" to "That encounter doesn't exist.")
            ReorderEncounterEntriesResult.NOT_OWNER ->
                mapOf("ok" to false, "error" to "You can only edit your own encounters.")
            ReorderEncounterEntriesResult.ENTRY_MISMATCH ->
                mapOf("ok" to false, "error" to "Entry list doesn't match this encounter.")
        }
    }

    @PostMapping("/campaign/{guildId}/encounters/{encounterId}/roll")
    fun rollEncounter(
        @PathVariable guildId: Long,
        @PathVariable encounterId: Long,
        @RequestParam(name = "playerDiscordIds", required = false) playerDiscordIds: List<Long>?,
        @AuthenticationPrincipal user: OAuth2User,
        ra: RedirectAttributes
    ): String = withCampaignAuth(user, guildId, ra) { discordId ->
        when (campaignWebService.rollEncounter(guildId, discordId, encounterId, playerDiscordIds.orEmpty())) {
            RollEncounterResult.ROLLED -> null
            RollEncounterResult.ENCOUNTER_NOT_FOUND -> "That encounter doesn't exist."
            RollEncounterResult.NOT_OWNER -> "You can only roll your own encounters."
            RollEncounterResult.NO_ACTIVE_CAMPAIGN -> "No active campaign in this server."
            RollEncounterResult.NOT_DM -> "Only the Dungeon Master can roll an encounter."
            RollEncounterResult.EMPTY_ROSTER -> "This encounter has no monsters yet."
            RollEncounterResult.TEMPLATE_NOT_FOUND -> "One of the encounter's monster templates couldn't be found."
        }
    }

    @PostMapping("/campaign/{guildId}/combat/monster-attack")
    fun monsterAttack(
        @PathVariable guildId: Long,
        @RequestParam("attackId") attackId: Long,
        @RequestParam("targetName") targetName: String,
        @AuthenticationPrincipal user: OAuth2User,
        ra: RedirectAttributes
    ): String = withCampaignAuth(user, guildId, ra) { discordId ->
        val outcome = campaignWebService.monsterAttack(guildId, discordId, attackId, targetName.trim())
        when (outcome.result) {
            MonsterAttackResult.HIT, MonsterAttackResult.MISS -> null
            MonsterAttackResult.NO_ACTIVE_CAMPAIGN -> "No active campaign in this server."
            MonsterAttackResult.NO_ACTIVE_COMBAT -> "Combat isn't active."
            MonsterAttackResult.NOT_DM -> "Only the Dungeon Master can drive monster attacks."
            MonsterAttackResult.CURRENT_NOT_MONSTER -> "It's not a monster's turn right now."
            MonsterAttackResult.NO_TEMPLATE -> "This monster has no template — add it to your library first."
            MonsterAttackResult.ATTACK_NOT_FOUND -> "That attack doesn't exist."
            MonsterAttackResult.ATTACK_TEMPLATE_MISMATCH -> "That attack doesn't belong to the current monster."
            MonsterAttackResult.INVALID_DAMAGE -> "This attack's damage expression is invalid — please re-save it."
            MonsterAttackResult.TARGET_NOT_FOUND -> "Target not found in initiative."
            MonsterAttackResult.TARGET_DEFEATED -> "That target is already defeated."
            MonsterAttackResult.CANT_TARGET_SELF -> "A monster can't attack itself."
        }
    }

    @PostMapping("/campaign/{guildId}/initiative/roll")
    fun rollInitiative(
        @PathVariable guildId: Long,
        @RequestParam(name = "playerDiscordIds", required = false) playerDiscordIds: List<Long>?,
        @RequestParam(name = "templateId", required = false) templateIds: List<Long>?,
        @RequestParam(name = "templateQty", required = false) templateQtys: List<String>?,
        @RequestParam(name = "adhocName", required = false) adhocNames: List<String>?,
        @RequestParam(name = "adhocMod", required = false) adhocMods: List<String>?,
        @RequestParam(name = "adhocHpExpr", required = false) adhocHpExprs: List<String>?,
        @RequestParam(name = "adhocAc", required = false) adhocAcs: List<String>?,
        @AuthenticationPrincipal user: OAuth2User,
        ra: RedirectAttributes
    ): String = withCampaignAuth(user, guildId, ra) { discordId ->
        // The ad-hoc monster rows have optional HP/AC inputs with no default,
        // so empty strings reach us for un-filled rows. Bind as List<String>
        // and parse leniently so empties become null/0 rather than 400/500.
        val tplIds = templateIds.orEmpty()
        val tplQtys = templateQtys.orEmpty()
        val expandedTemplateIds = tplIds.flatMapIndexed { i, id ->
            val qty = (tplQtys.getOrNull(i)?.toIntOrNull() ?: 1).coerceAtLeast(0)
            List(qty) { id }
        }

        val names = adhocNames.orEmpty()
        val mods = adhocMods.orEmpty()
        val hpExprs = adhocHpExprs.orEmpty()
        val acs = adhocAcs.orEmpty()
        val adhoc = names.mapIndexedNotNull { i, n ->
            val cleaned = n.trim()
            if (cleaned.isBlank()) null
            else AdhocMonster(
                name = cleaned,
                initiativeModifier = mods.getOrNull(i)?.toIntOrNull() ?: 0,
                hpExpression = hpExprs.getOrNull(i)?.trim()?.takeIf { it.isNotEmpty() },
                ac = acs.getOrNull(i)?.toIntOrNull()?.takeIf { it > 0 }
            )
        }
        val request = InitiativeRollRequest(
            playerDiscordIds = playerDiscordIds.orEmpty(),
            templateIds = expandedTemplateIds,
            adhocMonsters = adhoc
        )

        when (campaignWebService.rollInitiative(guildId, discordId, request)) {
            RollInitiativeResult.ROLLED -> null
            RollInitiativeResult.NO_ACTIVE_CAMPAIGN -> "No active campaign in this server."
            RollInitiativeResult.NOT_DM -> "Only the Dungeon Master can roll initiative here."
            RollInitiativeResult.EMPTY_ROSTER -> "Pick at least one player or monster before rolling."
            RollInitiativeResult.TEMPLATE_NOT_FOUND -> "One of the selected monster templates couldn't be found."
        }
    }

    @PostMapping("/campaign/{guildId}/roll")
    fun rollDice(
        @PathVariable guildId: Long,
        @RequestParam(name = "count", defaultValue = "1") count: Int,
        @RequestParam(name = "sides", defaultValue = "20") sides: Int,
        @RequestParam(name = "modifier", defaultValue = "0") modifier: Int,
        @RequestParam(name = "expression", required = false) expression: String?,
        @AuthenticationPrincipal user: OAuth2User,
        ra: RedirectAttributes
    ): String = withCampaignAuth(user, guildId, ra) { discordId ->
        when (campaignWebService.rollDice(guildId, discordId, count, sides, modifier, expression?.trim())) {
            RollDiceResult.ROLLED -> null
            RollDiceResult.NO_ACTIVE_CAMPAIGN -> "No active campaign in this server."
            RollDiceResult.NOT_PARTICIPANT -> "Only campaign participants can roll here."
            RollDiceResult.INVALID_SIDES -> "Die must be one of d4, d6, d8, d10, d12, d20, or d100."
            RollDiceResult.INVALID_COUNT -> "Dice count must be between 1 and ${CampaignWebService.MAX_DICE_COUNT}."
            RollDiceResult.INVALID_MODIFIER ->
                "Modifier must be between -${CampaignWebService.MAX_DICE_MODIFIER} and ${CampaignWebService.MAX_DICE_MODIFIER}."
            RollDiceResult.INVALID_EXPRESSION -> "Custom expression must look like '2d6+3' or 'd20-1'."
        }
    }

    @PostMapping("/campaign/{guildId}/combat/attack")
    fun attack(
        @PathVariable guildId: Long,
        @RequestParam("targetName") targetName: String,
        @RequestParam(name = "attackModifier", defaultValue = "0") attackModifier: Int,
        @AuthenticationPrincipal user: OAuth2User,
        ra: RedirectAttributes
    ): String = withCampaignAuth(user, guildId, ra) { discordId ->
        val outcome = campaignWebService.attack(guildId, discordId, targetName.trim(), attackModifier)
        when (outcome.result) {
            AttackResult.HIT, AttackResult.MISS -> null
            AttackResult.NO_ACTIVE_CAMPAIGN -> "No active campaign in this server."
            AttackResult.NO_ACTIVE_COMBAT -> "No active combat — roll initiative first."
            AttackResult.NOT_MY_TURN -> "You can only attack on your own turn (or as the DM)."
            AttackResult.TARGET_NOT_FOUND -> "That target isn't in the initiative order."
            AttackResult.TARGET_DEFEATED -> "That target is already defeated."
            AttackResult.CANT_TARGET_SELF -> "You can't attack yourself."
            AttackResult.INVALID_MODIFIER ->
                "Attack modifier must be between -${CampaignWebService.MAX_ATTACK_MODIFIER} and ${CampaignWebService.MAX_ATTACK_MODIFIER}."
        }
    }

    @PostMapping("/campaign/{guildId}/combat/damage")
    fun applyDamage(
        @PathVariable guildId: Long,
        @RequestParam("targetName") targetName: String,
        @RequestParam("amount") amount: String,
        @AuthenticationPrincipal user: OAuth2User,
        ra: RedirectAttributes
    ): String = withCampaignAuth(user, guildId, ra) { discordId ->
        when (campaignWebService.applyDamage(guildId, discordId, targetName.trim(), amount)) {
            ApplyDamageResult.APPLIED, ApplyDamageResult.DEFEATED -> null
            ApplyDamageResult.NO_ACTIVE_CAMPAIGN -> "No active campaign in this server."
            ApplyDamageResult.NO_ACTIVE_COMBAT -> "No active combat — roll initiative first."
            ApplyDamageResult.NOT_ATTACKER -> "You can only apply damage on your own turn (or as the DM)."
            ApplyDamageResult.TARGET_NOT_FOUND -> "That target isn't in the initiative order."
            ApplyDamageResult.INVALID_AMOUNT ->
                "Damage must be a number (0-${CampaignWebService.MAX_DAMAGE_AMOUNT}) or a dice expression like 2d6+3."
        }
    }

    @PostMapping("/campaign/{guildId}/combat/heal")
    fun applyHeal(
        @PathVariable guildId: Long,
        @RequestParam("targetName") targetName: String,
        @RequestParam("amount") amount: String,
        @AuthenticationPrincipal user: OAuth2User,
        ra: RedirectAttributes
    ): String = withCampaignAuth(user, guildId, ra) { discordId ->
        when (campaignWebService.applyHeal(guildId, discordId, targetName.trim(), amount)) {
            ApplyHealResult.APPLIED, ApplyHealResult.REVIVED -> null
            ApplyHealResult.NO_ACTIVE_CAMPAIGN -> "No active campaign in this server."
            ApplyHealResult.NO_ACTIVE_COMBAT -> "No active combat — roll initiative first."
            ApplyHealResult.NOT_ATTACKER -> "You can only heal on your own turn (or as the DM)."
            ApplyHealResult.TARGET_NOT_FOUND -> "That target isn't in the initiative order."
            ApplyHealResult.TARGET_HAS_NO_HP -> "That target has no HP tracked — can't heal them."
            ApplyHealResult.INVALID_AMOUNT ->
                "Heal must be a number (0-${CampaignWebService.MAX_DAMAGE_AMOUNT}) or a dice expression like 1d8+2."
        }
    }

    /**
     * The campaign mutation endpoints all share the same shape:
     *
     *   1. Reject anonymous callers back to the campaign list.
     *   2. Run the service operation.
     *   3. Translate the result into either success (no flash) or a flash
     *      `error` message.
     *   4. Redirect back to the campaign detail page.
     *
     * Wrapping that in one place means each endpoint is just the
     * service call and the result→message map; the auth + flash + redirect
     * scaffolding stops being duplicated 20+ times.
     */
    private inline fun withCampaignAuth(
        user: OAuth2User,
        guildId: Long,
        ra: RedirectAttributes,
        block: (discordId: Long) -> String?,
    ): String = WebGuildAccess.requireSignedInForPage(user, "/dnd/campaign") { discordId ->
        block(discordId)?.let { ra.addFlashAttribute("error", it) }
        "redirect:/dnd/campaign/$guildId"
    }
}
