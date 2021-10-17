package toby.command.commands.music;

import net.dv8tion.jda.api.entities.TextChannel;
import toby.command.CommandContext;
import toby.command.ICommand;
import toby.jpa.dto.UserDto;
import toby.lavaplayer.GuildMusicManager;
import toby.lavaplayer.PlayerManager;

import java.util.Arrays;
import java.util.List;

import static toby.command.commands.music.NowDigOnThisCommand.sendDeniedStoppableMessage;


public class StopCommand implements IMusicCommand {
    @Override
    public void handle(CommandContext ctx, String prefix, UserDto requestingUserDto, Integer deleteDelay) {
        ICommand.deleteAfter(ctx.getMessage(), deleteDelay);
        final TextChannel channel = ctx.getChannel();
        if(IMusicCommand.isInvalidChannelStateForCommand(ctx, channel, deleteDelay)) return;
        final GuildMusicManager musicManager = PlayerManager.getInstance().getMusicManager(ctx.getGuild());

        if (PlayerManager.getInstance().isCurrentlyStoppable() || requestingUserDto.isSuperUser()) {
            musicManager.getScheduler().stopTrack(true);
            musicManager.getScheduler().getQueue().clear();
            musicManager.getScheduler().setLooping(false);
            musicManager.getAudioPlayer().setPaused(false);
            channel.sendMessage("The player has been stopped and the queue has been cleared").queue(message -> ICommand.deleteAfter(message, deleteDelay));
        } else {
            sendDeniedStoppableMessage(channel, musicManager, deleteDelay);
        }
    }

    @Override
    public String getName() {
        return "stop";
    }

    @Override
    public String getHelp(String prefix) {
        return "Stops the current song and clears the queue\n" +
                String.format("Aliases are: '%s'", String.join(",", getAliases()));

    }

    @Override
    public List<String> getAliases() {
        return Arrays.asList("stfu");
    }

}
