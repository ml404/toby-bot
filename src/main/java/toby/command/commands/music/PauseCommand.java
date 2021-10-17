package toby.command.commands.music;


import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.TextChannel;
import toby.command.CommandContext;
import toby.command.ICommand;
import toby.jpa.dto.UserDto;
import toby.lavaplayer.GuildMusicManager;
import toby.lavaplayer.PlayerManager;

import static toby.command.commands.music.NowDigOnThisCommand.sendDeniedStoppableMessage;

public class PauseCommand implements IMusicCommand {
    @Override
    public void handle(CommandContext ctx, String prefix, UserDto requestingUserDto, Integer deleteDelay) {
        ICommand.deleteAfter(ctx.getMessage(), deleteDelay);
        final TextChannel channel = ctx.getChannel();
        if (!requestingUserDto.hasMusicPermission()) {
            sendErrorMessage(ctx, channel, deleteDelay);
            return;
        }
        if (IMusicCommand.isInvalidChannelStateForCommand(ctx, channel, deleteDelay)) return;
        final Member member = ctx.getMember();
        Guild guild = ctx.getGuild();
        GuildMusicManager musicManager = PlayerManager.getInstance().getMusicManager(guild);
        if (PlayerManager.getInstance().isCurrentlyStoppable() || member.hasPermission(Permission.KICK_MEMBERS)) {
            AudioPlayer audioPlayer = PlayerManager.getInstance().getMusicManager(guild).getAudioPlayer();
            if (!audioPlayer.isPaused()) {
                AudioTrack track = audioPlayer.getPlayingTrack();
                channel.sendMessage("Pausing: `")
                        .append(track.getInfo().title)
                        .append("` by `")
                        .append(track.getInfo().author)
                        .append('`')
                        .queue(message -> ICommand.deleteAfter(message, deleteDelay));
                audioPlayer.setPaused(true);
            } else {
                channel.sendMessageFormat("The audio player on this server is already paused. Please try using %sresume", prefix).queue(message -> ICommand.deleteAfter(message, deleteDelay));
            }
        } else {
            sendDeniedStoppableMessage(channel, musicManager, deleteDelay);
        }
    }

    @Override
    public String getName() {
        return "pause";
    }

    @Override
    public String getHelp(String prefix) {
        return "Pauses the current song if one is playing\n" +
                String.format("Usage: `%spause`", prefix);
    }

}
