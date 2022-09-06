package toby.command.commands.music;

import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.*;
import net.dv8tion.jda.api.managers.AudioManager;
import toby.command.CommandContext;
import toby.command.ICommand;
import toby.jpa.dto.ConfigDto;
import toby.jpa.dto.UserDto;
import toby.jpa.service.IConfigService;
import toby.lavaplayer.PlayerManager;

public class JoinCommand implements IMusicCommand {
    private final IConfigService configService;

    public JoinCommand(IConfigService configService) {
        this.configService = configService;
    }

    @Override
    public void handle(CommandContext ctx, String prefix, UserDto requestingUserDto, Integer deleteDelay) {
        ICommand.deleteAfter(ctx.getMessage(), deleteDelay);
        final TextChannel channel = ctx.getChannel();
        final Member self = ctx.getSelfMember();

        if (!requestingUserDto.hasMusicPermission()) {
            sendErrorMessage(ctx, channel, deleteDelay);
            return;
        }

        final GuildVoiceState memberVoiceState = doJoinChannelValidation(ctx, channel, deleteDelay);
        if (memberVoiceState == null) return;

        final AudioManager audioManager = ctx.getGuild().getAudioManager();
        final AudioChannel memberChannel = memberVoiceState.getChannel();

        if (self.hasPermission(Permission.VOICE_CONNECT)) {
            audioManager.openAudioConnection(memberChannel);
            String volumePropertyName = ConfigDto.Configurations.VOLUME.getConfigValue();
            ConfigDto databaseConfig = configService.getConfigByName(volumePropertyName, ctx.getGuild().getId());
            int defaultVolume = databaseConfig != null ? Integer.parseInt(databaseConfig.getValue()) : 100;
            PlayerManager.getInstance().getMusicManager(ctx.getGuild()).getAudioPlayer().setVolume(defaultVolume);
            channel.sendMessageFormat("Connecting to `\uD83D\uDD0A %s` with volume '%s'", memberChannel.getName(), defaultVolume).queue(message -> ICommand.deleteAfter(message, deleteDelay));
        }
    }

    private GuildVoiceState doJoinChannelValidation(CommandContext ctx, TextChannel channel, Integer deleteDelay) {
        final Member self = ctx.getSelfMember();
        final GuildVoiceState selfVoiceState = self.getVoiceState();

        if (selfVoiceState.inAudioChannel()) {
            channel.sendMessage("I'm already in a voice channel").queue(message -> ICommand.deleteAfter(message, deleteDelay));
            return null;
        }

        final Member member = ctx.getMember();
        final GuildVoiceState memberVoiceState = member.getVoiceState();

        if (!memberVoiceState.inAudioChannel()) {
            channel.sendMessage("You need to be in a voice channel for this command to work").queue(message -> ICommand.deleteAfter(message, deleteDelay));
            return null;
        }
        return memberVoiceState;
    }

    @Override
    public String getName() {
        return "join";
    }

    @Override
    public String getHelp(String prefix) {
        return "Makes the bot join your voice channel";
    }
}
