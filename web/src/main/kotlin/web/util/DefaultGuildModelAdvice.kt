package web.util

import jakarta.servlet.http.HttpServletRequest
import net.dv8tion.jda.api.JDA
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ModelAttribute

/**
 * Exposes the current anchored guild id + name to every page model so
 * the navbar can render a "Default: ServerName" pill regardless of
 * which controller rendered the request. Without this, every picker
 * controller would have to remember to set the attributes manually
 * just so the shared navbar fragment renders correctly.
 *
 * Looking up the guild name through JDA is in-memory (no Discord API
 * hit) — same call the picker controllers make to validate cookies.
 * If the bot was kicked from the cookie-anchored guild, the lookup
 * returns null and the navbar quietly hides the pill rather than
 * showing a phantom server name.
 */
@ControllerAdvice
class DefaultGuildModelAdvice(private val jda: JDA) {

    @ModelAttribute("currentDefaultGuildId")
    fun currentDefaultGuildId(request: HttpServletRequest): Long? =
        DefaultGuildCookie.read(request)

    @ModelAttribute("currentDefaultGuildName")
    fun currentDefaultGuildName(request: HttpServletRequest): String? {
        val id = DefaultGuildCookie.read(request) ?: return null
        return jda.getGuildById(id)?.name
    }
}
