package bot.toby.command.commands.music.player

import bot.toby.command.CommandContext
import bot.toby.command.commands.music.IMusicCommand
import bot.toby.helpers.MusicPlayerHelper
import bot.toby.lavaplayer.PlayerManager
import net.dv8tion.jda.api.interactions.commands.OptionMapping
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import java.util.*

class PlayCommand : IMusicCommand {
    private val TYPE = "type"
    private val START_POSITION = "start"
    private val LINK = "link"
    private val INTRO = "intro"
    override fun handle(ctx: CommandContext, requestingUserDto: bot.database.dto.UserDto, deleteDelay: Int?) {
        handleMusicCommand(ctx, PlayerManager.instance, requestingUserDto, deleteDelay)
    }

    override fun handleMusicCommand(
        ctx: CommandContext,
        instance: PlayerManager,
        requestingUserDto: bot.database.dto.UserDto,
        deleteDelay: Int?
    ) {
        val event = ctx.event
        event.deferReply().queue()
        if (!requestingUserDto.musicPermission) {
            sendErrorMessage(event, deleteDelay!!)
            return
        }
        if (IMusicCommand.isInvalidChannelStateForCommand(ctx, deleteDelay)) return
        val musicManager = instance.getMusicManager(ctx.guild)
        val type = Optional.ofNullable(event.getOption(TYPE)).map { obj: OptionMapping -> obj.asString }.orElse(LINK)
        val guild = event.guild!!
        val currentVolume = musicManager.audioPlayer.volume
        val startPosition = MusicPlayerHelper.adjustTrackPlayingTimes(Optional.ofNullable(event.getOption(START_POSITION)).map { obj: OptionMapping -> obj.asLong }.orElse(0L))
        val volume = Optional.ofNullable(event.getOption(VOLUME)).map { obj: OptionMapping -> obj.asInt }.orElse(currentVolume)
        if (type == INTRO) {
            MusicPlayerHelper.playUserIntro(
                requestingUserDto,
                guild,
                event,
                deleteDelay ?: 0,
                startPosition,
                event.member
            )
        } else {
            var link = Optional.ofNullable(event.getOption(LINK)).map { obj: OptionMapping -> obj.asString }.orElse("")
            if (link.contains("youtube") && MusicPlayerHelper.isUrl(link).isEmpty()) {
                link = "ytsearch:$link"
            }
            instance.loadAndPlay(ctx.guild, event, link, true, deleteDelay!!, startPosition, volume)
        }
    }

    override val name: String
        get() = "play"
    override val description: String
        get() = "Plays a song. You may optionally specify a start time"
    override val optionData: List<OptionData>
        get() {
            val type = OptionData(OptionType.STRING, TYPE, "Type of thing you're playing (link or intro). Defaults to link")
            type.addChoice(LINK, LINK)
            type.addChoice(INTRO, INTRO)
            val link = OptionData(OptionType.STRING, LINK, "link you would like to play")
            val startPosition = OptionData(OptionType.NUMBER, START_POSITION, "Start position of the track in seconds (defaults to 0)")
            val volume = OptionData(OptionType.INTEGER, VOLUME, "Volume to play at")
            return listOf(link, type, startPosition, volume)
        }

    companion object {
        private const val VOLUME = "volume"
    }
}