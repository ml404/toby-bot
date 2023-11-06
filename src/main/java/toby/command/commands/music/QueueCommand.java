package toby.command.commands.music;

import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.requests.restaction.WebhookMessageCreateAction;
import toby.command.CommandContext;
import toby.jpa.dto.UserDto;
import toby.lavaplayer.PlayerManager;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import static toby.command.ICommand.deleteAfter;
import static toby.command.ICommand.invokeDeleteOnMessageResponse;

public class QueueCommand implements IMusicCommand {

    @Override
    public void handle(CommandContext ctx, UserDto requestingUserDto, Integer deleteDelay) {
        handleMusicCommand(ctx, PlayerManager.getInstance(), requestingUserDto, deleteDelay);
    }

    @Override
    public void handleMusicCommand(CommandContext ctx, PlayerManager instance, UserDto requestingUserDto, Integer deleteDelay) {
        deleteAfter(ctx.getEvent().getHook(), deleteDelay);
        final SlashCommandInteractionEvent event = ctx.getEvent();
        event.deferReply().queue();
        final BlockingQueue<AudioTrack> queue = instance.getMusicManager(ctx.getGuild()).getScheduler().getQueue();

        if (!requestingUserDto.hasMusicPermission()) {
            sendErrorMessage(event, deleteDelay);
            return;
        }

        if (queue.isEmpty()) {
            event.getHook().sendMessage("The queue is currently empty").setEphemeral(true).queue(invokeDeleteOnMessageResponse(deleteDelay));
            return;
        }

        final int trackCount = Math.min(queue.size(), 20);
        final List<AudioTrack> trackList = new ArrayList<>(queue);
        WebhookMessageCreateAction<Message> messageAction = event.getHook().sendMessage("**Current Queue:**\n");

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

        messageAction.setEphemeral(true).queue(invokeDeleteOnMessageResponse(deleteDelay));
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
    public String getDescription() {
        return "shows the queued up songs";
    }
}
