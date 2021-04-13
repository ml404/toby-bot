package toby.command.commands.music;

import net.dv8tion.jda.api.entities.GuildVoiceState;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.TextChannel;
import org.jetbrains.annotations.Nullable;
import toby.command.CommandContext;
import toby.jpa.dto.UserDto;
import toby.lavaplayer.GuildMusicManager;
import toby.lavaplayer.PlayerManager;

import java.util.Arrays;
import java.util.List;

import static toby.command.commands.music.NowDigOnThisCommand.sendDeniedStoppableMessage;

public class StopCommand implements IMusicCommand {
    @Override
    public void handle(CommandContext ctx, String prefix, UserDto requestingUserDto) {
        final TextChannel channel = ctx.getChannel();
        final Member member = doChannelStateValidation(ctx, channel);
        if (member == null) return;

        final GuildMusicManager musicManager = PlayerManager.getInstance().getMusicManager(ctx.getGuild());

        if (PlayerManager.getInstance().isCurrentlyStoppable() || requestingUserDto.isSuperUser()) {
            musicManager.getScheduler().stopTrack(true);
            musicManager.getScheduler().getQueue().clear();
            musicManager.getScheduler().setLooping(false);
            musicManager.getAudioPlayer().setPaused(false);
            channel.sendMessage("The player has been stopped and the queue has been cleared").queue();
        } else {
            sendDeniedStoppableMessage(channel, musicManager);
        }
    }

    @Nullable
    private Member doChannelStateValidation(CommandContext ctx, TextChannel channel) {
        final Member self = ctx.getSelfMember();
        final GuildVoiceState selfVoiceState = self.getVoiceState();

        if (!selfVoiceState.inVoiceChannel()) {
            channel.sendMessage("I need to be in a voice channel for this to work").queue();
            return null;
        }

        final Member member = ctx.getMember();
        final GuildVoiceState memberVoiceState = member.getVoiceState();

        if (!memberVoiceState.inVoiceChannel()) {
            channel.sendMessage("You need to be in a voice channel for this command to work").queue();
            return null;
        }

        if (!memberVoiceState.getChannel().equals(selfVoiceState.getChannel())) {
            channel.sendMessage("You need to be in the same voice channel as me for this to work").queue();
            return null;
        }
        return member;
    }

    @Override
    public String getName() {
        return "stop";
    }

    @Override
    public String getHelp(String prefix) {
        return "Stops the current song and clears the queue\n" +
                String.format("Aliases are: %s", String.join(",", getAliases()));

    }

    @Override
    public List<String> getAliases() {
        return Arrays.asList("stfu");
    }

}
