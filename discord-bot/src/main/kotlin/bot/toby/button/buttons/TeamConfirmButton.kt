package bot.toby.button.buttons

import bot.toby.command.commands.misc.TeamCommand
import bot.toby.modal.modals.TeamSplitModal
import core.button.Button
import core.button.ButtonContext
import database.dto.TeamSplitSessionDto
import database.dto.UserDto
import database.service.TeamSplitSessionService
import database.service.decodeAssignments
import database.service.decodeTeamNames
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel
import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class TeamConfirmButton @Autowired constructor(
    private val teamSplitSessionService: TeamSplitSessionService,
) : Button {

    override val name: String = TeamCommand.BUTTON_CONFIRM
    override val description: String = "Confirm a team split preview: create voice channels and move members."
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

        // Double-click protection: if a previous click already confirmed,
        // don't create another set of channels.
        if (session.lastAction == TeamSplitSessionDto.ACTION_CONFIRMED) {
            event.hook.sendMessage("Teams already created for this preview.")
                .setEphemeral(true).queue()
            return
        }
        if (session.lastAction == TeamSplitSessionDto.ACTION_CANCELLED) {
            event.hook.sendMessage("That preview was cancelled.").setEphemeral(true).queue()
            return
        }

        val guild = ctx.guild
        val assignments = decodeAssignments(session.assignments)
        val teamNames = decodeTeamNames(session.teamNames)

        assignments.forEachIndexed { index, memberIds ->
            val teamName = teamNames.getOrNull(index) ?: "${TeamSplitModal.DEFAULT_PREFIX} ${index + 1}"
            val channel: VoiceChannel = guild.createVoiceChannel(teamName)
                .setBitrate(guild.maxBitrate).complete()
            memberIds.forEach { id ->
                val target = guild.getMemberById(id) ?: return@forEach
                guild.moveVoiceMember(target, channel as AudioChannel).queue(
                    { /* moved */ },
                    { /* member not in voice / left — skip silently */ },
                )
            }
        }

        teamSplitSessionService.markConfirmed(uuid)

        val resultEmbed = TeamCommand.buildResultEmbed(guild, teamNames, assignments)
        event.hook.editOriginalEmbeds(resultEmbed)
            .setComponents(TeamCommand.buildDisabledActionRow(uuid))
            .queue()
    }

    private fun decodeUuid(componentId: String): UUID? {
        val raw = componentId.substringAfter(":", "")
        if (raw.isEmpty()) return null
        return runCatching { UUID.fromString(raw) }.getOrNull()
    }
}
