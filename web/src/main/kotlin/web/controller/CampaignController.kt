package web.controller

import database.service.CampaignPlayerService
import database.service.CampaignService
import net.dv8tion.jda.api.JDA
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient
import org.springframework.security.oauth2.client.annotation.RegisteredOAuth2AuthorizedClient
import org.springframework.security.oauth2.core.user.OAuth2User
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.servlet.mvc.support.RedirectAttributes
import web.service.CampaignWebService

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
        model.addAttribute("username", user.getAttribute<String>("username") ?: "User")

        return "campaign"
    }

    @GetMapping("/campaign/{guildId}")
    fun campaignDetail(
        @PathVariable guildId: Long,
        @AuthenticationPrincipal user: OAuth2User,
        model: Model,
        ra: RedirectAttributes
    ): String {
        val discordId = user.getAttribute<String>("id")?.toLongOrNull()
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
        model.addAttribute("username", user.getAttribute<String>("username") ?: "User")

        return "campaignDetail"
    }
}
