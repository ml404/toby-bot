package bot.toby.command.commands.music.player

import database.dto.UserDto
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import bot.toby.command.CommandContext
import bot.toby.command.commands.music.IMusicCommand
import bot.toby.helpers.MusicPlayerHelper
import bot.toby.lavaplayer.PlayerManager

class SkipCommand : IMusicCommand {
    private val SKIP = "skip"
    override fun handle(ctx: CommandContext, requestingUserDto: UserDto, deleteDelay: Int?) {
        handleMusicCommand(ctx, PlayerManager.instance, requestingUserDto, deleteDelay)
    }

    override fun handleMusicCommand(ctx: CommandContext, instance: PlayerManager, requestingUserDto: UserDto, deleteDelay: Int?) {
        val event = ctx.event
        event.deferReply(true).queue()
        if (IMusicCommand.isInvalidChannelStateForCommand(ctx, deleteDelay)) return
        val tracksToSkip = event.getOption(SKIP)?.asInt?: 1
        MusicPlayerHelper.skipTracks(event, instance, tracksToSkip, requestingUserDto.superUser, deleteDelay)
    }

    override val name: String
        get() = "skip"
    override val description: String
        get() = "skip X number of tracks. Skips 1 by default"
    override val optionData: List<OptionData>
        get() = listOf(OptionData(OptionType.INTEGER, SKIP, "Number of tracks to skip"))
}
