package toby.command.commands.music;


import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import toby.command.CommandContext;
import toby.command.ICommand;
import toby.jpa.dto.UserDto;
import toby.lavaplayer.PlayerManager;


public class ResumeCommand implements IMusicCommand {
    @Override
    public void handle(CommandContext ctx, UserDto requestingUserDto, Integer deleteDelay) {
        ICommand.deleteAfter(ctx.getEvent().getHook(), deleteDelay);
        final SlashCommandInteractionEvent event = ctx.getEvent();
        if (!requestingUserDto.hasMusicPermission()) {
            sendErrorMessage(event, deleteDelay);
            return;
        }

        if (IMusicCommand.isInvalidChannelStateForCommand(ctx, deleteDelay)) return;
        AudioPlayer audioPlayer = PlayerManager.getInstance().getMusicManager(event.getGuild()).getAudioPlayer();
        if (audioPlayer.isPaused()) {
            AudioTrack track = audioPlayer.getPlayingTrack();
            event.reply("Resuming: `")
                    .addContent(track.getInfo().title)
                    .addContent("` by `")
                    .addContent(track.getInfo().author)
                    .addContent("`")
                    .queue(message -> ICommand.deleteAfter(message, deleteDelay));
            audioPlayer.setPaused(false);
        }
    }

    @Override
    public String getName() {
        return "resume";
    }

    @Override
    public String getDescription() {
        return "Resumes the current song if one is paused.";
    }

}
