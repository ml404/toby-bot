package toby.command.commands.music;


import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import org.jetbrains.annotations.NotNull;
import toby.command.CommandContext;
import toby.jpa.dto.UserDto;
import toby.lavaplayer.PlayerManager;
import toby.lavaplayer.TrackScheduler;

import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import static toby.command.ICommand.deleteAfter;
import static toby.command.ICommand.getConsumer;

public class ShuffleCommand implements IMusicCommand {
    @Override
    public void handle(CommandContext ctx, UserDto requestingUserDto, Integer deleteDelay) {
        handleMusicCommand(ctx, PlayerManager.getInstance(), requestingUserDto, deleteDelay);
    }

    @Override
    public void handleMusicCommand(CommandContext ctx, PlayerManager instance, UserDto requestingUserDto, Integer deleteDelay) {
        final SlashCommandInteractionEvent event = ctx.getEvent();
        deleteAfter(event.getHook(), deleteDelay);
        event.deferReply().queue();
        if (requestingUserDto.hasMusicPermission()) {
            if (IMusicCommand.isInvalidChannelStateForCommand(ctx, deleteDelay)) return;

            Guild guild = event.getGuild();

            TrackScheduler trackScheduler = instance.getMusicManager(guild).getScheduler();
            BlockingQueue<AudioTrack> queue = trackScheduler.getQueue();
            if (queue.size() == 0) {
                event.getHook().sendMessage("I can't shuffle a queue that doesn't exist").queue(getConsumer(deleteDelay));
                return;
            }
            LinkedBlockingQueue<AudioTrack> shuffledAudioTracks = shuffleAudioTracks(queue);
            trackScheduler.setQueue(shuffledAudioTracks);
            event.getHook().sendMessage("The queue has been shuffled 🦧").queue(getConsumer(deleteDelay));

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
    public String getDescription() {
        return "Use this command to shuffle the queue";
    }
}
