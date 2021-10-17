package toby.command.commands.music;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import net.dv8tion.jda.api.entities.TextChannel;
import toby.command.CommandContext;
import toby.command.ICommand;
import toby.jpa.dto.UserDto;
import toby.lavaplayer.GuildMusicManager;
import toby.lavaplayer.PlayerManager;

import java.util.Arrays;
import java.util.List;



public class NowPlayingCommand implements IMusicCommand {
    @Override
    public void handle(CommandContext ctx, String prefix, UserDto requestingUserDto, Integer deleteDelay) {
        ICommand.deleteAfter(ctx.getMessage(), deleteDelay);
        final TextChannel channel = ctx.getChannel();
        if (requestingUserDto.hasMusicPermission()) {
            if (IMusicCommand.isInvalidChannelStateForCommand(ctx, channel, deleteDelay)) return;
            final GuildMusicManager musicManager = PlayerManager.getInstance().getMusicManager(ctx.getGuild());
            final AudioPlayer audioPlayer = musicManager.getAudioPlayer();
            final AudioTrack track = audioPlayer.getPlayingTrack();

            if (track == null) {
                channel.sendMessage("There is no track playing currently").queue(message -> ICommand.deleteAfter(message, deleteDelay));
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
                channel.sendMessage(nowPlaying).queue(message -> ICommand.deleteAfter(message, deleteDelay));
            } else {
                String nowPlaying = String.format("Now playing `%s` by `%s` (Link: <%s>) ", info.title, info.author, info.uri);
                channel.sendMessage(nowPlaying).queue(message -> ICommand.deleteAfter(message, deleteDelay));
            }
        }
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
