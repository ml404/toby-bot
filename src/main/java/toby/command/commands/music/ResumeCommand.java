package toby.command.commands.music;


import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import net.dv8tion.jda.api.entities.TextChannel;
import toby.command.CommandContext;
import toby.command.ICommand;
import toby.jpa.dto.UserDto;
import toby.lavaplayer.PlayerManager;


public class ResumeCommand implements IMusicCommand {
    @Override
    public void handle(CommandContext ctx, String prefix, UserDto requestingUserDto, Integer deleteDelay) {
        ICommand.deleteAfter(ctx.getMessage(), deleteDelay);
        final TextChannel channel = ctx.getChannel();
        if (!requestingUserDto.hasMusicPermission()) {
            sendErrorMessage(ctx, channel, deleteDelay);
            return;
        }

        if (IMusicCommand.isInvalidChannelStateForCommand(ctx, channel, deleteDelay)) return;
        AudioPlayer audioPlayer = PlayerManager.getInstance().getMusicManager(ctx.getGuild()).getAudioPlayer();
        if (audioPlayer.isPaused()) {
            AudioTrack track = audioPlayer.getPlayingTrack();
            channel.sendMessage("Resuming: `")
                    .append(track.getInfo().title)
                    .append("` by `")
                    .append(track.getInfo().author)
                    .append('`')
                    .queue(message -> ICommand.deleteAfter(message, deleteDelay));
            audioPlayer.setPaused(false);
        }
    }

    @Override
    public String getName() {
        return "resume";
    }

    @Override
    public String getHelp(String prefix) {
        return "Resumes the current song if one is paused\n" +
                String.format("Usage: `%sresume`", prefix);
    }

}
