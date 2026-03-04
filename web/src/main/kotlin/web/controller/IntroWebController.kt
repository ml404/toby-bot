package web.controller

import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient
import org.springframework.security.oauth2.client.annotation.RegisteredOAuth2AuthorizedClient
import org.springframework.security.oauth2.core.user.OAuth2User
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.servlet.mvc.support.RedirectAttributes
import web.service.IntroWebService

@Controller
@RequestMapping("/intro")
class IntroWebController(
    private val introWebService: IntroWebService
) {

    @GetMapping("/guilds")
    fun guildList(
        @RegisteredOAuth2AuthorizedClient("discord") client: OAuth2AuthorizedClient,
        @AuthenticationPrincipal user: OAuth2User,
        model: Model
    ): String {
        val accessToken = client.accessToken.tokenValue
        val guilds = introWebService.getMutualGuilds(accessToken)

        model.addAttribute("guilds", guilds)
        model.addAttribute("username", user.getAttribute<String>("username") ?: "User")
        model.addAttribute("discordId", user.getAttribute<String>("id") ?: "")

        return "guilds"
    }

    @GetMapping("/{guildId}")
    fun introPage(
        @PathVariable guildId: Long,
        @AuthenticationPrincipal user: OAuth2User,
        model: Model,
        ra: RedirectAttributes
    ): String {
        val discordId = user.getAttribute<String>("id")?.toLongOrNull()
            ?: return "redirect:/intro/guilds"

        val guildName = introWebService.getGuildName(guildId) ?: run {
            ra.addFlashAttribute("error", "Bot is not in that server.")
            return "redirect:/intro/guilds"
        }

        val intros = introWebService.getUserIntros(discordId, guildId)

        model.addAttribute("guildId", guildId)
        model.addAttribute("guildName", guildName)
        model.addAttribute("intros", intros)
        model.addAttribute("username", user.getAttribute<String>("username") ?: "User")
        model.addAttribute("atLimit", intros.size >= IntroWebService.MAX_INTRO_COUNT)
        model.addAttribute("maxIntros", IntroWebService.MAX_INTRO_COUNT)

        return "intros"
    }

    @PostMapping("/{guildId}/set")
    fun setIntro(
        @PathVariable guildId: Long,
        @AuthenticationPrincipal user: OAuth2User,
        @RequestParam inputType: String,
        @RequestParam(required = false) url: String?,
        @RequestParam(required = false) file: MultipartFile?,
        @RequestParam(defaultValue = "90") volume: Int,
        @RequestParam(required = false) replaceIndex: Int?,
        ra: RedirectAttributes
    ): String {
        val discordId = user.getAttribute<String>("id")?.toLongOrNull()
            ?: return "redirect:/intro/guilds"

        val clampedVolume = volume.coerceIn(1, 100)
        val error: String? = when (inputType) {
            "url" -> introWebService.setIntroByUrl(discordId, guildId, url.orEmpty().trim(), clampedVolume, replaceIndex)
            "file" -> if (file != null && !file.isEmpty) {
                introWebService.setIntroByFile(discordId, guildId, file, clampedVolume, replaceIndex)
            } else {
                "No file provided."
            }
            else -> "Invalid input type."
        }

        if (error != null) {
            ra.addFlashAttribute("error", error)
        } else {
            ra.addFlashAttribute("success", "Intro saved successfully.")
        }

        return "redirect:/intro/$guildId"
    }

    @PostMapping("/{guildId}/delete/{introId:.+}")
    fun deleteIntro(
        @PathVariable guildId: Long,
        @PathVariable introId: String,
        @AuthenticationPrincipal user: OAuth2User,
        ra: RedirectAttributes
    ): String {
        val discordId = user.getAttribute<String>("id")?.toLongOrNull()
            ?: return "redirect:/intro/guilds"

        val error = introWebService.deleteIntro(discordId, guildId, introId)
        if (error != null) {
            ra.addFlashAttribute("error", error)
        } else {
            ra.addFlashAttribute("success", "Intro deleted.")
        }

        return "redirect:/intro/$guildId"
    }
}
