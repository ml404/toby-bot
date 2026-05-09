package web.casino

import database.service.JackpotGame
import database.service.JackpotService
import database.service.TobyCoinMarketService
import database.service.UserService
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.entities.Guild
import org.springframework.security.oauth2.core.user.OAuth2User
import org.springframework.stereotype.Component
import org.springframework.ui.Model
import org.springframework.web.servlet.mvc.support.RedirectAttributes
import web.service.EconomyWebService
import web.util.WebGuildAccess
import web.util.displayName

/**
 * Resolves the model attributes every casino-game page needs:
 * `guildId`, `guildName`, `balance`, `tobyCoins`, `marketPrice`,
 * `jackpotPool`, `jackpotWinPct`, `jackpotStakeAnchor`, `username`.
 * Returns the resolved [Guild] (or `null` when the bot has been
 * kicked from the guild between the auth check and now) so the caller
 * can short-circuit with a flash error.
 *
 * Each per-game page handler used to repeat ~15 lines of `addAttribute`
 * calls; centralising them here means a new common attribute (e.g. a
 * cosmetic theme, an account flag) is one edit instead of five.
 * Game-specific attributes (min/max stake, payout tables, anchors) are
 * still set by the controller after this returns.
 */
@Component
class CasinoPageContext(
    private val userService: UserService,
    private val jackpotService: JackpotService,
    private val marketService: TobyCoinMarketService,
    private val jda: JDA,
) {
    fun populate(
        model: Model,
        guildId: Long,
        discordId: Long,
        user: OAuth2User,
        game: JackpotGame? = null,
    ): Guild? {
        val guild = jda.getGuildById(guildId) ?: return null
        val profile = userService.getUserById(discordId, guildId)
        model.addAttribute("guildId", guildId.toString())
        model.addAttribute("guildName", guild.name)
        model.addAttribute("balance", profile?.socialCredit ?: 0L)
        model.addAttribute("tobyCoins", profile?.tobyCoins ?: 0L)
        model.addAttribute("marketPrice", marketService.getMarket(guildId)?.price ?: 0.0)
        model.addAttribute("jackpotPool", jackpotService.getPool(guildId))
        model.addAttribute("jackpotWinPct", jackpotService.winProbabilityDisplay(guildId))
        model.addAttribute("jackpotStakeAnchor", jackpotService.stakeAnchor(guildId))
        // Per-game RTP eligibility — only set when caller declares which
        // game this page is for. Lottery / poker pages share the banner
        // but don't roll for the jackpot themselves, so they leave `game`
        // null and the banner stays in its eligible-by-default form.
        if (game != null && !jackpotService.isEligibleByRtp(guildId, game)) {
            model.addAttribute("jackpotIneligible", true)
            model.addAttribute("jackpotRtpMax", jackpotService.rtpMaxPct(guildId))
        }
        model.addAttribute("username", user.displayName())
        return guild
    }
}

/**
 * Membership guard + page-context populate + bot-kicked fallback +
 * game-specific rules attributes — the GET-page boilerplate every
 * minigame controller used to copy verbatim. Returns the template
 * name to render or a `redirect:` string when guards fail.
 *
 * Extension (rather than a method on [CasinoPageContext]) so existing
 * tests that mock `populate` directly still work — extension dispatch
 * is static, but the inner `populate` call still routes through the
 * mocked instance.
 */
fun CasinoPageContext.renderMinigamePage(
    user: OAuth2User,
    guildId: Long,
    economyWebService: EconomyWebService,
    model: Model,
    ra: RedirectAttributes,
    template: String,
    lobbyPath: String = "/casino/guilds",
    game: JackpotGame? = null,
    addRulesAttributes: Model.() -> Unit,
): String = WebGuildAccess.requireMemberForPage(
    user, guildId, economyWebService, ra, lobbyPath = lobbyPath
) { discordId ->
    populate(model, guildId, discordId, user, game) ?: run {
        ra.addFlashAttribute("error", "Bot is not in that server.")
        return@requireMemberForPage "redirect:$lobbyPath"
    }
    model.addRulesAttributes()
    template
}
