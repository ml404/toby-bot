package web.controller

import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient
import org.springframework.security.oauth2.client.annotation.RegisteredOAuth2AuthorizedClient
import org.springframework.security.oauth2.core.user.OAuth2User
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.*
import org.springframework.web.servlet.mvc.support.RedirectAttributes
import web.service.CampaignWebService
import web.service.JoinResult
import web.service.LeaveResult
import web.service.SetCharacterResult
import web.util.discordIdOrNull
import web.util.displayName

@Controller
@RequestMapping("/dnd")
class CampaignController(
    private val campaignWebService: CampaignWebService
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
        model.addAttribute("username", user.displayName())

        return "campaignDetail"
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
}
