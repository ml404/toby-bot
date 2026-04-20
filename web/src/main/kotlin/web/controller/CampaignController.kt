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
import web.service.CampaignEventBroadcaster
import web.service.CampaignWebService
import web.service.DeleteNoteResult
import web.service.DeleteTemplateResult
import web.service.EndResult
import web.service.InitiativeRollRequest
import web.service.JoinResult
import web.service.KickResult
import web.service.LeaveResult
import web.service.MonsterTemplateView
import web.service.NarrateResult
import web.service.RollDiceResult
import web.service.RollInitiativeResult
import web.service.SaveTemplateResult
import web.service.SessionEventView
import web.service.SetAliveResult
import web.service.SetCharacterResult
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
    ): String {
        val discordId = user.discordIdOrNull()
            ?: return "redirect:/dnd/campaign"

        val guildName = campaignWebService.getGuildName(guildId) ?: run {
            ra.addFlashAttribute("error", "Bot is not in that server.")
            return "redirect:/dnd/campaign"
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
        model.addAttribute("username", user.displayName())

        return "campaignDetail"
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
    ): String {
        val discordId = user.discordIdOrNull()
            ?: return "redirect:/dnd/campaign"

        campaignWebService.getGuildName(guildId) ?: run {
            ra.addFlashAttribute("error", "Bot is not in that server.")
            return "redirect:/dnd/campaign"
        }

        if (campaignWebService.createCampaign(guildId, discordId, name) == null) {
            ra.addFlashAttribute("error", "A campaign is already active in this server.")
        }
        return "redirect:/dnd/campaign/$guildId"
    }

    @PostMapping("/campaign/{guildId}/join")
    fun joinCampaign(
        @PathVariable guildId: Long,
        @AuthenticationPrincipal user: OAuth2User,
        ra: RedirectAttributes
    ): String {
        val discordId = user.discordIdOrNull()
            ?: return "redirect:/dnd/campaign"

        when (campaignWebService.joinCampaign(guildId, discordId)) {
            JoinResult.JOINED -> {}
            JoinResult.NO_ACTIVE_CAMPAIGN -> ra.addFlashAttribute("error", "No active campaign in this server.")
            JoinResult.ALREADY_JOINED -> ra.addFlashAttribute("error", "You are already in this campaign.")
            JoinResult.IS_DM -> ra.addFlashAttribute("error", "You are the DM and cannot join as a player.")
        }
        return "redirect:/dnd/campaign/$guildId"
    }

    @PostMapping("/campaign/{guildId}/leave")
    fun leaveCampaign(
        @PathVariable guildId: Long,
        @AuthenticationPrincipal user: OAuth2User,
        ra: RedirectAttributes
    ): String {
        val discordId = user.discordIdOrNull()
            ?: return "redirect:/dnd/campaign"

        when (campaignWebService.leaveCampaign(guildId, discordId)) {
            LeaveResult.LEFT -> {}
            LeaveResult.NO_ACTIVE_CAMPAIGN -> ra.addFlashAttribute("error", "No active campaign in this server.")
            LeaveResult.NOT_A_PLAYER -> ra.addFlashAttribute("error", "You are not in this campaign.")
        }
        return "redirect:/dnd/campaign/$guildId"
    }

    @PostMapping("/campaign/{guildId}/character")
    fun setLinkedCharacter(
        @PathVariable guildId: Long,
        @RequestParam(name = "characterInput", required = false, defaultValue = "") characterInput: String,
        @AuthenticationPrincipal user: OAuth2User,
        ra: RedirectAttributes
    ): String {
        val discordId = user.discordIdOrNull()
            ?: return "redirect:/dnd/campaign"

        when (campaignWebService.setLinkedCharacter(guildId, discordId, characterInput)) {
            SetCharacterResult.UPDATED, SetCharacterResult.CLEARED -> {}
            SetCharacterResult.INVALID -> ra.addFlashAttribute(
                "error",
                "Could not extract a valid character ID. Paste a D&D Beyond URL or numeric ID."
            )
        }
        return "redirect:/dnd/campaign/$guildId"
    }

    @PostMapping("/campaign/{guildId}/end")
    fun endCampaign(
        @PathVariable guildId: Long,
        @AuthenticationPrincipal user: OAuth2User,
        ra: RedirectAttributes
    ): String {
        val discordId = user.discordIdOrNull()
            ?: return "redirect:/dnd/campaign"

        when (campaignWebService.endCampaign(guildId, discordId)) {
            EndResult.ENDED -> {}
            EndResult.NO_ACTIVE_CAMPAIGN -> ra.addFlashAttribute("error", "No active campaign in this server.")
            EndResult.NOT_DM -> ra.addFlashAttribute("error", "Only the Dungeon Master can end the campaign.")
        }
        return "redirect:/dnd/campaign/$guildId"
    }

    @PostMapping("/campaign/{guildId}/players/{targetDiscordId}/kick")
    fun kickPlayer(
        @PathVariable guildId: Long,
        @PathVariable targetDiscordId: Long,
        @AuthenticationPrincipal user: OAuth2User,
        ra: RedirectAttributes
    ): String {
        val discordId = user.discordIdOrNull()
            ?: return "redirect:/dnd/campaign"

        when (campaignWebService.kickPlayer(guildId, discordId, targetDiscordId)) {
            KickResult.KICKED -> {}
            KickResult.NO_ACTIVE_CAMPAIGN -> ra.addFlashAttribute("error", "No active campaign in this server.")
            KickResult.NOT_DM -> ra.addFlashAttribute("error", "Only the Dungeon Master can kick players.")
            KickResult.NOT_A_PLAYER -> ra.addFlashAttribute("error", "That user isn't in the campaign.")
            KickResult.CANNOT_KICK_DM -> ra.addFlashAttribute("error", "The DM can't be kicked.")
        }
        return "redirect:/dnd/campaign/$guildId"
    }

    @PostMapping("/campaign/{guildId}/players/{targetDiscordId}/alive")
    fun setPlayerAlive(
        @PathVariable guildId: Long,
        @PathVariable targetDiscordId: Long,
        @RequestParam alive: Boolean,
        @AuthenticationPrincipal user: OAuth2User,
        ra: RedirectAttributes
    ): String {
        val discordId = user.discordIdOrNull()
            ?: return "redirect:/dnd/campaign"

        when (campaignWebService.setPlayerAlive(guildId, discordId, targetDiscordId, alive)) {
            SetAliveResult.UPDATED -> {}
            SetAliveResult.NO_ACTIVE_CAMPAIGN -> ra.addFlashAttribute("error", "No active campaign in this server.")
            SetAliveResult.NOT_DM -> ra.addFlashAttribute("error", "Only the Dungeon Master can change player status.")
            SetAliveResult.NOT_A_PLAYER -> ra.addFlashAttribute("error", "That user isn't in the campaign.")
        }
        return "redirect:/dnd/campaign/$guildId"
    }

    @PostMapping("/campaign/{guildId}/notes")
    fun addNote(
        @PathVariable guildId: Long,
        @RequestParam body: String,
        @AuthenticationPrincipal user: OAuth2User,
        ra: RedirectAttributes
    ): String {
        val discordId = user.discordIdOrNull()
            ?: return "redirect:/dnd/campaign"

        when (campaignWebService.addNote(guildId, discordId, body)) {
            AddNoteResult.ADDED -> {}
            AddNoteResult.NO_ACTIVE_CAMPAIGN -> ra.addFlashAttribute("error", "No active campaign in this server.")
            AddNoteResult.NOT_PARTICIPANT -> ra.addFlashAttribute(
                "error",
                "Only the DM and campaign players can add notes."
            )
            AddNoteResult.EMPTY_BODY -> ra.addFlashAttribute("error", "Note body can't be empty.")
            AddNoteResult.BODY_TOO_LONG -> ra.addFlashAttribute(
                "error",
                "Note is too long (max 2000 characters)."
            )
        }
        return "redirect:/dnd/campaign/$guildId"
    }

    @PostMapping("/campaign/{guildId}/notes/{noteId}/delete")
    fun deleteNote(
        @PathVariable guildId: Long,
        @PathVariable noteId: Long,
        @AuthenticationPrincipal user: OAuth2User,
        ra: RedirectAttributes
    ): String {
        val discordId = user.discordIdOrNull()
            ?: return "redirect:/dnd/campaign"

        when (campaignWebService.deleteNote(guildId, discordId, noteId)) {
            DeleteNoteResult.DELETED -> {}
            DeleteNoteResult.NO_ACTIVE_CAMPAIGN -> ra.addFlashAttribute("error", "No active campaign in this server.")
            DeleteNoteResult.NOT_FOUND -> ra.addFlashAttribute("error", "That note doesn't exist.")
            DeleteNoteResult.NOT_ALLOWED -> ra.addFlashAttribute(
                "error",
                "You can only delete your own notes (or any note if you're the DM)."
            )
        }
        return "redirect:/dnd/campaign/$guildId"
    }

    @PostMapping("/campaign/{guildId}/events/{eventId}/annotate")
    fun annotateRoll(
        @PathVariable guildId: Long,
        @PathVariable eventId: Long,
        @RequestParam kind: String,
        @RequestParam(name = "target", required = false) target: String?,
        @AuthenticationPrincipal user: OAuth2User,
        ra: RedirectAttributes
    ): String {
        val discordId = user.discordIdOrNull()
            ?: return "redirect:/dnd/campaign"

        when (campaignWebService.annotateRoll(guildId, discordId, eventId, kind, target)) {
            AnnotateRollResult.ANNOTATED -> {}
            AnnotateRollResult.NO_ACTIVE_CAMPAIGN -> ra.addFlashAttribute("error", "No active campaign in this server.")
            AnnotateRollResult.NOT_DM -> ra.addFlashAttribute("error", "Only the Dungeon Master can annotate rolls.")
            AnnotateRollResult.NOT_FOUND -> ra.addFlashAttribute("error", "That roll doesn't exist in this campaign.")
            AnnotateRollResult.NOT_A_ROLL -> ra.addFlashAttribute("error", "You can only mark Hit/Miss on a roll.")
            AnnotateRollResult.INVALID_KIND -> ra.addFlashAttribute("error", "Annotation kind must be HIT or MISS.")
        }
        return "redirect:/dnd/campaign/$guildId"
    }

    @PostMapping("/campaign/{guildId}/narrate")
    fun narrate(
        @PathVariable guildId: Long,
        @RequestParam body: String,
        @AuthenticationPrincipal user: OAuth2User,
        ra: RedirectAttributes
    ): String {
        val discordId = user.discordIdOrNull()
            ?: return "redirect:/dnd/campaign"

        when (campaignWebService.narrate(guildId, discordId, body)) {
            NarrateResult.NARRATED -> {}
            NarrateResult.NO_ACTIVE_CAMPAIGN -> ra.addFlashAttribute("error", "No active campaign in this server.")
            NarrateResult.NOT_DM -> ra.addFlashAttribute("error", "Only the Dungeon Master can narrate.")
            NarrateResult.EMPTY_BODY -> ra.addFlashAttribute("error", "Narration can't be empty.")
            NarrateResult.BODY_TOO_LONG -> ra.addFlashAttribute(
                "error",
                "Narration is too long (max ${web.service.CampaignWebService.MAX_NARRATE_BODY_LENGTH} characters)."
            )
        }
        return "redirect:/dnd/campaign/$guildId"
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
        @RequestParam(name = "maxHp", required = false) maxHp: Int?,
        @RequestParam(name = "ac", required = false) ac: Int?,
        @AuthenticationPrincipal user: OAuth2User,
        ra: RedirectAttributes
    ): String {
        val discordId = user.discordIdOrNull()
            ?: return "redirect:/dnd/campaign"

        when (campaignWebService.saveTemplate(discordId, id, name, initiativeModifier, maxHp, ac)) {
            SaveTemplateResult.SAVED -> {}
            SaveTemplateResult.NAME_BLANK -> ra.addFlashAttribute("error", "Monster name can't be empty.")
            SaveTemplateResult.NAME_TOO_LONG -> ra.addFlashAttribute(
                "error",
                "Monster name is too long (max ${web.service.CampaignWebService.MAX_TEMPLATE_NAME_LENGTH} characters)."
            )
            SaveTemplateResult.NOT_FOUND -> ra.addFlashAttribute("error", "That template doesn't exist.")
            SaveTemplateResult.NOT_OWNER -> ra.addFlashAttribute("error", "You can only edit your own templates.")
        }
        return "redirect:/dnd/campaign/$guildId"
    }

    @PostMapping("/campaign/{guildId}/monsters/templates/{templateId}/delete")
    fun deleteMonsterTemplate(
        @PathVariable guildId: Long,
        @PathVariable templateId: Long,
        @AuthenticationPrincipal user: OAuth2User,
        ra: RedirectAttributes
    ): String {
        val discordId = user.discordIdOrNull()
            ?: return "redirect:/dnd/campaign"

        when (campaignWebService.deleteTemplate(discordId, templateId)) {
            DeleteTemplateResult.DELETED -> {}
            DeleteTemplateResult.NOT_FOUND -> ra.addFlashAttribute("error", "That template doesn't exist.")
            DeleteTemplateResult.NOT_OWNER -> ra.addFlashAttribute("error", "You can only delete your own templates.")
        }
        return "redirect:/dnd/campaign/$guildId"
    }

    @PostMapping("/campaign/{guildId}/initiative/roll")
    fun rollInitiative(
        @PathVariable guildId: Long,
        @RequestParam(name = "playerDiscordIds", required = false) playerDiscordIds: List<Long>?,
        @RequestParam(name = "templateId", required = false) templateIds: List<Long>?,
        @RequestParam(name = "templateQty", required = false) templateQtys: List<Int>?,
        @RequestParam(name = "adhocName", required = false) adhocNames: List<String>?,
        @RequestParam(name = "adhocMod", required = false) adhocMods: List<Int>?,
        @AuthenticationPrincipal user: OAuth2User,
        ra: RedirectAttributes
    ): String {
        val discordId = user.discordIdOrNull()
            ?: return "redirect:/dnd/campaign"

        val tplIds = templateIds.orEmpty()
        val tplQtys = templateQtys.orEmpty()
        val expandedTemplateIds = tplIds.flatMapIndexed { i, id ->
            val qty = tplQtys.getOrElse(i) { 1 }.coerceAtLeast(0)
            List(qty) { id }
        }

        val names = adhocNames.orEmpty()
        val mods = adhocMods.orEmpty()
        val adhoc = names.mapIndexedNotNull { i, n ->
            val cleaned = n.trim()
            if (cleaned.isBlank()) null
            else AdhocMonster(cleaned, mods.getOrElse(i) { 0 })
        }
        val request = InitiativeRollRequest(
            playerDiscordIds = playerDiscordIds.orEmpty(),
            templateIds = expandedTemplateIds,
            adhocMonsters = adhoc
        )

        when (campaignWebService.rollInitiative(guildId, discordId, request)) {
            RollInitiativeResult.ROLLED -> {}
            RollInitiativeResult.NO_ACTIVE_CAMPAIGN -> ra.addFlashAttribute("error", "No active campaign in this server.")
            RollInitiativeResult.NOT_DM -> ra.addFlashAttribute("error", "Only the Dungeon Master can roll initiative here.")
            RollInitiativeResult.EMPTY_ROSTER -> ra.addFlashAttribute(
                "error",
                "Pick at least one player or monster before rolling."
            )
            RollInitiativeResult.TEMPLATE_NOT_FOUND -> ra.addFlashAttribute(
                "error",
                "One of the selected monster templates couldn't be found."
            )
        }
        return "redirect:/dnd/campaign/$guildId"
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
    ): String {
        val discordId = user.discordIdOrNull()
            ?: return "redirect:/dnd/campaign"

        when (campaignWebService.rollDice(guildId, discordId, count, sides, modifier, expression?.trim())) {
            RollDiceResult.ROLLED -> {}
            RollDiceResult.NO_ACTIVE_CAMPAIGN -> ra.addFlashAttribute("error", "No active campaign in this server.")
            RollDiceResult.NOT_PARTICIPANT -> ra.addFlashAttribute(
                "error",
                "Only campaign participants can roll here."
            )
            RollDiceResult.INVALID_SIDES -> ra.addFlashAttribute(
                "error",
                "Die must be one of d4, d6, d8, d10, d12, d20, or d100."
            )
            RollDiceResult.INVALID_COUNT -> ra.addFlashAttribute(
                "error",
                "Dice count must be between 1 and ${web.service.CampaignWebService.MAX_DICE_COUNT}."
            )
            RollDiceResult.INVALID_MODIFIER -> ra.addFlashAttribute(
                "error",
                "Modifier must be between -${web.service.CampaignWebService.MAX_DICE_MODIFIER} and ${web.service.CampaignWebService.MAX_DICE_MODIFIER}."
            )
            RollDiceResult.INVALID_EXPRESSION -> ra.addFlashAttribute(
                "error",
                "Custom expression must look like '2d6+3' or 'd20-1'."
            )
        }
        return "redirect:/dnd/campaign/$guildId"
    }
}
