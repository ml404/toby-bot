package web.controller

import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient
import org.springframework.security.oauth2.client.annotation.RegisteredOAuth2AuthorizedClient
import org.springframework.security.oauth2.core.user.OAuth2User
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseBody
import org.springframework.web.servlet.mvc.support.RedirectAttributes
import web.service.BuyWithTobyOutcome
import web.service.TitlesWebService
import web.util.discordIdOrNull
import web.util.displayName

@Controller
@RequestMapping("/titles")
class TitlesController(
    private val titlesWebService: TitlesWebService
) {

    @GetMapping("/guilds")
    fun guildList(
        @RegisteredOAuth2AuthorizedClient("discord") client: OAuth2AuthorizedClient,
        @AuthenticationPrincipal user: OAuth2User,
        model: Model
    ): String {
        val discordId = user.discordIdOrNull()
        val guilds = if (discordId != null) {
            titlesWebService.getMemberGuilds(client.accessToken.tokenValue, discordId)
        } else emptyList()

        model.addAttribute("guilds", guilds)
        model.addAttribute("username", user.displayName())
        return "titles-guilds"
    }

    @GetMapping("/{guildId}")
    fun titlesPage(
        @PathVariable guildId: Long,
        @AuthenticationPrincipal user: OAuth2User,
        model: Model,
        ra: RedirectAttributes
    ): String {
        val discordId = user.discordIdOrNull()
            ?: return "redirect:/titles/guilds"

        if (!titlesWebService.isMember(discordId, guildId)) {
            ra.addFlashAttribute("error", "You are not a member of that server.")
            return "redirect:/titles/guilds"
        }

        val titleShop = titlesWebService.getTitlesForGuild(guildId, discordId)
        model.addAttribute("titleShop", titleShop)
        model.addAttribute("guildId", guildId.toString())
        model.addAttribute("username", user.displayName())
        return "titles"
    }

    @PostMapping("/{guildId}/{titleId}/buy")
    @ResponseBody
    fun buy(
        @PathVariable guildId: Long,
        @PathVariable titleId: Long,
        @AuthenticationPrincipal user: OAuth2User
    ): ResponseEntity<ApiResult> {
        val actor = user.discordIdOrNull()
            ?: return ResponseEntity.status(401).body(ApiResult(false, "Not signed in."))
        if (!titlesWebService.isMember(actor, guildId)) {
            return ResponseEntity.status(403).body(ApiResult(false, "You are not a member of that server."))
        }
        val error = titlesWebService.buyTitle(actor, guildId, titleId)
        return if (error != null) ResponseEntity.badRequest().body(ApiResult(false, error))
        else ResponseEntity.ok(ApiResult(true, null))
    }

    @PostMapping("/{guildId}/{titleId}/buy-with-toby")
    @ResponseBody
    fun buyWithToby(
        @PathVariable guildId: Long,
        @PathVariable titleId: Long,
        @AuthenticationPrincipal user: OAuth2User
    ): ResponseEntity<BuyWithTobyResponse> {
        val actor = user.discordIdOrNull()
            ?: return ResponseEntity.status(401).body(BuyWithTobyResponse(false, "Not signed in."))
        if (!titlesWebService.isMember(actor, guildId)) {
            return ResponseEntity.status(403).body(BuyWithTobyResponse(false, "You are not a member of that server."))
        }
        return when (val outcome = titlesWebService.buyTitleWithTobyCoin(actor, guildId, titleId)) {
            is BuyWithTobyOutcome.Ok -> ResponseEntity.ok(
                BuyWithTobyResponse(
                    ok = true,
                    error = null,
                    soldTobyCoins = outcome.soldTobyCoins,
                    newCoins = outcome.newCoins,
                    newCredits = outcome.newCredits,
                    newPrice = outcome.newPrice
                )
            )
            is BuyWithTobyOutcome.InsufficientCoins -> ResponseEntity.badRequest().body(
                BuyWithTobyResponse(false, "Need ${outcome.needed} TOBY, you have ${outcome.have}.")
            )
            BuyWithTobyOutcome.AlreadyOwns -> ResponseEntity.badRequest().body(
                BuyWithTobyResponse(false, "You already own this title.")
            )
            is BuyWithTobyOutcome.Error -> ResponseEntity.badRequest().body(
                BuyWithTobyResponse(false, outcome.message)
            )
        }
    }

    @PostMapping("/{guildId}/{titleId}/equip")
    @ResponseBody
    fun equip(
        @PathVariable guildId: Long,
        @PathVariable titleId: Long,
        @AuthenticationPrincipal user: OAuth2User
    ): ResponseEntity<ApiResult> {
        val actor = user.discordIdOrNull()
            ?: return ResponseEntity.status(401).body(ApiResult(false, "Not signed in."))
        if (!titlesWebService.isMember(actor, guildId)) {
            return ResponseEntity.status(403).body(ApiResult(false, "You are not a member of that server."))
        }
        val error = titlesWebService.equipTitle(actor, guildId, titleId)
        return if (error != null) ResponseEntity.badRequest().body(ApiResult(false, error))
        else ResponseEntity.ok(ApiResult(true, null))
    }

    @DeleteMapping("/{guildId}/equipped")
    @ResponseBody
    fun unequip(
        @PathVariable guildId: Long,
        @AuthenticationPrincipal user: OAuth2User
    ): ResponseEntity<ApiResult> {
        val actor = user.discordIdOrNull()
            ?: return ResponseEntity.status(401).body(ApiResult(false, "Not signed in."))
        if (!titlesWebService.isMember(actor, guildId)) {
            return ResponseEntity.status(403).body(ApiResult(false, "You are not a member of that server."))
        }
        val error = titlesWebService.unequipTitle(actor, guildId)
        return if (error != null) ResponseEntity.badRequest().body(ApiResult(false, error))
        else ResponseEntity.ok(ApiResult(true, null))
    }
}

data class BuyWithTobyResponse(
    val ok: Boolean,
    val error: String? = null,
    val soldTobyCoins: Long? = null,
    val newCoins: Long? = null,
    val newCredits: Long? = null,
    val newPrice: Double? = null
)
