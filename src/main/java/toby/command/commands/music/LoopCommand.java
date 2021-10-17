package toby.command.commands.music;


import net.dv8tion.jda.api.entities.TextChannel;
import toby.command.CommandContext;
import toby.command.ICommand;
import toby.jpa.dto.UserDto;
import toby.lavaplayer.GuildMusicManager;
import toby.lavaplayer.PlayerManager;
import toby.lavaplayer.TrackScheduler;

public class LoopCommand implements IMusicCommand {
    @Override
    public void handle(CommandContext ctx, String prefix, UserDto requestingUserDto, Integer deleteDelay) {
        ICommand.deleteAfter(ctx.getMessage(), deleteDelay);
        final TextChannel channel = ctx.getChannel();
        if (!requestingUserDto.hasMusicPermission()) {
            sendErrorMessage(ctx, channel, deleteDelay);
            return;
        }

        if (IMusicCommand.isInvalidChannelStateForCommand(ctx, channel, deleteDelay)) return;

        GuildMusicManager musicManager = PlayerManager.getInstance().getMusicManager(ctx.getGuild());
        TrackScheduler scheduler = musicManager.getScheduler();
        boolean newIsRepeating = !scheduler.isLooping();
        scheduler.setLooping(newIsRepeating);

        channel.sendMessageFormat("The Player has been set to **%s**", newIsRepeating ? "looping" : "not looping").queue(message -> ICommand.deleteAfter(message, deleteDelay));
    }

    @Override
    public String getName() {
        return "loop";
    }

    @Override
    public String getHelp(String prefix) {
        return "Loop the current song\n" +
                String.format("Usage: `%sloop`", prefix);
    }
}
