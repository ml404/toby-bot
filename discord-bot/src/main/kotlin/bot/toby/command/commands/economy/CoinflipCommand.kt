package bot.toby.command.commands.economy

import core.command.CommandContext
import database.dto.UserDto
import database.economy.Coinflip
import database.service.CoinflipService
import database.service.CoinflipService.FlipOutcome
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

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
) : EconomyCommand {

    override val name: String = "coinflip"
    override val description: String =
        "Flip a coin for double-or-nothing. Bet ${Coinflip.MIN_STAKE}-${Coinflip.MAX_STAKE} credits."

    companion object {
        private const val OPT_SIDE = "side"
        private const val OPT_STAKE = "stake"
        private const val SIDE_HEADS = "HEADS"
        private const val SIDE_TAILS = "TAILS"
        private const val TITLE = "🪙 Coinflip"
    }

    override val optionData: List<OptionData> = listOf(
        OptionData(OptionType.STRING, OPT_SIDE, "Heads or tails", true)
            .addChoice("Heads", SIDE_HEADS)
            .addChoice("Tails", SIDE_TAILS),
        OptionData(OptionType.INTEGER, OPT_STAKE, "Credits to wager (10–1000)", true)
            .setMinValue(Coinflip.MIN_STAKE)
            .setMaxValue(Coinflip.MAX_STAKE)
    )

    override fun handle(ctx: CommandContext, requestingUserDto: UserDto, deleteDelay: Int) {
        val event = ctx.event
        event.deferReply().queue()

        val guild = event.guild ?: run {
            WagerCommandEmbeds.replyError(event, TITLE, "This command can only be used in a server.", deleteDelay); return
        }
        val side = parseSide(event.getOption(OPT_SIDE)?.asString) ?: run {
            WagerCommandEmbeds.replyError(event, TITLE, "Pick a side: heads or tails.", deleteDelay); return
        }
        val stake = event.getOption(OPT_STAKE)?.asLong ?: run {
            WagerCommandEmbeds.replyError(event, TITLE, "You must specify a stake.", deleteDelay); return
        }

        val outcome = coinflipService.flip(requestingUserDto.discordId, guild.idLong, stake, side)
        WagerCommandEmbeds.reply(event, embedFor(outcome), deleteDelay)
    }

    private fun parseSide(raw: String?): Coinflip.Side? = when (raw) {
        SIDE_HEADS -> Coinflip.Side.HEADS
        SIDE_TAILS -> Coinflip.Side.TAILS
        else -> null
    }

    private fun embedFor(outcome: FlipOutcome) = when (outcome) {
        is FlipOutcome.Win -> EmbedBuilder()
            .setTitle("🪙 ${outcome.landed.display}!")
            .setDescription("You called **${outcome.predicted.display}** and won **+${outcome.net} credits**.")
            .addField("New balance", "${outcome.newBalance} credits", true)
            .setColor(WagerCommandColors.WIN)
            .build()

        is FlipOutcome.Lose -> EmbedBuilder()
            .setTitle("🪙 ${outcome.landed.display}!")
            .setDescription("You called **${outcome.predicted.display}**. Lost **${outcome.stake} credits**.")
            .addField("New balance", "${outcome.newBalance} credits", true)
            .setColor(WagerCommandColors.LOSE)
            .build()

        is FlipOutcome.InsufficientCredits -> WagerCommandEmbeds.failureEmbed(
            TITLE, WagerCommandFailure.InsufficientCredits(outcome.stake, outcome.have)
        )
        is FlipOutcome.InsufficientCoinsForTopUp -> WagerCommandEmbeds.failureEmbed(
            TITLE, WagerCommandFailure.InsufficientCoinsForTopUp(outcome.needed, outcome.have)
        )
        is FlipOutcome.InvalidStake -> WagerCommandEmbeds.failureEmbed(
            TITLE, WagerCommandFailure.InvalidStake(outcome.min, outcome.max)
        )
        FlipOutcome.UnknownUser -> WagerCommandEmbeds.failureEmbed(TITLE, WagerCommandFailure.UnknownUser)
    }
}
