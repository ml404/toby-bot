package toby.command.commands.music;


import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
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
    public void handle(CommandContext ctx, UserDto requestingUserDto, Integer deleteDelay) {
        ICommand.deleteAfter(ctx.getEvent().getHook(), deleteDelay);
        final SlashCommandInteractionEvent event = ctx.getEvent();
        event.deferReply().queue();
        if (requestingUserDto.hasMusicPermission()) {
            if (IMusicCommand.isInvalidChannelStateForCommand(ctx, deleteDelay)) return;

            Guild guild = event.getGuild();

            TrackScheduler trackScheduler = PlayerManager.getInstance().getMusicManager(guild).getScheduler();
            BlockingQueue<AudioTrack> queue = trackScheduler.getQueue();
            if (queue.size() == 0) {
                event.reply("I can't shuffle a queue that doesn't exist").queue(message -> ICommand.deleteAfter(message, deleteDelay));
                return;
            }
            LinkedBlockingQueue<AudioTrack> shuffledAudioTracks = shuffleAudioTracks(queue);
            trackScheduler.setQueue(shuffledAudioTracks);
            event.reply("The queue has been shuffled 🦧").queue(message -> ICommand.deleteAfter(message, deleteDelay));

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
