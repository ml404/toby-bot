package bot.toby.command.commands.music.player

import bot.toby.command.CommandContext
import bot.toby.command.ICommand.Companion.invokeDeleteOnMessageResponse
import bot.toby.command.commands.music.IMusicCommand
import bot.toby.emote.Emotes
import bot.toby.lavaplayer.PlayerManager
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.interactions.commands.OptionMapping
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import org.springframework.stereotype.Component
import java.util.*

@Component
class SetVolumeCommand : IMusicCommand {
    private val VOLUME = "volume"
    override fun handle(ctx: CommandContext, requestingUserDto: database.dto.UserDto, deleteDelay: Int?) {
        handleMusicCommand(ctx, PlayerManager.instance, requestingUserDto, deleteDelay)
    }

    override fun handleMusicCommand(
        ctx: CommandContext,
        instance: PlayerManager,
        requestingUserDto: database.dto.UserDto,
        deleteDelay: Int?
    ) {
        val event = ctx.event
        event.deferReply().queue()
        if (!requestingUserDto.musicPermission) {
            sendErrorMessage(event, deleteDelay)
            return
        }
        if (IMusicCommand.isInvalidChannelStateForCommand(ctx, deleteDelay)) return
        val member = ctx.member
        setNewVolume(event, instance, member, requestingUserDto, deleteDelay)
    }

    private fun setNewVolume(
        event: SlashCommandInteractionEvent,
        instance: PlayerManager,
        member: Member?,
        requestingUserDto: database.dto.UserDto?,
        deleteDelay: Int?
    ) {
        val volumeArg = Optional.ofNullable(event.getOption(VOLUME)).map { obj: OptionMapping -> obj.asInt }.orElse(0)
        val hook = event.hook
        val musicManager = instance.getMusicManager(event.guild!!)
        if (volumeArg > 0) {
            if (instance.isCurrentlyStoppable || requestingUserDto!!.superUser) {
                val audioPlayer = musicManager.audioPlayer
                if (volumeArg > 100) {
                    hook.sendMessage(description).setEphemeral(true).queue(invokeDeleteOnMessageResponse(deleteDelay!!))
                    return
                }
                val oldVolume = audioPlayer.volume
                if (volumeArg == oldVolume) {
                    hook.sendMessageFormat("New volume and old volume are the same value, somebody shoot %s", member!!.effectiveName).setEphemeral(true).queue(invokeDeleteOnMessageResponse(deleteDelay!!))
                    return
                }
                instance.setPreviousVolume(oldVolume)
                audioPlayer.volume = volumeArg
                hook.sendMessageFormat("Changing volume from '%s' to '%s' \uD83D\uDD0A", oldVolume, volumeArg).setEphemeral(true).queue(invokeDeleteOnMessageResponse(deleteDelay!!))
            } else {
                sendErrorMessage(event, deleteDelay)
            }
        } else hook.sendMessage(description).setEphemeral(true).queue(invokeDeleteOnMessageResponse(deleteDelay!!))
    }

    fun sendErrorMessage(event: SlashCommandInteractionEvent, deleteDelay: Int?) {
        event.hook.sendMessageFormat("You aren't allowed to change the volume kid %s", event.guild!!.jda.getEmojiById(
            Emotes.TOBY)).setEphemeral(true).queue(invokeDeleteOnMessageResponse(deleteDelay!!))
    }

    override val name: String
        get() = "setvolume"
    override val description: String
        get() = "Set the volume of the audio player for the server to a percent value (between 1 and 100)"
    override val optionData: List<OptionData>
        get() = listOf(OptionData(OptionType.INTEGER, VOLUME, "Volume value between 1-100 to set the audio to", true))
}
