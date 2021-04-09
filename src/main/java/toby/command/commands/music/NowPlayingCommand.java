package toby.command.commands.music;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import net.dv8tion.jda.api.entities.GuildVoiceState;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.TextChannel;
import toby.command.CommandContext;
import toby.command.ICommand;
import toby.lavaplayer.GuildMusicManager;
import toby.lavaplayer.PlayerManager;

import java.util.Arrays;
import java.util.List;

public class NowPlayingCommand implements ICommand {
    @Override
    public void handle(CommandContext ctx, String prefix) {
        final TextChannel channel = ctx.getChannel();
        final Member self = ctx.getSelfMember();
        final GuildVoiceState selfVoiceState = self.getVoiceState();

        if (doChannelValidation(ctx, channel, selfVoiceState)) return;

        final GuildMusicManager musicManager = PlayerManager.getInstance().getMusicManager(ctx.getGuild());
        final AudioPlayer audioPlayer = musicManager.getAudioPlayer();
        final AudioTrack track = audioPlayer.getPlayingTrack();

        if (track == null) {
            channel.sendMessage("There is no track playing currently").queue();
            return;
        }

        final AudioTrackInfo info = track.getInfo();

        AudioTrack playingTrack = musicManager.getAudioPlayer().getPlayingTrack();
        if (!track.getInfo().isStream) {
            long position = playingTrack.getPosition();
            long duration = playingTrack.getDuration();
            String songPosition = QueueCommand.formatTime(position);
            String songDuration = QueueCommand.formatTime(duration);
            String nowPlaying = String.format("Now playing `%s` by `%s` `[%s/%s]` (Link: <%s>) ", info.title, info.author, songPosition, songDuration, info.uri);
            channel.sendMessage(nowPlaying).queue();
        } else {
            String nowPlaying = String.format("Now playing `%s` by `%s` (Link: <%s>) ", info.title, info.author, info.uri);
            channel.sendMessage(nowPlaying).queue();
        }
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

    @Override
    public String getName() {
        return "nowplaying";
    }

    @Override
    public String getHelp(String prefix) {
        return String.format("Shows the currently playing song. Aliases are: %s", String.join(",", getAliases()));
    }

    @Override
    public List<String> getAliases() {
        return Arrays.asList("np", "now");
    }
}
