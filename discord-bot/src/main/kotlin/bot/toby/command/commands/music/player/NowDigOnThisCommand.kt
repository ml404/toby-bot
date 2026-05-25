package bot.toby.command.commands.music.player

import bot.toby.command.commands.music.MusicCommand
import bot.toby.lavaplayer.PlayerManager
import bot.toby.lavaplayer.SearchPrefixResolver
import bot.toby.util.adjustTrackPlayingTimes
import core.command.Command.Companion.replyAndDelete
import core.command.CommandContext
import database.dto.user.UserDto
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import org.springframework.stereotype.Component

@Component
class NowDigOnThisCommand : MusicCommand {
    companion object {
        private const val LINK = "link"
        private const val START_POSITION = "start"
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
        val event = ctx.event

        if (!requestingUserDto.digPermission) {
            sendErrorMessage(event, deleteDelay)
            return
        }

        val linkOption = event.getOption(LINK)?.asString
        if (linkOption.isNullOrBlank()) {
            event.hook.replyAndDelete("Correct usage is `/nowdigonthis <youtube link>`", deleteDelay)
            return
        }

        if (MusicCommand.isInvalidChannelStateForCommand(ctx, deleteDelay)) return

        val link = SearchPrefixResolver.resolve(linkOption)

        val startPosition = adjustTrackPlayingTimes(event.getOption(START_POSITION)?.asLong ?: 0L)
        val musicManager = instance.getMusicManager(ctx.guild)
        val volume = event.getOption(VOLUME)?.asInt ?: musicManager.audioPlayer.volume

        if (musicManager.scheduler.queue.isEmpty()) {
            musicManager.audioPlayer.volume = volume
        }

        instance.loadAndPlay(ctx.guild, event, link, false, deleteDelay, startPosition, volume)
    }

    override val name: String get() = "nowdigonthis"
    override val description: String get() = "Plays a song which cannot be skipped"

    override fun getErrorMessage(name: String?): String = "I'm gonna put some dirt in your eye $name"

    override fun sendErrorMessage(event: SlashCommandInteractionEvent, deleteDelay: Int) {
        event.hook.replyAndDelete(getErrorMessage(event.member!!.effectiveName), deleteDelay)
    }

    override val optionData: List<OptionData>
        get() {
            val linkArg = OptionData(OptionType.STRING, LINK, "Link to play that cannot be stopped unless requested by a super user", true)
            val startPositionArg = OptionData(OptionType.NUMBER, START_POSITION, "Start position of the track in seconds")
            val volumeArg = OptionData(OptionType.INTEGER, VOLUME, "Volume to play at")
            return listOf(linkArg, volumeArg, startPositionArg)
        }
}