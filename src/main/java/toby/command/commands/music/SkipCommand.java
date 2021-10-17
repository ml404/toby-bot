package toby.command.commands.music;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import net.dv8tion.jda.api.entities.TextChannel;
import toby.command.CommandContext;
import toby.command.ICommand;
import toby.jpa.dto.UserDto;
import toby.lavaplayer.GuildMusicManager;
import toby.lavaplayer.PlayerManager;

import java.util.List;

import static toby.command.commands.music.NowDigOnThisCommand.sendDeniedStoppableMessage;
import static toby.helpers.MusicPlayerHelper.nowPlaying;


public class SkipCommand implements IMusicCommand {

    @Override
    public void handle(CommandContext ctx, String prefix, UserDto requestingUserDto, Integer deleteDelay) {
        ICommand.deleteAfter(ctx.getMessage(), deleteDelay);
        final TextChannel channel = ctx.getChannel();
        if (IMusicCommand.isInvalidChannelStateForCommand(ctx, channel, deleteDelay)) return;
        final GuildMusicManager musicManager = PlayerManager.getInstance().getMusicManager(ctx.getGuild());
        final AudioPlayer audioPlayer = musicManager.getAudioPlayer();

        if (audioPlayer.getPlayingTrack() == null) {
            channel.sendMessage("There is no track playing currently").queue(message -> ICommand.deleteAfter(message, deleteDelay));
            return;
        }
        List<String> args = ctx.getArgs();
        String skipValue = (!args.isEmpty()) ? args.get(0) : "";
        int tracksToSkip = !skipValue.isEmpty() ? Integer.parseInt(skipValue) : 1;

        if (tracksToSkip < 0) {
            channel.sendMessage("You're not too bright, but thanks for trying").queue(message -> ICommand.deleteAfter(message, deleteDelay));
            return;
        }

        if (PlayerManager.getInstance().isCurrentlyStoppable() || requestingUserDto.isSuperUser()) {
            for (int j = 0; j < tracksToSkip; j++) {
                musicManager.getScheduler().nextTrack();
            }
            musicManager.getScheduler().setLooping(false);
            channel.sendMessage(String.format("Skipped %d track(s)", tracksToSkip)).queue(message -> ICommand.deleteAfter(message, deleteDelay));
            nowPlaying(channel, musicManager.getAudioPlayer().getPlayingTrack(), deleteDelay);
        } else {
            sendDeniedStoppableMessage(channel, musicManager, deleteDelay);
        }
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

