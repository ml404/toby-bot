package bot.toby.install.button

import bot.toby.command.commands.misc.DailyEmbeds
import bot.toby.install.InstallWizard
import core.button.Button
import core.button.ButtonContext
import database.dto.user.UserDto
import database.service.social.LoginStreakService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

/**
 * "🎁 Claim daily credits" — the non-owner launcher button left on the
 * post-install "done" message. Anyone in the channel can tap it to claim
 * their own daily reward without typing `/daily`, turning the very first
 * post-setup action into a single click.
 *
 * Reuses [LoginStreakService.claim] (the same entry point as
 * [bot.toby.command.commands.misc.DailyCommand]) and renders the outcome
 * via the shared [DailyEmbeds], so the button and the slash command behave
 * identically. Uses the default ephemeral [Button.defersReply] ack, so the
 * handler just sends the result as a per-clicker follow-up.
 */
@Component
class InstallClaimDailyButton @Autowired constructor(
    private val loginStreakService: LoginStreakService,
) : Button {

    override val name: String = InstallWizard.BTN_CLAIM_DAILY
    override val description: String =
        "Claim your daily reward straight from the welcome message — usable by anyone, not just the owner."

    override fun handle(ctx: ButtonContext, requestingUserDto: UserDto, deleteDelay: Int) {
        val result = loginStreakService.claim(
            discordId = requestingUserDto.discordId,
            guildId = ctx.guild.idLong,
            channelId = ctx.event.channel.idLong,
        )
        ctx.event.hook.sendMessageEmbeds(DailyEmbeds.claimResult(result)).queue()
    }
}
