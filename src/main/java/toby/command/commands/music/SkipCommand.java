package toby.command.commands.music;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.GuildVoiceState;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.TextChannel;
import toby.command.CommandContext;
import toby.command.ICommand;
import toby.lavaplayer.GuildMusicManager;
import toby.lavaplayer.PlayerManager;

import java.util.List;

import static toby.command.commands.music.NowDigOnThisCommand.sendDeniedStoppableMessage;

public class SkipCommand implements ICommand {

    @Override
    public void handle(CommandContext ctx, String prefix) {
        final TextChannel channel = ctx.getChannel();
        final Member self = ctx.getSelfMember();
        final GuildVoiceState selfVoiceState = self.getVoiceState();

        if (!selfVoiceState.inVoiceChannel()) {
            channel.sendMessage("I need to be in a voice channel for this to work").queue();
            return;
        }

        final Member member = ctx.getMember();
        final GuildVoiceState memberVoiceState = member.getVoiceState();

        if (!memberVoiceState.inVoiceChannel()) {
            channel.sendMessage("You need to be in a voice channel for this command to work").queue();
            return;
        }

        if (!memberVoiceState.getChannel().equals(selfVoiceState.getChannel())) {
            channel.sendMessage("You need to be in the same voice channel as me for this to work").queue();
            return;
        }

        final GuildMusicManager musicManager = PlayerManager.getInstance().getMusicManager(ctx.getGuild());
        final AudioPlayer audioPlayer = musicManager.getAudioPlayer();

        if (audioPlayer.getPlayingTrack() == null) {
            channel.sendMessage("There is no track playing currently").queue();
            return;
        }
        List<String> args = ctx.getArgs();
        String skipValue = (!args.isEmpty()) ? args.get(0) : "";
        int tracksToSkip = !skipValue.isEmpty() ? Integer.parseInt(skipValue) : 1;

        if(tracksToSkip < 0){
            channel.sendMessage("You're not too bright, but thanks for trying").queue();
            return ;
        }

        if (PlayerManager.getInstance().isCurrentlyStoppable() || member.hasPermission(Permission.KICK_MEMBERS)) {
            for (int j = 0; j < tracksToSkip; j++) {
                musicManager.getScheduler().nextTrack();
            }
            musicManager.getScheduler().setLooping(false);
            channel.sendMessage(String.format("Skipped %d track(s)", tracksToSkip)).queue();
            nowPlaying(channel, musicManager);
        } else {
            sendDeniedStoppableMessage(channel, musicManager);
        }
    }

    private void nowPlaying(TextChannel channel, GuildMusicManager musicManager) {
        AudioTrack track = musicManager.getAudioPlayer().getPlayingTrack();
        AudioTrackInfo info = track.getInfo();
        long duration = track.getDuration();
        String songDuration = QueueCommand.formatTime(duration);
        String nowPlaying = String.format("Now playing `%s` by `%s` `[%s]` (Link: <%s>) ", info.title, info.author, songDuration, info.uri);
        channel.sendMessage(nowPlaying).queue();
    }

    @Override
    public String getName() {
        return "skip";
    }

    @Override
    public String getHelp(String prefix) {
        return "skip X number of tracks \n" +
                String.format("e.g. `%sskip 5` \n", prefix) +
                "skips one by default";
    }
}

