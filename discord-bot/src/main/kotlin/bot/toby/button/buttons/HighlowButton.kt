package bot.toby.button.buttons

import bot.toby.command.commands.economy.HighlowEmbeds
import core.button.Button
import core.button.ButtonContext
import database.dto.UserDto
import database.service.HighlowService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

/**
 * Resolves a `/highlow` round once the player clicks HIGHER or LOWER.
 * Anchor + stake + direction + owning user are all encoded in the
 * component ID by [bot.toby.command.commands.economy.HighlowCommand], so
 * no server-side state is needed between the slash command and the
 * click. Edits the original message in place and removes the buttons
 * so the round can't be re-clicked.
 */
@Component
class HighlowButton @Autowired constructor(
    private val highlowService: HighlowService
) : Button {

    override val name: String get() = HighlowEmbeds.BUTTON_NAME
    override val description: String get() = "Resolves a /highlow round in the player's chosen direction."

    override fun handle(ctx: ButtonContext, requestingUserDto: UserDto, deleteDelay: Int) {
        val event = ctx.event
        val parsed = HighlowEmbeds.parseButtonId(event.componentId) ?: run {
            event.deferEdit().queue()
            return
        }

        if (event.user.idLong != parsed.userId) {
            event.reply("This isn't your /highlow round — run `/highlow` to start your own.")
                .setEphemeral(true)
                .queue()
            return
        }

        val outcome = highlowService.play(
            requestingUserDto.discordId,
            ctx.guild.idLong,
            parsed.stake,
            parsed.direction,
            parsed.anchor
        )

        event.editMessageEmbeds(HighlowEmbeds.outcomeEmbed(outcome))
            .setComponents()
            .queue()
    }
}
