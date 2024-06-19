package toby.command.commands.music

import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.interactions.commands.OptionMapping
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import toby.command.CommandContext
import toby.command.ICommand.Companion.invokeDeleteOnMessageResponse
import toby.helpers.MusicPlayerHelper
import toby.helpers.URLHelper
import toby.jpa.dto.UserDto
import toby.lavaplayer.PlayerManager
import java.util.*

class NowDigOnThisCommand : IMusicCommand {
    private val LINK = "link"
    private val START_POSITION = "start"
    private val VOLUME = "volume"
    override fun handle(ctx: CommandContext, requestingUserDto: UserDto, deleteDelay: Int?) {
        handleMusicCommand(ctx, PlayerManager.instance, requestingUserDto, deleteDelay)
    }

    override fun handleMusicCommand(ctx: CommandContext, instance: PlayerManager, requestingUserDto: UserDto, deleteDelay: Int?) {
        val event = ctx.event
        event.deferReply().queue()
        if (requestingUserDto.digPermission) {
            val linkOptional = Optional.ofNullable(event.getOption(LINK)).map { obj: OptionMapping -> obj.asString }
            if (linkOptional.isEmpty) {
                event.hook.sendMessageFormat("Correct usage is `%snowdigonthis <youtube linkOptional>`", "/").queue(invokeDeleteOnMessageResponse(deleteDelay!!))
                return
            }
            if (IMusicCommand.isInvalidChannelStateForCommand(ctx, deleteDelay)) return
            var link = linkOptional.get()
            if (link.contains("youtube") && !URLHelper.isValidURL(link)) link = "ytsearch:$linkOptional"
            val startPosition = MusicPlayerHelper.adjustTrackPlayingTimes(Optional.ofNullable(event.getOption(START_POSITION)).map { obj: OptionMapping -> obj.asLong }.orElse(0L))
            val musicManager = instance.getMusicManager(ctx.guild)
            val volume = Optional.ofNullable(event.getOption(VOLUME)).map { obj: OptionMapping -> obj.asInt }.orElse(musicManager.audioPlayer.volume)
            if (musicManager.scheduler.queue.isEmpty()) {
                musicManager.audioPlayer.volume = volume
            }
            instance.loadAndPlay(ctx.guild, event, link, false, deleteDelay ?: 0, startPosition, volume)
        } else sendErrorMessage(event, deleteDelay)
    }

    override val name: String get() = "nowdigonthis"
    override val description: String get() = "Plays a song which cannot be skipped"

    override fun getErrorMessage(name: String?): String = "I'm gonna put some dirt in your eye $name"

    fun sendErrorMessage(event: SlashCommandInteractionEvent, deleteDelay: Int?) {
        event.hook.sendMessage(getErrorMessage(event.member!!.effectiveName)).queue(invokeDeleteOnMessageResponse(deleteDelay!!))
    }

    override val optionData: List<OptionData>
        get() {
            val linkArg = OptionData(OptionType.STRING, LINK, "Link to play that cannot be stopped unless requested by a super user", true)
            val startPositionArg = OptionData(OptionType.NUMBER, START_POSITION, "Start position of the track in seconds")
            val volume = OptionData(OptionType.INTEGER, VOLUME, "Volume to play at")
            return listOf(linkArg, volume, startPositionArg)
        }
}