package bot.toby.command.commands.music.player

import bot.toby.command.commands.music.MusicCommand
import bot.toby.emote.Emotes
import bot.toby.lavaplayer.PlayerManager
import core.command.Command.Companion.replyEphemeralAndDelete
import core.command.CommandContext
import database.dto.user.UserDto
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.interactions.commands.OptionMapping
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import org.springframework.stereotype.Component
import java.util.*

@Component
class SetVolumeCommand : MusicCommand {
    companion object {
        private const val VOLUME = "volume"
    }
    override fun handle(ctx: CommandContext, requestingUserDto: UserDto, deleteDelay: Int) {
        handleMusicCommand(ctx, PlayerManager.instance, requestingUserDto, deleteDelay)
    }

    override fun handleMusicCommand(
        ctx: CommandContext,
        instance: PlayerManager,
        requestingUserDto: UserDto,
        deleteDelay: Int
    ) {
        if (!checkMusicPreconditions(ctx, requestingUserDto, deleteDelay)) return
        setNewVolume(ctx.event, instance, ctx.member, requestingUserDto, deleteDelay)
    }

    private fun setNewVolume(
        event: SlashCommandInteractionEvent,
        instance: PlayerManager,
        member: Member?,
        requestingUserDto: UserDto?,
        deleteDelay: Int
    ) {
        val volumeArg = Optional.ofNullable(event.getOption(VOLUME)).map { obj: OptionMapping -> obj.asInt }.orElse(0)
        val hook = event.hook
        val musicManager = instance.getMusicManager(event.guild!!)
        if (volumeArg > 0) {
            if (instance.isCurrentlyStoppable || requestingUserDto!!.superUser) {
                val audioPlayer = musicManager.audioPlayer
                if (volumeArg > 100) {
                    hook.replyEphemeralAndDelete(description, deleteDelay)
                    return
                }
                val oldVolume = audioPlayer.volume
                if (volumeArg == oldVolume) {
                    hook.replyEphemeralAndDelete(
                        "New volume and old volume are the same value, somebody shoot ${member!!.effectiveName}",
                        deleteDelay,
                    )
                    return
                }
                instance.setPreviousVolume(oldVolume)
                audioPlayer.volume = volumeArg
                hook.replyEphemeralAndDelete(
                    "Changing volume from '$oldVolume' to '$volumeArg' \uD83D\uDD0A",
                    deleteDelay,
                )
            } else {
                sendErrorMessage(event, deleteDelay)
            }
        } else hook.replyEphemeralAndDelete(description, deleteDelay)
    }

    override fun sendErrorMessage(event: SlashCommandInteractionEvent, deleteDelay: Int) {
        event.hook.replyEphemeralAndDelete(
            "You aren't allowed to change the volume kid ${event.guild!!.jda.getEmojiById(Emotes.TOBY)}",
            deleteDelay,
        )
    }

    override val name: String
        get() = "setvolume"
    override val description: String
        get() = "Set the volume of the audio player for the server to a percent value (between 1 and 100)"
    override val optionData: List<OptionData>
        get() = listOf(OptionData(OptionType.INTEGER, VOLUME, "Volume value between 1-100 to set the audio to", true))
}
