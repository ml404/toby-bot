package bot.toby.install.button

import bot.toby.command.commands.game.casino.coinflip.CoinflipEmbeds
import bot.toby.install.InstallWizard
import common.casino.coinflip.Coinflip
import core.button.Button
import core.button.ButtonContext
import database.dto.user.UserDto
import database.service.casino.coinflip.CoinflipService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

/**
 * "🪙 Flip a coin" — the non-owner launcher button left on the post-install
 * "done" message. Anyone can tap it to play one real coinflip (calling
 * heads) at the minimum stake, so a brand-new server *sees the casino work*
 * in a single click instead of being told to go type `/coinflip`.
 *
 * Plays through [CoinflipService.flip] (the same path as
 * [bot.toby.command.commands.game.casino.coinflip.CoinflipCommand]) and
 * renders via the shared [CoinflipEmbeds], so the button and command can't
 * drift. Uses the default ephemeral [Button.defersReply] ack — each clicker
 * stakes their own credits and gets their own private result. If they're
 * out of credits or below the per-guild stake floor, the shared embed says
 * so (and the sibling "Claim daily" button is right next to it).
 */
@Component
class InstallQuickFlipButton @Autowired constructor(
    private val coinflipService: CoinflipService,
) : Button {

    override val name: String = InstallWizard.BTN_QUICK_FLIP
    override val description: String =
        "Play one quick coinflip straight from the welcome message — usable by anyone, not just the owner."

    override fun handle(ctx: ButtonContext, requestingUserDto: UserDto, deleteDelay: Int) {
        val outcome = coinflipService.flip(
            discordId = requestingUserDto.discordId,
            guildId = ctx.guild.idLong,
            stake = Coinflip.MIN_STAKE,
            predicted = Coinflip.Side.HEADS,
        )
        ctx.event.hook.sendMessageEmbeds(CoinflipEmbeds.outcome(outcome)).queue()
    }
}
