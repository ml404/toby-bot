package bot.toby.button.buttons

import bot.toby.command.commands.economy.BaccaratEmbeds
import core.button.Button
import core.button.ButtonContext
import database.dto.UserDto
import database.service.BaccaratService
import net.dv8tion.jda.api.components.MessageTopLevelComponent
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

/**
 * Resolves a `/baccarat` round once the player clicks Player, Banker,
 * or Tie. Side + stake + owning user are encoded in the component ID
 * by [bot.toby.command.commands.economy.BaccaratCommand], so no server-
 * side state is needed between the slash command and the click. Edits
 * the original message in place and removes the buttons so the round
 * can't be re-clicked.
 */
@Component
class BaccaratButton @Autowired constructor(
    private val baccaratService: BaccaratService
) : Button {

    override val name: String get() = BaccaratEmbeds.BUTTON_NAME
    override val description: String get() = "Resolves a /baccarat round on the player's chosen side."

    override fun handle(ctx: ButtonContext, requestingUserDto: UserDto, deleteDelay: Int) {
        val event = ctx.event
        val parsed = BaccaratEmbeds.parseButtonId(event.componentId) ?: run {
            event.deferEdit().queue()
            return
        }

        if (requestingUserDto.discordId != parsed.userId) {
            event.reply("This isn't your /baccarat round — run `/baccarat` to start your own.")
                .setEphemeral(true)
                .queue()
            return
        }

        val outcome = baccaratService.play(
            requestingUserDto.discordId,
            ctx.guild.idLong,
            parsed.stake,
            parsed.side
        )

        event.editMessageEmbeds(BaccaratEmbeds.outcomeEmbed(outcome))
            .setComponents(emptyList<MessageTopLevelComponent>())
            .queue()
    }
}
