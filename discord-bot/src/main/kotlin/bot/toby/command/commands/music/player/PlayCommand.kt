package bot.toby.command.commands.music.player

import bot.toby.command.commands.music.MusicCommand
import bot.toby.helpers.MusicPlayerHelper
import bot.toby.lavaplayer.PlayerManager
import core.command.CommandContext
import database.dto.UserDto
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData
import org.springframework.stereotype.Component

@Component
class PlayCommand : MusicCommand {

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

        if (!requestingUserDto.musicPermission) {
            sendErrorMessage(event, deleteDelay)
            return
        }

        if (MusicCommand.isInvalidChannelStateForCommand(ctx, deleteDelay)) return

        val sub = event.subcommandName
        val guild = event.guild ?: return
        val musicManager = instance.getMusicManager(guild)

        val startPosition = MusicPlayerHelper.adjustTrackPlayingTimes(
            event.getOption(START_POSITION)?.asLong ?: 0L
        )
        val volume = event.getOption(VOLUME)?.asInt ?: musicManager.audioPlayer.volume

        when (sub) {
            "intro" -> MusicPlayerHelper.playUserIntro(
                requestingUserDto, guild, event, deleteDelay, startPosition, event.member
            )

            "link" -> {
                var link = event.getOption(LINK)?.asString ?: ""
                if (link.contains("youtube") && MusicPlayerHelper.isUrl(link).isEmpty()) {
                    link = "ytsearch:$link"
                }
                instance.loadAndPlay(guild, event, link, true, deleteDelay, startPosition, volume)
            }

            else -> event.hook.sendMessage("Unknown subcommand.").queue()
        }
    }

    override val name: String
        get() = "play"
    override val description: String
        get() = "Plays a song. You may optionally specify a start time"
    override val subCommands: List<SubcommandData>
        get() {
            val linkSub = SubcommandData(LINK, "Play a song from a link")
                .addOption(OptionType.STRING, LINK, "The URL or search term", true)
                .addOption(OptionType.NUMBER, START_POSITION, "Start position in seconds")
                .addOption(OptionType.INTEGER, VOLUME, "Volume to play at")

            val introSub = SubcommandData(INTRO, "Play your user intro")
                .addOption(OptionType.NUMBER, START_POSITION, "Start position in seconds")
                .addOption(OptionType.INTEGER, VOLUME, "Volume to play at")

            return listOf(linkSub, introSub)
        }

    companion object {
        private val INTRO = "intro"
        private val LINK = "link"
        private val VOLUME = "volume"
        private val START_POSITION = "start"
    }
}