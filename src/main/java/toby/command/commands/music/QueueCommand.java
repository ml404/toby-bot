package toby.command.commands.music;

import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import net.dv8tion.jda.api.entities.TextChannel;
import net.dv8tion.jda.api.requests.restaction.MessageCreateAction;
import toby.command.CommandContext;
import toby.command.ICommand;
import toby.jpa.dto.UserDto;
import toby.lavaplayer.GuildMusicManager;
import toby.lavaplayer.PlayerManager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

public class QueueCommand implements IMusicCommand {

    @Override
    public void handle(CommandContext ctx, String prefix, UserDto requestingUserDto, Integer deleteDelay) {
        ICommand.deleteAfter(ctx.getMessage(), deleteDelay);
        final TextChannel channel = ctx.getChannel();
        final GuildMusicManager musicManager = PlayerManager.getInstance().getMusicManager(ctx.getGuild());
        final BlockingQueue<AudioTrack> queue = musicManager.getScheduler().getQueue();

        if (!requestingUserDto.hasMusicPermission()) {
            sendErrorMessage(ctx, channel, deleteDelay);
            return;
        }

        if (queue.isEmpty()) {
            channel.sendMessage("The queue is currently empty").queue(message -> ICommand.deleteAfter(message, deleteDelay));
            return;
        }

        final int trackCount = Math.min(queue.size(), 20);
        final List<AudioTrack> trackList = new ArrayList<>(queue);
        final MessageCreateAction messageAction = channel.sendMessage("**Current Queue:**\n");

        for (int i = 0; i < trackCount; i++) {
            final AudioTrack track = trackList.get(i);
            final AudioTrackInfo info = track.getInfo();

            messageAction.addContent("#")
                    .addContent(String.valueOf(i + 1))
                    .addContent(" `")
                    .addContent(String.valueOf(info.title))
                    .addContent(" by ")
                    .addContent(info.author)
                    .addContent("` [`")
                    .addContent(formatTime(track.getDuration()))
                    .addContent("`]\n");
        }

        if (trackList.size() > trackCount) {
            messageAction.addContent("And `")
                    .addContent(String.valueOf(trackList.size() - trackCount))
                    .addContent("` more...");
        }

        messageAction.queue(message -> ICommand.deleteAfter(message, deleteDelay));
    }

    public static String formatTime(long timeInMillis) {
        final long hours = timeInMillis / TimeUnit.HOURS.toMillis(1);
        final long minutes = timeInMillis / TimeUnit.MINUTES.toMillis(1);
        final long seconds = timeInMillis % TimeUnit.MINUTES.toMillis(1) / TimeUnit.SECONDS.toMillis(1);

        return String.format("%02d:%02d:%02d", hours, minutes, seconds);
    }

    @Override
    public String getName() {
        return "queue";
    }

    @Override
    public String getHelp(String prefix) {
        return "shows the queued up songs\n" +
                String.format("Aliases are: '%s'", String.join(",", getAliases()));
    }

    @Override
    public List<String> getAliases() {
        return Arrays.asList("q");
    }
}
