package bot.toby.command.commands.economy

import core.command.Command.Companion.invokeDeleteOnMessageResponse
import core.command.CommandContext
import database.dto.UserDto
import database.economy.SlotMachine
import database.service.SlotsService
import database.service.SlotsService.SpinOutcome
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import java.awt.Color

/**
 * `/slots stake:<int>` — first credit-spend gambling minigame. Calls
 * through to [SlotsService.spin], which is the same path the web
 * `/casino/{guildId}/slots` page uses, so the Discord and web surfaces
 * can't drift on payout maths or balance debit/credit semantics.
 */
@Component
class SlotsCommand @Autowired constructor(
    private val slotsService: SlotsService
) : EconomyCommand {

    override val name: String = "slots"
    override val description: String =
        "Spin a 3-reel slot machine. Bet ${SlotMachine.MIN_STAKE}-${SlotMachine.MAX_STAKE} credits per pull."

    companion object {
        private const val OPT_STAKE = "stake"
        private val WIN_COLOR = Color(87, 242, 135)   // #57F287 — same as market chart green
        private val LOSE_COLOR = Color(160, 160, 176) // #a0a0b0 — muted grey
        private val ERROR_COLOR = Color(237, 66, 69)  // #ED4245 — Discord red
    }

    override val optionData: List<OptionData> = listOf(
        OptionData(OptionType.INTEGER, OPT_STAKE, "Credits to wager (10–500)", true)
            .setMinValue(SlotMachine.MIN_STAKE)
            .setMaxValue(SlotMachine.MAX_STAKE)
    )

    override fun handle(ctx: CommandContext, requestingUserDto: UserDto, deleteDelay: Int) {
        val event = ctx.event
        event.deferReply().queue()

        val guild = event.guild ?: run {
            replyError(event, "This command can only be used in a server.", deleteDelay); return
        }
        val stake = event.getOption(OPT_STAKE)?.asLong ?: run {
            replyError(event, "You must specify a stake.", deleteDelay); return
        }

        val outcome = slotsService.spin(requestingUserDto.discordId, guild.idLong, stake)
        replyOutcome(event, outcome, deleteDelay)
    }

    private fun replyOutcome(
        event: SlashCommandInteractionEvent,
        outcome: SpinOutcome,
        deleteDelay: Int
    ) {
        val embed = when (outcome) {
            is SpinOutcome.Win -> EmbedBuilder()
                .setTitle("🎰 ${reelLine(outcome.symbols)}")
                .setDescription("**+${outcome.net} credits** (${outcome.multiplier}× on a ${outcome.stake} stake)")
                .addField("New balance", "${outcome.newBalance} credits", true)
                .setColor(WIN_COLOR)
                .build()

            is SpinOutcome.Lose -> EmbedBuilder()
                .setTitle("🎰 ${reelLine(outcome.symbols)}")
                .setDescription("Lost **${outcome.stake} credits**.")
                .addField("New balance", "${outcome.newBalance} credits", true)
                .setColor(LOSE_COLOR)
                .build()

            is SpinOutcome.InsufficientCredits -> errorEmbed(
                "Not enough credits. You need ${outcome.stake} but only have ${outcome.have}."
            )

            is SpinOutcome.InsufficientCoinsForTopUp -> errorEmbed(
                "Not enough credits, and not enough TOBY to cover. " +
                    "Need ${outcome.needed} TOBY, you have ${outcome.have}."
            )

            is SpinOutcome.InvalidStake -> errorEmbed(
                "Stake must be between ${outcome.min} and ${outcome.max} credits."
            )

            SpinOutcome.UnknownUser -> errorEmbed(
                "No user record yet. Try another TobyBot command first."
            )
        }
        event.hook.sendMessageEmbeds(embed).queue(invokeDeleteOnMessageResponse(deleteDelay))
    }

    private fun reelLine(symbols: List<SlotMachine.Symbol>): String =
        symbols.joinToString(separator = " ") { it.display }

    private fun errorEmbed(message: String) = EmbedBuilder()
        .setTitle("🎰 Slots")
        .setDescription(message)
        .setColor(ERROR_COLOR)
        .build()

    private fun replyError(
        event: SlashCommandInteractionEvent,
        message: String,
        deleteDelay: Int
    ) {
        event.hook.sendMessageEmbeds(errorEmbed(message)).queue(invokeDeleteOnMessageResponse(deleteDelay))
    }
}
