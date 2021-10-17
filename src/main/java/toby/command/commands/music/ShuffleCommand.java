package toby.command.commands.music;


import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.TextChannel;
import org.jetbrains.annotations.NotNull;
import toby.command.CommandContext;
import toby.command.ICommand;
import toby.jpa.dto.UserDto;
import toby.lavaplayer.PlayerManager;
import toby.lavaplayer.TrackScheduler;

import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public class ShuffleCommand implements IMusicCommand {
    @Override
    public void handle(CommandContext ctx, String prefix, UserDto requestingUserDto, Integer deleteDelay) {
        ICommand.deleteAfter(ctx.getMessage(), deleteDelay);
        final TextChannel channel = ctx.getChannel();
        if (requestingUserDto.hasMusicPermission()) {
            if (IMusicCommand.isInvalidChannelStateForCommand(ctx, channel, deleteDelay)) return;

            Guild guild = ctx.getGuild();

            TrackScheduler trackScheduler = PlayerManager.getInstance().getMusicManager(guild).getScheduler();
            BlockingQueue<AudioTrack> queue = trackScheduler.getQueue();
            if (queue.size() == 0) {
                channel.sendMessage("I can't shuffle a queue that doesn't exist").queue(message -> ICommand.deleteAfter(message, deleteDelay));
                return;
            }
            LinkedBlockingQueue<AudioTrack> shuffledAudioTracks = shuffleAudioTracks(queue);
            trackScheduler.setQueue(shuffledAudioTracks);
            channel.sendMessage("The queue has been shuffled 🦧").queue(message -> ICommand.deleteAfter(message, deleteDelay));

        }
    }

    @NotNull
    private LinkedBlockingQueue<AudioTrack> shuffleAudioTracks(BlockingQueue<AudioTrack> queue) {
        ArrayList<AudioTrack> audioTrackArrayList = new ArrayList<>(queue);
        Collections.shuffle(audioTrackArrayList);
        return new LinkedBlockingQueue<>(audioTrackArrayList);
    }

    @Override
    public String getName() {
        return "shuffle";
    }

    @Override
    public String getHelp(String prefix) {
        return "Use this command to shuffle the queue\n" +
                String.format("Usage: `%sshuffle`", prefix);
    }

}
