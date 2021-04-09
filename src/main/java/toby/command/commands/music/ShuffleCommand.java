package toby.command.commands.music;


import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.GuildVoiceState;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.TextChannel;
import org.jetbrains.annotations.NotNull;
import toby.command.CommandContext;
import toby.command.ICommand;
import toby.lavaplayer.PlayerManager;
import toby.lavaplayer.TrackScheduler;

import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public class ShuffleCommand implements ICommand {
    @SuppressWarnings("ConstantConditions")
    @Override
    public void handle(CommandContext ctx, String prefix) {
        final TextChannel channel = ctx.getChannel();

        final Member self = ctx.getSelfMember();
        final GuildVoiceState selfVoiceState = self.getVoiceState();

        if (doChannelValidation(ctx, channel, selfVoiceState)) return;

        Guild guild = ctx.getGuild();

        TrackScheduler trackScheduler = PlayerManager.getInstance().getMusicManager(guild).getScheduler();
        BlockingQueue<AudioTrack> queue = trackScheduler.getQueue();
        if (queue.size() == 0){
            channel.sendMessage("I can't shuffle a queue that doesn't exist").queue();
            return;
        }
        LinkedBlockingQueue<AudioTrack> shuffledAudioTracks = shuffleAudioTracks(queue);
        trackScheduler.setQueue(shuffledAudioTracks);
        channel.sendMessage("The queue has been shuffled 🦧").queue();

    }

    private boolean doChannelValidation(CommandContext ctx, TextChannel channel, GuildVoiceState selfVoiceState) {
        if (!selfVoiceState.inVoiceChannel()) {
            channel.sendMessage("I need to be in a voice channel for this to work").queue();
            return true;
        }

        final Member member = ctx.getMember();
        final GuildVoiceState memberVoiceState = member.getVoiceState();

        if (!memberVoiceState.inVoiceChannel()) {
            channel.sendMessage("You need to be in a voice channel for this command to work").queue();
            return true;
        }

        if (!memberVoiceState.getChannel().equals(selfVoiceState.getChannel())) {
            channel.sendMessage("You need to be in the same voice channel as me for this to work").queue();
            return true;
        }
        return false;
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
