package web.service

import database.dto.UserDto
import database.service.TipDailyService
import database.service.UserService
import org.springframework.stereotype.Service
import java.time.LocalDate

/**
 * Read-only helpers the [web.controller.TipController] needs that
 * don't belong on [database.service.TipService] proper:
 *  - daily-tipped lookup for the page render (TipService.tip itself
 *    only returns post-tip state).
 *  - lazy-create of the recipient's [UserDto] row so a tip to a
 *    not-yet-seen user works without a Discord-side bootstrap.
 */
@Service
class TipWebService(
    private val tipDailyService: TipDailyService,
    private val userService: UserService,
) {
    fun getDailyTipped(senderDiscordId: Long, guildId: Long, date: LocalDate): Long =
        tipDailyService.get(senderDiscordId, guildId, date)?.creditsSent ?: 0L

    fun ensureRecipient(recipientDiscordId: Long, guildId: Long): UserDto {
        return userService.getUserById(recipientDiscordId, guildId)
            ?: userService.createNewUser(UserDto(recipientDiscordId, guildId))
    }
}
