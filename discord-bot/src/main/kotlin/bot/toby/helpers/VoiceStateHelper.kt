package bot.toby.helpers

import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent

object VoiceStateHelper {

    fun muteOrUnmuteMembers(
        member: Member?,
        requestingUserDto: database.dto.UserDto,
        event: SlashCommandInteractionEvent,
        deleteDelay: Int?,
        guild: Guild,
        muteTargets: Boolean
    ) {
        member?.voiceState?.channel?.members?.forEach { target ->
            val action = if (muteTargets) {
                "mute"
            } else "unmute"
            if (!member.canInteract(target!!) || !member.hasPermission(Permission.VOICE_MUTE_OTHERS) || !requestingUserDto.superUser) {
                event.hook
                    .sendMessage("You aren't allowed to $action ${target.effectiveName}")
                    .queue(core.command.Command.Companion.invokeDeleteOnMessageResponse(deleteDelay ?: 0))
                return
            }
            val bot = guild.selfMember
            if (!bot.hasPermission(Permission.VOICE_MUTE_OTHERS)) {
                event.hook
                    .sendMessage("I'm not allowed to $action ${target.effectiveName}")
                    .queue(core.command.Command.Companion.invokeDeleteOnMessageResponse(deleteDelay ?: 0))
                return
            }
            guild.mute(target, muteTargets)
                .reason(if(muteTargets) "Muted" else "Unmuted")
                .queue()
        }
    }
}