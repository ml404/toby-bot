package bot.toby.button.buttons.music

import bot.toby.helpers.IntroHelper
import bot.toby.intro.IntroListView
import core.button.Button
import core.button.ButtonContext
import database.dto.user.UserDto
import net.dv8tion.jda.api.components.buttons.ButtonStyle
import net.dv8tion.jda.api.entities.Member
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import net.dv8tion.jda.api.components.buttons.Button as JdaButton

/**
 * Shows a member's intros, from wherever their name already appears.
 *
 * The half of the cross-link that runs profile → intros. A profile card is a
 * picture and therefore a dead end; this is the one thing on it you can
 * actually go and hear.
 *
 * Open to anyone, for the same reason the **View intros** entry is: an intro
 * plays out loud to the whole channel on every join, so there is nothing here
 * that looking could reveal.
 */
@Component
class ViewIntrosButton @Autowired constructor(
    private val introHelper: IntroHelper,
) : Button {

    override val name: String = BUTTON_NAME
    override val description: String = "Show a member's intros."

    override fun handle(ctx: ButtonContext, requestingUserDto: UserDto, deleteDelay: Int) {
        val event = ctx.event
        logger.setGuildAndMemberContext(ctx.guild, ctx.member)

        val targetId = event.componentId.substringAfter(':').toLongOrNull()
            ?: return reply(ctx, "That button has lost track of whose intros it was for — open the menu again.")

        val target = ctx.guild.getMemberById(targetId)
            ?: return reply(ctx, "They don't seem to be in this server any more.")

        val isSelf = targetId == requestingUserDto.discordId
        // Your own list is already in hand; anyone else's is a lookup.
        val targetDto = if (isSelf) requestingUserDto else introHelper.findUserById(targetId, ctx.guild.idLong)
        val view = IntroListView.of(target, targetDto.musicDtos, isSelf)

        logger.info { "Showed ${targetDto.musicDtos.size} intro(s) of $targetId to ${event.user.idLong}" }

        event.hook.sendMessageEmbeds(view.embed)
            .addComponents(view.rows)
            .setEphemeral(true)
            .queue()
    }

    private fun reply(ctx: ButtonContext, message: String) {
        ctx.event.hook.sendMessage(message).setEphemeral(true).queue()
    }

    companion object {
        const val BUTTON_NAME = "viewintros"

        fun button(target: Member): JdaButton =
            JdaButton.of(ButtonStyle.SECONDARY, "$BUTTON_NAME:${target.idLong}", "🎵 Intros")
    }
}
