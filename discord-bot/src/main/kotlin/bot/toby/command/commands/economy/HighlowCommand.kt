package bot.toby.command.commands.economy

import core.command.Command.Companion.invokeDeleteOnMessageResponse
import core.command.CommandContext
import database.dto.UserDto
import database.economy.Highlow
import database.service.HighlowService
import net.dv8tion.jda.api.components.actionrow.ActionRow
import net.dv8tion.jda.api.components.buttons.Button
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

/**
 * `/highlow stake:<int>` — deals an anchor card and asks the player to
 * call HIGHER or LOWER via buttons. The wager only settles when the
 * player picks a direction. Mirrors the web `/casino/{guildId}/highlow`
 * flow so the player isn't forced to commit blind.
 *
 * The button click is handled by [bot.toby.button.buttons.HighlowButton],
 * which decodes the anchor + stake from the component ID and resolves
 * via [HighlowService.play] (anchored entry point).
 */
@Component
class HighlowCommand @Autowired constructor(
    private val highlowService: HighlowService
) : EconomyCommand {

    override val name: String = "highlow"
    override val description: String =
        "Predict if the next card is higher or lower than the anchor. Bet ${Highlow.MIN_STAKE}-${Highlow.MAX_STAKE} credits."

    companion object {
        private const val OPT_STAKE = "stake"
    }

    override val optionData: List<OptionData> = listOf(
        OptionData(OptionType.INTEGER, OPT_STAKE, "Credits to wager (${Highlow.MIN_STAKE}-${Highlow.MAX_STAKE})", true)
            .setMinValue(Highlow.MIN_STAKE)
            .setMaxValue(Highlow.MAX_STAKE)
    )

    override fun handle(ctx: CommandContext, requestingUserDto: UserDto, deleteDelay: Int) {
        val event = ctx.event
        event.deferReply().queue()

        if (event.guild == null) {
            replyError(event, "This command can only be used in a server.", deleteDelay); return
        }
        val stake = event.getOption(OPT_STAKE)?.asLong ?: run {
            replyError(event, "You must specify a stake.", deleteDelay); return
        }

        val anchor = highlowService.dealAnchor()
        val userId = requestingUserDto.discordId
        val higher = Button.primary(
            HighlowEmbeds.directionButtonId(Highlow.Direction.HIGHER, anchor, stake, userId),
            Highlow.Direction.HIGHER.display
        )
        val lower = Button.primary(
            HighlowEmbeds.directionButtonId(Highlow.Direction.LOWER, anchor, stake, userId),
            Highlow.Direction.LOWER.display
        )

        event.hook.sendMessageEmbeds(HighlowEmbeds.anchorEmbed(anchor, stake))
            .addComponents(ActionRow.of(higher, lower))
            .queue()
    }

    private fun replyError(
        event: SlashCommandInteractionEvent,
        message: String,
        deleteDelay: Int
    ) {
        event.hook.sendMessageEmbeds(HighlowEmbeds.errorEmbed(message))
            .queue(invokeDeleteOnMessageResponse(deleteDelay))
    }
}
