package toby.command.commands.music;


import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.GuildVoiceState;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.TextChannel;
import org.jetbrains.annotations.Nullable;
import toby.command.CommandContext;
import toby.emote.Emotes;
import toby.jpa.dto.UserDto;
import toby.lavaplayer.PlayerManager;

import java.util.Arrays;
import java.util.List;

public class SetVolumeCommand implements IMusicCommand {
    @SuppressWarnings("ConstantConditions")
    @Override
    public void handle(CommandContext ctx, String prefix, UserDto requestingUserDto) {
        final TextChannel channel = ctx.getChannel();

        final Member self = ctx.getSelfMember();
        final GuildVoiceState selfVoiceState = self.getVoiceState();
        if (!requestingUserDto.hasMusicPermission()) {
            sendErrorMessage(ctx, channel);
            return;
        }
        final Member member = doChannelAndArgsValidation(ctx, prefix, channel, selfVoiceState);
        if (member == null) return;
        setNewVolume(ctx, prefix, channel, member);
    }


    private void setNewVolume(CommandContext ctx, String prefix, TextChannel channel, Member member) {
        Guild guild = ctx.getGuild();
        boolean validVolumeArg = ctx.getArgs().get(0).matches("\\d+");
        if (validVolumeArg) {
            int volume = Integer.parseInt(ctx.getArgs().get(0));
            if (PlayerManager.getInstance().isCurrentlyStoppable() || member.hasPermission(Permission.KICK_MEMBERS)) {
                AudioPlayer audioPlayer = PlayerManager.getInstance().getMusicManager(guild).getAudioPlayer();
                if (volume < 1 || volume > 100) {
                    channel.sendMessage(getHelp(prefix)).queue();
                    return;
                }
                int oldVolume = audioPlayer.getVolume();
                if (volume == oldVolume) {
                    channel.sendMessageFormat("New volume and old volume are the same value, somebody shoot %s", member.getEffectiveName()).queue();
                    return;
                }
                audioPlayer.setVolume(volume);
                channel.sendMessageFormat("Changing volume from '%s' to '%s' \uD83D\uDD0A", oldVolume, volume).queue();
            } else {
                channel.sendMessageFormat("You aren't allowed to change the volume kid %s", Emotes.TOBY).queue();
            }
        } else channel.sendMessage(getHelp(prefix)).queue();
    }

    @Nullable
    private Member doChannelAndArgsValidation(CommandContext ctx, String prefix, TextChannel channel, GuildVoiceState selfVoiceState) {
        if (ctx.getArgs().isEmpty()) {
            channel.sendMessage(getHelp(prefix)).queue();
            return null;
        }

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
        return "setvolume";
    }

    @Override
    public String getHelp(String prefix) {
        return "Set the volume of the audio player for the server to a percent value between 1 and 100\n" +
                String.format("Usage: `%ssetvolume 10`\n", prefix) +
                String.format("Aliases are: %s", String.join(",", getAliases()));

    }

    @Override
    public List<String> getAliases() {
        return Arrays.asList("volume", "vol");
    }

}
