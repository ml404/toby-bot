package toby.command.commands.music;

import net.dv8tion.jda.api.entities.TextChannel;
import toby.command.CommandContext;
import toby.command.ICommand;
import toby.jpa.dto.UserDto;
import toby.lavaplayer.GuildMusicManager;
import toby.lavaplayer.PlayerManager;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.List;


public class NowDigOnThisCommand implements IMusicCommand {

    @Override
    public void handle(CommandContext ctx, String prefix, UserDto requestingUserDto, Integer deleteDelay) {
        ICommand.deleteAfter(ctx.getMessage(), deleteDelay);
        final TextChannel channel = ctx.getChannel();
        if (requestingUserDto.hasDigPermission()) {
            if (ctx.getArgs().isEmpty()) {
                channel.sendMessageFormat("Correct usage is `%snowdigonthis <youtube link>`", prefix).queue(message -> ICommand.deleteAfter(message, deleteDelay));
                return;
            }
            if (IMusicCommand.isInvalidChannelStateForCommand(ctx, channel, deleteDelay)) return;
            String link = String.join(" ", ctx.getArgs());
            if (link.contains("youtube") && !isUrl(link)) link = "ytsearch:" + link;
            PlayerManager.getInstance().loadAndPlay(channel, link, false, deleteDelay);
        } else
            sendErrorMessage(ctx, channel, deleteDelay);
    }


    public static void sendDeniedStoppableMessage(TextChannel channel, GuildMusicManager musicManager, Integer deleteDelay) {
        if (musicManager.getScheduler().getQueue().size() > 1) {
            channel.sendMessage("Our daddy taught us not to be ashamed of our playlists").queue(message -> ICommand.deleteAfter(message, deleteDelay));
        } else {
            long duration = musicManager.getAudioPlayer().getPlayingTrack().getDuration();
            String songDuration = QueueCommand.formatTime(duration);
            channel.sendMessage(String.format("HEY FREAK-SHOW! YOU AIN’T GOIN’ NOWHERE. I GOTCHA’ FOR %s, %s OF PLAYTIME!", songDuration, songDuration)).queue(message -> ICommand.deleteAfter(message, deleteDelay));
        }
    }

    @Override
    public String getName() {
        return "nowdigonthis";
    }

    @Override
    public String getHelp(String prefix) {
        return "Plays a song\n" +
                String.format("Usage: `%snowdigonthis <youtube link>` \n", prefix) +
                String.format("Aliases are: '%s'", String.join(",", getAliases()));
    }

    @Override
    public List<String> getAliases() {
        return Arrays.asList("ndot", "dig");
    }

    @Override
    public String getErrorMessage() {
        return "I'm gonna put some dirt in your eye %s";
    }

    @Override
    public void sendErrorMessage(CommandContext ctx, TextChannel channel, Integer deleteDelay) {
        channel.sendMessageFormat(getErrorMessage(), ctx.getEvent().getMember().getNickname()).queue(message -> ICommand.deleteAfter(message, deleteDelay));
    }

    private boolean isUrl(String url) {
        try {
            new URI(url);
            return true;
        } catch (URISyntaxException e) {
            return false;
        }
    }
}