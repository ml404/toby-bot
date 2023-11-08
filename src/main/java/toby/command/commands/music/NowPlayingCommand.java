package toby.command.commands.music;

import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import toby.command.CommandContext;
import toby.jpa.dto.UserDto;
import toby.lavaplayer.PlayerManager;

import static toby.command.commands.music.IMusicCommand.isInvalidChannelStateForCommand;
import static toby.helpers.MusicPlayerHelper.nowPlaying;


public class NowPlayingCommand implements IMusicCommand {
    @Override
    public void handle(CommandContext ctx, UserDto requestingUserDto, Integer deleteDelay) {
        handleMusicCommand(ctx, PlayerManager.getInstance(), requestingUserDto, deleteDelay);
    }

    @Override
    public void handleMusicCommand(CommandContext ctx, PlayerManager instance, UserDto requestingUserDto, Integer deleteDelay) {
        final SlashCommandInteractionEvent event = ctx.getEvent();
        event.deferReply().queue();
        if (requestingUserDto.hasMusicPermission()) {
            if (isInvalidChannelStateForCommand(ctx, deleteDelay)) return;
            nowPlaying(event.getHook(), instance, deleteDelay);
        } else sendErrorMessage(event, deleteDelay);
    }

    @Override
    public String getName() {
        return "nowplaying";
    }

    @Override
    public String getDescription() {
        return "Shows the currently playing song";
    }
}
