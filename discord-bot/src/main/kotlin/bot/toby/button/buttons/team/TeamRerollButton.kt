package bot.toby.button.buttons.team

import bot.toby.command.commands.misc.TeamCommand
import core.button.Button
import core.button.ButtonContext
import database.dto.TeamSplitSessionDto
import database.dto.UserDto
import database.service.guild.TeamSplitSessionService
import database.service.guild.decodeTeamNames
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class TeamRerollButton @Autowired constructor(
    private val teamSplitSessionService: TeamSplitSessionService,
) : Button {

    override val name: String = TeamCommand.BUTTON_REROLL
    override val description: String = "Re-shuffle a team split preview, keeping the same roster and team count."
    override val defersReply: Boolean = false

    override fun handle(ctx: ButtonContext, requestingUserDto: UserDto, deleteDelay: Int) {
        val event = ctx.event
        event.deferEdit().queue()
        val uuid = decodeUuid(event.componentId) ?: run {
            event.hook.sendMessage("That preview is no longer valid.").setEphemeral(true).queue()
            return
        }
        val session = teamSplitSessionService.getSession(uuid) ?: run {
            event.hook.editOriginal("That preview expired or was cancelled.")
                .setEmbeds(emptyList())
                .setComponents()
                .queue()
            return
        }
        if (session.lastAction == TeamSplitSessionDto.ACTION_CONFIRMED) {
            event.hook.sendMessage("Already confirmed — start a new `/team split` to reroll.")
                .setEphemeral(true).queue()
            return
        }
        if (session.lastAction == TeamSplitSessionDto.ACTION_CANCELLED) {
            event.hook.sendMessage("That preview was cancelled.").setEphemeral(true).queue()
            return
        }

        val guild = ctx.guild
        val memberIds = session.memberIds.split(',').mapNotNull { it.trim().toLongOrNull() }
        val resolvedMembers = memberIds.mapNotNull { guild.getMemberById(it) }
        val teamNames = decodeTeamNames(session.teamNames)

        // Rebuild the preview with a fresh shuffle. Member list, count,
        // and team names stay frozen from the original modal submission
        // so Confirm still produces the labels the user agreed to.
        val newAssignments = TeamCommand.split(resolvedMembers, session.teamCount)
            .map { team -> team.mapNotNull { it?.idLong } }
        teamSplitSessionService.updateAssignments(uuid, newAssignments)

        val embed = TeamCommand.buildPreviewEmbed(guild, teamNames, newAssignments)
        event.hook.editOriginalEmbeds(embed)
            .setComponents(TeamCommand.buildActionRow(uuid))
            .queue()
    }

    private fun decodeUuid(componentId: String): UUID? {
        val raw = componentId.substringAfter(":", "")
        if (raw.isEmpty()) return null
        return runCatching { UUID.fromString(raw) }.getOrNull()
    }
}
