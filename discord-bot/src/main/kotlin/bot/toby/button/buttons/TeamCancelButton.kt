package bot.toby.button.buttons

import bot.toby.command.commands.misc.TeamCommand
import core.button.Button
import core.button.ButtonContext
import database.dto.UserDto
import database.service.guild.TeamSplitSessionService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class TeamCancelButton @Autowired constructor(
    private val teamSplitSessionService: TeamSplitSessionService,
) : Button {

    override val name: String = TeamCommand.BUTTON_CANCEL
    override val description: String = "Cancel a team split preview without creating any voice channels."
    override val defersReply: Boolean = false

    override fun handle(ctx: ButtonContext, requestingUserDto: UserDto, deleteDelay: Int) {
        val event = ctx.event
        event.deferEdit().queue()
        val uuid = decodeUuid(event.componentId)
        if (uuid != null) {
            teamSplitSessionService.markCancelled(uuid)
        }
        event.hook.editOriginal("Team split cancelled.")
            .setEmbeds(emptyList())
            .setComponents()
            .queue()
    }

    private fun decodeUuid(componentId: String): UUID? {
        val raw = componentId.substringAfter(":", "")
        if (raw.isEmpty()) return null
        return runCatching { UUID.fromString(raw) }.getOrNull()
    }
}
