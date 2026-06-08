package bot.toby.install.button

import bot.toby.install.InstallSummary
import bot.toby.install.InstallWizard
import core.button.ButtonContext
import database.dto.user.UserDto
import database.service.economy.JackpotService
import database.service.guild.ConfigService
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/**
 * "⚙️ View setup" — owner-only launcher button on the post-install control
 * panel. Replies (ephemerally) with the same setup summary as
 * `/install summary`, so the owner can audit the current config and see
 * recommended next steps without re-running the whole wizard.
 *
 * Extends [OwnerOnlyInstallButton] for the owner gate; unlike its siblings
 * it doesn't edit the source message — it sends a fresh ephemeral reply
 * (the control panel stays put). Owner gate plus `defersReply = false`
 * means the interaction is un-acked here, so a direct `reply` is correct.
 */
@Component
class InstallSummaryButton(
    private val configService: ConfigService,
    private val jackpotService: JackpotService,
    @param:Value($$"${app.base-url:}") private val webBaseUrl: String = "",
) : OwnerOnlyInstallButton() {

    override val name: String = InstallWizard.BTN_VIEW_SETUP
    override val description: String = "Owner-only — show the current setup summary."

    override fun handleAsOwner(ctx: ButtonContext, requestingUserDto: UserDto, deleteDelay: Int) {
        val guild = ctx.guild
        val reader = InstallWizard.configReader(configService, guild.id)
        ctx.event.replyEmbeds(
            InstallSummary.embed(
                guild = guild,
                reader = reader,
                jackpotPool = jackpotService.getPool(guild.idLong),
                winChanceDisplay = jackpotService.winProbabilityDisplay(guild.idLong),
                webBaseUrl = webBaseUrl,
            ),
        ).setEphemeral(true).queue()
    }
}
