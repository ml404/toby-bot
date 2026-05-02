package bot.toby.command.commands.economy

import core.command.Command.Companion.invokeDeleteOnMessageResponse
import core.command.CommandContext
import database.dto.UserDto
import database.economy.Baccarat
import database.service.BaccaratService
import net.dv8tion.jda.api.components.actionrow.ActionRow
import net.dv8tion.jda.api.components.buttons.Button
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

/**
 * `/baccarat stake:<int>` — posts an embed prompting the side bet
 * (Player / Banker / Tie) and three buttons. The wager only settles
 * when the player picks a side. No mid-hand decisions: once the side is
 * chosen, both hands deal automatically per the Punto Banco tableau in
 * [Baccarat.play] and the round resolves in-place. Mirrors `/highlow`'s
 * post-then-button shape.
 *
 * The button click is handled by [bot.toby.button.buttons.BaccaratButton],
 * which decodes the side + stake + owning user from the component ID
 * and resolves via [BaccaratService.play].
 */
@Component
class BaccaratCommand @Autowired constructor(
    private val baccaratService: BaccaratService
) : EconomyCommand {

    override val name: String = "baccarat"
    override val description: String =
        "Pick Player, Banker, or Tie — both hands deal automatically. Bet ${Baccarat.MIN_STAKE}-${Baccarat.MAX_STAKE} credits."

    companion object {
        private const val OPT_STAKE = "stake"
    }

    override val optionData: List<OptionData> = listOf(
        OptionData(OptionType.INTEGER, OPT_STAKE, "Credits to wager (${Baccarat.MIN_STAKE}-${Baccarat.MAX_STAKE})", true)
            .setMinValue(Baccarat.MIN_STAKE)
            .setMaxValue(Baccarat.MAX_STAKE)
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

        val userId = requestingUserDto.discordId
        val sideButtons = Baccarat.Side.entries.map { side ->
            Button.primary(
                BaccaratEmbeds.sideButtonId(side, stake, userId),
                "${side.display} (${WagerCommandEmbeds.multiplierLabel(baccaratService.previewMultiplier(side))})"
            )
        }

        event.hook.sendMessageEmbeds(BaccaratEmbeds.promptEmbed(stake))
            .addComponents(ActionRow.of(sideButtons))
            .queue()
    }

    private fun replyError(
        event: SlashCommandInteractionEvent,
        message: String,
        deleteDelay: Int
    ) {
        event.hook.sendMessageEmbeds(BaccaratEmbeds.errorEmbed(message))
            .queue(invokeDeleteOnMessageResponse(deleteDelay))
    }
}
