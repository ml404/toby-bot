package web.service

import net.dv8tion.jda.api.JDA
import org.springframework.stereotype.Component

/**
 * Single source of truth for "is this Discord user allowed to moderate
 * this guild." Owners always count; everyone else has to be flagged
 * as super-user via [IntroWebService.isSuperUser] (the read side of
 * the per-user override).
 *
 * Extracted so that the casino-admin slice ([CasinoAuditService]) can
 * gate its actions identically without taking a dependency on the
 * bigger [ModerationWebService].
 */
@Component
class ModerationAuthorizer(
    private val jda: JDA,
    private val introWebService: IntroWebService,
) {
    fun canModerate(discordId: Long, guildId: Long): Boolean {
        val guild = jda.getGuildById(guildId) ?: return false
        val member = guild.getMemberById(discordId)
        if (member?.isOwner == true) return true
        return introWebService.isSuperUser(discordId, guildId)
    }
}
