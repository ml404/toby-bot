package bot.toby.command.commands.music.player

import bot.toby.command.commands.music.MusicCommand
import bot.toby.helpers.MusicPlayerHelper
import bot.toby.helpers.URLHelper
import bot.toby.lavaplayer.PlayerManager
import core.command.Command.Companion.invokeDeleteOnMessageResponse
import core.command.CommandContext
import database.dto.UserDto
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import org.springframework.stereotype.Component

@Component
class NowDigOnThisCommand : MusicCommand {
    private val LINK = "link"
    private val START_POSITION = "start"
    private val VOLUME = "volume"

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
        event.deferReply().queue()

        if (!requestingUserDto.digPermission) {
            sendErrorMessage(event, deleteDelay)
            return
        }

        val linkOption = event.getOption(LINK)?.asString
        if (linkOption.isNullOrBlank()) {
            event.hook
                .sendMessage("Correct usage is `/nowdigonthis <youtube link>`")
                .queue(invokeDeleteOnMessageResponse(deleteDelay))
            return
        }

        if (MusicCommand.isInvalidChannelStateForCommand(ctx, deleteDelay)) return

        var link = linkOption
        if (link.contains("youtube") && !URLHelper.isValidURL(link)) {
            link = "ytsearch:$link"
        }

        val startPosition = MusicPlayerHelper.adjustTrackPlayingTimes(event.getOption(START_POSITION)?.asLong ?: 0L)
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
        event.hook.sendMessage(getErrorMessage(event.member!!.effectiveName))
            .queue(invokeDeleteOnMessageResponse(deleteDelay))
    }

    override val optionData: List<OptionData>
        get() {
            val linkArg = OptionData(OptionType.STRING, LINK, "Link to play that cannot be stopped unless requested by a super user", true)
            val startPositionArg = OptionData(OptionType.NUMBER, START_POSITION, "Start position of the track in seconds")
            val volumeArg = OptionData(OptionType.INTEGER, VOLUME, "Volume to play at")
            return listOf(linkArg, volumeArg, startPositionArg)
        }
}