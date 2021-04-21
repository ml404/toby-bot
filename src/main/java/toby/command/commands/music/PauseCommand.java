package toby.command.commands.music;


import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.GuildVoiceState;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.TextChannel;
import org.jetbrains.annotations.Nullable;
import toby.command.CommandContext;
import toby.command.ICommand;
import toby.jpa.dto.UserDto;
import toby.lavaplayer.GuildMusicManager;
import toby.lavaplayer.PlayerManager;

import static toby.command.commands.music.NowDigOnThisCommand.sendDeniedStoppableMessage;

public class PauseCommand implements IMusicCommand {
    @SuppressWarnings("ConstantConditions")
    @Override
    public void handle(CommandContext ctx, String prefix, UserDto requestingUserDto, Integer deleteDelay) {
        final TextChannel channel = ctx.getChannel();

        final Member self = ctx.getSelfMember();
        final GuildVoiceState selfVoiceState = self.getVoiceState();
        if (!requestingUserDto.hasMusicPermission()) {
            sendErrorMessage(ctx, channel, deleteDelay);
            return;
        }

        final Member member = doChannelStateValidation(ctx, channel, selfVoiceState, deleteDelay);
        if (member == null) return;

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
            }
        } else {
            sendDeniedStoppableMessage(channel, musicManager, deleteDelay);
        }
    }

    @Nullable
    private Member doChannelStateValidation(CommandContext ctx, TextChannel channel, GuildVoiceState selfVoiceState, Integer deleteDelay) {
        if (!selfVoiceState.inVoiceChannel()) {
            channel.sendMessage("I need to be in a voice channel for this to work").queue(message -> ICommand.deleteAfter(message, deleteDelay));
            return null;
        }

        final Member member = ctx.getMember();
        final GuildVoiceState memberVoiceState = member.getVoiceState();

        if (!memberVoiceState.inVoiceChannel()) {
            channel.sendMessage("You need to be in a voice channel for this command to work").queue(message -> ICommand.deleteAfter(message, deleteDelay));
            return null;
        }

        if (!memberVoiceState.getChannel().equals(selfVoiceState.getChannel())) {
            channel.sendMessage("You need to be in the same voice channel as me for this to work").queue(message -> ICommand.deleteAfter(message, deleteDelay));
            return null;
        }
        return member;
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
