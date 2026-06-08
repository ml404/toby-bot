package bot.toby.command.commands.game.casino.coinflip

import core.command.CommandContext
import database.dto.user.UserDto
import common.casino.coinflip.Coinflip
import database.service.casino.coinflip.CoinflipService
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import bot.toby.command.commands.game.pvp.GameCommand
import bot.toby.command.commands.game.WagerCommandEmbeds

/**
 * `/coinflip side:<HEADS|TAILS> stake:<int>` — fair 50/50 double-or-
 * nothing. Calls through to [CoinflipService.flip], which is the same
 * path the web `/casino/{guildId}/coinflip` page uses, so the Discord
 * and web surfaces can't drift on payout maths or balance debit/credit
 * semantics.
 */
@Component
class CoinflipCommand @Autowired constructor(
    private val coinflipService: CoinflipService
) : GameCommand {

    override val name: String = "coinflip"
    override val description: String =
        "Flip a coin for double-or-nothing. Stake bounds are per-guild (default ${Coinflip.MIN_STAKE}-${Coinflip.MAX_STAKE})."

    companion object {
        private const val OPT_SIDE = "side"
        private const val OPT_STAKE = "stake"
        private const val SIDE_HEADS = "HEADS"
        private const val SIDE_TAILS = "TAILS"
        private val TITLE = CoinflipEmbeds.TITLE
    }

    override val optionData: List<OptionData> = listOf(
        OptionData(OptionType.STRING, OPT_SIDE, "Heads or tails", true)
            .addChoice("Heads", SIDE_HEADS)
            .addChoice("Tails", SIDE_TAILS),
        OptionData(OptionType.INTEGER, OPT_STAKE, "Credits to wager (per-guild bounds; service rejects out-of-range)", true)
            .setMinValue(1L)
    )

    override fun handle(ctx: CommandContext, requestingUserDto: UserDto, deleteDelay: Int) {
        val event = ctx.event

        val guild = WagerCommandEmbeds.requireGuild(event, TITLE, deleteDelay) ?: return
        val side = parseSide(event.getOption(OPT_SIDE)?.asString) ?: run {
            WagerCommandEmbeds.replyError(event, TITLE, "Pick a side: heads or tails.", deleteDelay); return
        }
        val stake = WagerCommandEmbeds.requireOption(
            event, TITLE, OPT_STAKE, "You must specify a stake.", deleteDelay
        )?.asLong ?: return

        val outcome = coinflipService.flip(requestingUserDto.discordId, guild.idLong, stake, side)
        WagerCommandEmbeds.reply(event, CoinflipEmbeds.outcome(outcome), deleteDelay)
    }

    private fun parseSide(raw: String?): Coinflip.Side? = when (raw) {
        SIDE_HEADS -> Coinflip.Side.HEADS
        SIDE_TAILS -> Coinflip.Side.TAILS
        else -> null
    }
}
