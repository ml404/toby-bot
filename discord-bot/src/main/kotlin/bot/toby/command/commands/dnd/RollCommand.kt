package bot.toby.command.commands.dnd

import bot.toby.helpers.DnDHelper
import bot.toby.helpers.intOption
import common.discord.embed
import common.discord.field
import common.events.CampaignEventType
import core.command.Command.Companion.invokeDeleteOnMessageResponse
import core.command.CommandContext
import database.dto.UserDto
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.interactions.callbacks.IReplyCallback
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import net.dv8tion.jda.api.components.actionrow.ActionRow
import net.dv8tion.jda.api.components.buttons.Button
import net.dv8tion.jda.api.requests.restaction.WebhookMessageCreateAction
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import web.service.SessionLogPublisher
import java.awt.Color

@Component
class RollCommand @Autowired constructor(
    private val dndHelper: DnDHelper,
    private val sessionLog: SessionLogPublisher
) : DnDCommand {
    companion object {
        private const val DICE_NUMBER = "number"
        private const val DICE_TO_ROLL = "amount"
        private const val MODIFIER = "modifier"
    }
    override fun handle(ctx: CommandContext, requestingUserDto: UserDto, deleteDelay: Int) {
        val event = ctx.event
        val diceValue = event.intOption(DICE_NUMBER, 20)
        val diceToRoll = event.intOption(DICE_TO_ROLL, 1).coerceAtLeast(1)
        val modifier = event.intOption(MODIFIER, 0)
        handleDiceRoll(event, diceValue, diceToRoll, modifier).queue(invokeDeleteOnMessageResponse(deleteDelay))
    }

    fun handleDiceRoll(
        event: IReplyCallback,
        diceValue: Int,
        diceToRoll: Int,
        modifier: Int
    ): WebhookMessageCreateAction<Message> {
        event.deferReply().queue()
        val rollTotal = dndHelper.rollDice(diceValue, diceToRoll)
        publishRollEvent(event, diceValue, diceToRoll, modifier, rollTotal)
        val rollSummary = String.format(
            "Your final roll total was '%d' (%d + %d).",
            rollTotal + modifier, rollTotal, modifier
        )
        val rollEmbed = embed(color = Color.GREEN) {
            field(
                name = String.format("%dd%d + %d", diceToRoll, diceValue, modifier),
                value = rollSummary,
                inline = true,
            )
        }
        val rollD20 = Button.primary("$name:20, 1, 0", "Roll D20")
        val rollD10 = Button.primary("$name:10, 1, 0", "Roll D10")
        val rollD6 = Button.primary("$name:6, 1, 0", "Roll D6")
        val rollD4 = Button.primary("$name:4, 1, 0", "Roll D4")
        return event.hook
            .sendMessageEmbeds(rollEmbed)
            .addComponents(ActionRow.of(Button.primary("resend_last_request", "Click to Reroll"), rollD20, rollD10, rollD6, rollD4))
    }

    private fun publishRollEvent(
        event: IReplyCallback,
        diceValue: Int,
        diceToRoll: Int,
        modifier: Int,
        rawTotal: Int
    ) {
        val guild = event.guild ?: return
        sessionLog.publish(
            guildId = guild.idLong,
            type = CampaignEventType.ROLL,
            actorDiscordId = event.user.idLong,
            actorName = event.member?.effectiveName ?: event.user.effectiveName,
            payload = mapOf(
                "sides" to diceValue,
                "count" to diceToRoll,
                "modifier" to modifier,
                "rawTotal" to rawTotal,
                "total" to rawTotal + modifier
            )
        )
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
