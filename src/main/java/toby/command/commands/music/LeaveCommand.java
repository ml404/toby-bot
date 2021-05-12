package toby.command.commands.music;

import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.*;
import net.dv8tion.jda.api.managers.AudioManager;
import toby.command.CommandContext;
import toby.command.ICommand;
import toby.jpa.dto.ConfigDto;
import toby.jpa.dto.UserDto;
import toby.jpa.service.IConfigService;
import toby.lavaplayer.GuildMusicManager;
import toby.lavaplayer.PlayerManager;

import static toby.command.commands.music.NowDigOnThisCommand.sendDeniedStoppableMessage;

public class LeaveCommand implements IMusicCommand {
    private final IConfigService configService;

    public LeaveCommand(IConfigService configService) {
        this.configService = configService;
    }

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

        if (!selfVoiceState.inVoiceChannel()) {
            channel.sendMessage("I'm not in a voice channel, somebody shoot this guy").queue(message -> ICommand.deleteAfter(message, deleteDelay));
            return;
        }

        final Member member = ctx.getMember();
        final GuildVoiceState memberVoiceState = member.getVoiceState();


        Guild guild = ctx.getGuild();
        final AudioManager audioManager = guild.getAudioManager();
        final VoiceChannel memberChannel = memberVoiceState.getChannel();

        GuildMusicManager musicManager = PlayerManager.getInstance().getMusicManager(guild);
        if (PlayerManager.getInstance().isCurrentlyStoppable() || member.hasPermission(Permission.KICK_MEMBERS)) {
            String volumePropertyName = ConfigDto.Configurations.VOLUME.getConfigValue();
            ConfigDto databaseConfig = configService.getConfigByName(volumePropertyName, ctx.getGuild().getId());
            int defaultVolume = databaseConfig!=null ? Integer.parseInt(databaseConfig.getValue()) : 100;
            musicManager.getScheduler().setLooping(false);
            musicManager.getScheduler().getQueue().clear();
            musicManager.getAudioPlayer().stopTrack();
            musicManager.getAudioPlayer().setVolume(defaultVolume);
            audioManager.closeAudioConnection();
            channel.sendMessageFormat("Disconnecting from `\uD83D\uDD0A %s`", memberChannel.getName()).queue(message -> ICommand.deleteAfter(message, deleteDelay));
        } else {
            sendDeniedStoppableMessage(channel, musicManager, deleteDelay);
        }
    }


    @Override
    public String getName() {
        return "leave";
    }

    @Override
    public String getHelp(String prefix) {
        return "Makes the TobyBot leave the voice channel it's currently in";
    }


}
