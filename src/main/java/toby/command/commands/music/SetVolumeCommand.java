package toby.command.commands.music;


import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.GuildVoiceState;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.TextChannel;
import toby.command.CommandContext;
import toby.command.ICommand;
import toby.emote.Emotes;
import toby.jpa.dto.UserDto;
import toby.lavaplayer.PlayerManager;

import java.util.Arrays;
import java.util.List;


public class SetVolumeCommand implements IMusicCommand {

    @Override
    public void handle(CommandContext ctx, String prefix, UserDto requestingUserDto, Integer deleteDelay) {
        ICommand.deleteAfter(ctx.getMessage(), deleteDelay);
        final TextChannel channel = ctx.getChannel();

        final Member self = ctx.getSelfMember();
        final GuildVoiceState selfVoiceState = self.getVoiceState();
        if (!requestingUserDto.hasMusicPermission()) {
            sendErrorMessage(ctx, channel, deleteDelay);
            return;
        }
        if (ctx.getArgs().isEmpty()) {
            channel.sendMessage(getHelp(prefix)).queue(message -> ICommand.deleteAfter(message, deleteDelay));
            return;
        }
        if (IMusicCommand.isInvalidChannelStateForCommand(ctx, channel, deleteDelay)) return;
        final Member member = ctx.getMember();
        setNewVolume(ctx, prefix, channel, member, deleteDelay);
    }


    private void setNewVolume(CommandContext ctx, String prefix, TextChannel channel, Member member, Integer deleteDelay) {
        Guild guild = ctx.getGuild();
        boolean validVolumeArg = ctx.getArgs().get(0).matches("\\d+");
        if (validVolumeArg) {
            int volume = Integer.parseInt(ctx.getArgs().get(0));
            if (PlayerManager.getInstance().isCurrentlyStoppable() || member.hasPermission(Permission.KICK_MEMBERS)) {
                AudioPlayer audioPlayer = PlayerManager.getInstance().getMusicManager(guild).getAudioPlayer();
                if (volume < 1 || volume > 100) {
                    channel.sendMessage(getHelp(prefix)).queue(message -> ICommand.deleteAfter(message, deleteDelay));
                    return;
                }
                int oldVolume = audioPlayer.getVolume();
                if (volume == oldVolume) {
                    channel.sendMessageFormat("New volume and old volume are the same value, somebody shoot %s", member.getEffectiveName()).queue(message -> ICommand.deleteAfter(message, deleteDelay));
                    return;
                }
                audioPlayer.setVolume(volume);
                channel.sendMessageFormat("Changing volume from '%s' to '%s' \uD83D\uDD0A", oldVolume, volume).queue(message -> ICommand.deleteAfter(message, deleteDelay));
            } else {
                channel.sendMessageFormat("You aren't allowed to change the volume kid %s", Emotes.TOBY).queue(message -> ICommand.deleteAfter(message, deleteDelay));
            }
        } else channel.sendMessage(getHelp(prefix)).queue(message -> ICommand.deleteAfter(message, deleteDelay));
    }


    @Override
    public String getName() {
        return "setvolume";
    }

    @Override
    public String getHelp(String prefix) {
        return "Set the volume of the audio player for the server to a percent value between 1 and 100\n" +
                String.format("Usage: `%ssetvolume 10`\n", prefix) +
                String.format("Aliases are: '%s'", String.join(",", getAliases()));

    }

    @Override
    public List<String> getAliases() {
        return Arrays.asList("volume", "vol");
    }

}
