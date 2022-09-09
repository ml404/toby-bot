package toby.command.commands.music;

import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.AudioChannel;
import net.dv8tion.jda.api.entities.GuildVoiceState;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
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
    public void handle(CommandContext ctx, UserDto requestingUserDto, Integer deleteDelay) {
        ICommand.deleteAfter(ctx.getEvent().getHook(), deleteDelay);
        final SlashCommandInteractionEvent event = ctx.getEvent();
        event.deferReply().queue();
        final Member self = ctx.getSelfMember();

        if (!requestingUserDto.hasMusicPermission()) {
            sendErrorMessage(event, deleteDelay);
            return;
        }

        final GuildVoiceState memberVoiceState = doJoinChannelValidation(ctx, deleteDelay);
        if (memberVoiceState == null) return;

        final AudioManager audioManager = event.getGuild().getAudioManager();
        final AudioChannel memberChannel = memberVoiceState.getChannel();

        if (self.hasPermission(Permission.VOICE_CONNECT)) {
            audioManager.openAudioConnection(memberChannel);
            String volumePropertyName = ConfigDto.Configurations.VOLUME.getConfigValue();
            ConfigDto databaseConfig = configService.getConfigByName(volumePropertyName, event.getGuild().getId());
            int defaultVolume = databaseConfig != null ? Integer.parseInt(databaseConfig.getValue()) : 100;
            PlayerManager.getInstance().getMusicManager(event.getGuild()).getAudioPlayer().setVolume(defaultVolume);
            event.getHook().sendMessageFormat("Connecting to `\uD83D\uDD0A %s` with volume '%s'", memberChannel.getName(), defaultVolume).queue(message -> ICommand.deleteAfter(message, deleteDelay));
        }
    }

    private GuildVoiceState doJoinChannelValidation(CommandContext ctx, Integer deleteDelay) {
        final Member self = ctx.getSelfMember();
        final GuildVoiceState selfVoiceState = self.getVoiceState();
        SlashCommandInteractionEvent event = ctx.getEvent();

        if (selfVoiceState.inAudioChannel()) {
            event.getHook().sendMessage("I'm already in a voice channel").setEphemeral(true).queue(message -> ICommand.deleteAfter(message, deleteDelay));
            return null;
        }

        final Member member = ctx.getMember();
        final GuildVoiceState memberVoiceState = member.getVoiceState();

        if (!memberVoiceState.inAudioChannel()) {
            event.getHook().sendMessage("You need to be in a voice channel for this command to work").setEphemeral(true).queue(message -> ICommand.deleteAfter(message, deleteDelay));
            return null;
        }
        return memberVoiceState;
    }

    @Override
    public String getName() {
        return "join";
    }

    @Override
    public String getDescription() {
        return "Makes the bot join your voice channel";
    }
}
