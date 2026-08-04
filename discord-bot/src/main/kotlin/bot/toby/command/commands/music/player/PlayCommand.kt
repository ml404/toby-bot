package bot.toby.command.commands.music.player

import bot.toby.command.commands.music.MusicCommand
import bot.toby.helpers.MusicPlayerHelper
import bot.toby.helpers.UserDtoHelper
import bot.toby.lavaplayer.PlayerManager
import bot.toby.lavaplayer.SearchPrefixResolver
import bot.toby.util.adjustTrackPlayingTimes
import core.command.CommandContext
import database.dto.user.UserDto
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

@Component
class PlayCommand @Autowired constructor(
    private val userDtoHelper: UserDtoHelper,
) : MusicCommand {

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

        val event = ctx.event
        val sub = event.subcommandName
        val guild = event.guild ?: return
        val musicManager = instance.getMusicManager(guild)

        val startPosition = adjustTrackPlayingTimes(
            event.getOption(START_POSITION)?.asLong ?: 0L
        )
        val volume = event.getOption(VOLUME)?.asInt ?: musicManager.audioPlayer.volume

        when (sub) {
            "intro" -> playIntro(event, guild, requestingUserDto, deleteDelay, startPosition)

            "link" -> {
                val link = SearchPrefixResolver.resolve(event.getOption(LINK)?.asString.orEmpty())
                instance.loadAndPlay(guild, event, link, true, deleteDelay, startPosition, volume)
            }

            else -> event.hook.sendMessage("Unknown subcommand.").queue()
        }
    }

    /**
     * Plays an intro on demand — yours by default, or anyone's via `user:`.
     *
     * Previewing someone else's needs no permission for the same reason
     * `/listintros user:` needs none: it is the sound they have already chosen
     * to play to the whole channel every time they join. Hearing it on purpose
     * is strictly less intrusive than hearing it by surprise.
     */
    private fun playIntro(
        event: SlashCommandInteractionEvent,
        guild: Guild,
        requestingUserDto: UserDto,
        deleteDelay: Int,
        startPosition: Long,
    ) {
        val target = event.getOption(INTRO_USER)?.asMember
        val targetDto = if (target == null || target.idLong == event.user.idLong) {
            requestingUserDto
        } else {
            userDtoHelper.calculateUserDto(target.idLong, guild.idLong)
        }

        // playUserIntro only logs when there's nothing to play, which left the
        // caller staring at a deferred reply that never resolved.
        if (targetDto.musicDtos.isEmpty()) {
            val who = target?.let { "${it.effectiveName} hasn't" } ?: "You haven't"
            event.hook.sendMessage("$who set an intro on this server yet.").setEphemeral(true).queue()
            return
        }

        MusicPlayerHelper.playUserIntro(
            targetDto, guild, event, deleteDelay, startPosition, target ?: event.member
        )
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

            val introSub = SubcommandData(INTRO, "Play an intro — yours, or someone else's")
                .addOption(OptionType.USER, INTRO_USER, "Whose intro to play — defaults to yours")
                .addOption(OptionType.NUMBER, START_POSITION, "Start position in seconds")
                .addOption(OptionType.INTEGER, VOLUME, "Volume to play at")

            return listOf(linkSub, introSub)
        }

    companion object {
        private const val INTRO = "intro"
        private const val INTRO_USER = "user"
        private const val LINK = "link"
        private const val VOLUME = "volume"
        private const val START_POSITION = "start"
    }
}