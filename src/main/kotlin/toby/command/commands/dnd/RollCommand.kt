package toby.command.commands.dnd

import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.interactions.callbacks.IReplyCallback
import net.dv8tion.jda.api.interactions.commands.OptionMapping
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import net.dv8tion.jda.api.interactions.components.buttons.Button
import net.dv8tion.jda.api.requests.restaction.WebhookMessageCreateAction
import toby.command.CommandContext
import toby.command.ICommand.Companion.invokeDeleteOnMessageResponse
import toby.helpers.DnDHelper
import toby.jpa.dto.UserDto
import java.util.*

class RollCommand(private val dndHelper: DnDHelper) : IDnDCommand {
    private val DICE_NUMBER = "number"
    private val DICE_TO_ROLL = "amount"
    private val MODIFIER = "modifier"
    override fun handle(ctx: CommandContext, requestingUserDto: UserDto, deleteDelay: Int?) {
        val event = ctx.event
        val diceValueOptional =
            Optional.ofNullable(event.getOption(DICE_NUMBER)).map { obj: OptionMapping -> obj.asInt }
        val diceToRollOptional =
            Optional.ofNullable(event.getOption(DICE_TO_ROLL)).map { obj: OptionMapping -> obj.asInt }
        val diceModifierOptional =
            Optional.ofNullable(event.getOption(MODIFIER)).map { obj: OptionMapping -> obj.asInt }
        val diceValue = diceValueOptional.orElse(20)
        val diceToRollInput = diceToRollOptional.orElse(1)
        val diceToRoll = if (diceToRollInput < 1) 1 else diceToRollInput
        val modifier = diceModifierOptional.orElse(0)
        handleDiceRoll(event, diceValue, diceToRoll, modifier).queue(invokeDeleteOnMessageResponse(deleteDelay!!))
    }

    fun handleDiceRoll(
        event: IReplyCallback,
        diceValue: Int,
        diceToRoll: Int,
        modifier: Int
    ): WebhookMessageCreateAction<Message> {
        event.deferReply().queue()
        val sb = buildStringForDiceRoll(diceValue, diceToRoll, modifier)
        val embedBuilder = EmbedBuilder()
            .addField(
                MessageEmbed.Field(
                    String.format("%dd%d + %d", diceToRoll, diceValue, modifier),
                    sb.toString(),
                    true
                )
            )
            .setColor(0x00FF00) // Green color
        val rollD20 = Button.primary("$name:20, 1, 0", "Roll D20")
        val rollD10 = Button.primary("$name:10, 1, 0", "Roll D10")
        val rollD6 = Button.primary("$name:6, 1, 0", "Roll D6")
        val rollD4 = Button.primary("$name:4, 1, 0", "Roll D4")
        return event.hook
            .sendMessageEmbeds(embedBuilder.build())
            .addActionRow(Button.primary("resend_last_request", "Click to Reroll"), rollD20, rollD10, rollD6, rollD4)
    }

    private fun buildStringForDiceRoll(diceValue: Int, diceToRoll: Int, modifier: Int): StringBuilder {
        val sb = StringBuilder()
        val rollTotal = dndHelper.rollDice(diceValue, diceToRoll)
        sb.append(String.format("Your final roll total was '%d' (%d + %d).", rollTotal + modifier, rollTotal, modifier))
        return sb
    }

    override val name: String
        get() = "roll"
    override val description: String
        get() = "Roll an X sided dice Y times with a Z modifier. (Default 20 sided dice, 1 roll and 0 modifier)"

    override val optionData: List<OptionData>
        get() {
            val diceNumberOption =
                OptionData(OptionType.INTEGER, DICE_NUMBER, "What sided dice would you like to roll?")
            val diceToRollOption = OptionData(OptionType.INTEGER, DICE_TO_ROLL, "How many dice would you like to roll?")
            val modifierOption = OptionData(OptionType.INTEGER, MODIFIER, "What modifier applies to your roll?")
            return listOf(diceNumberOption, diceToRollOption, modifierOption)
        }

}
