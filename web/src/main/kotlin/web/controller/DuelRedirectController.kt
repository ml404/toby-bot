package web.controller

import org.springframework.http.HttpStatus
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestMethod
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus

/**
 * Keeps the old `/duel/...` URL space alive after the web surface
 * moved under `/pvp/...`. GET shapes 301-redirect to their new
 * counterparts; non-GET JSON shapes (challenge / accept / decline /
 * cancel) 308-redirect so the method + body survive. Any third-party
 * bookmark or integration that still hits `/duel/...` lands somewhere
 * sensible.
 */
@Controller
@RequestMapping("/duel")
class DuelRedirectController {

    @GetMapping("/guilds")
    fun guildList(@RequestParam(required = false, defaultValue = "false") pick: Boolean): String =
        if (pick) "redirect:/pvp/guilds?pick=true" else "redirect:/pvp/guilds"

    @GetMapping("/{guildId}")
    fun page(@PathVariable guildId: Long): String =
        "redirect:/pvp/$guildId"

    @GetMapping("/{guildId}/pending")
    fun pending(@PathVariable guildId: Long): String =
        "redirect:/pvp/$guildId/duel/pending"

    @GetMapping("/{guildId}/outgoing")
    fun outgoing(@PathVariable guildId: Long): String =
        "redirect:/pvp/$guildId/duel/outgoing"

    @RequestMapping("/{guildId}/challenge", method = [RequestMethod.POST])
    @ResponseStatus(HttpStatus.PERMANENT_REDIRECT)
    fun challenge(@PathVariable guildId: Long): String =
        "redirect:/pvp/$guildId/duel/challenge"

    @RequestMapping("/{guildId}/{duelId}/accept", method = [RequestMethod.POST])
    @ResponseStatus(HttpStatus.PERMANENT_REDIRECT)
    fun accept(@PathVariable guildId: Long, @PathVariable duelId: Long): String =
        "redirect:/pvp/$guildId/duel/$duelId/accept"

    @RequestMapping("/{guildId}/{duelId}/decline", method = [RequestMethod.POST])
    @ResponseStatus(HttpStatus.PERMANENT_REDIRECT)
    fun decline(@PathVariable guildId: Long, @PathVariable duelId: Long): String =
        "redirect:/pvp/$guildId/duel/$duelId/decline"

    @RequestMapping("/{guildId}/{duelId}/cancel", method = [RequestMethod.POST])
    @ResponseStatus(HttpStatus.PERMANENT_REDIRECT)
    fun cancel(@PathVariable guildId: Long, @PathVariable duelId: Long): String =
        "redirect:/pvp/$guildId/duel/$duelId/cancel"
}
