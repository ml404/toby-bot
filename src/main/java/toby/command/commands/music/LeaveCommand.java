package toby.command.commands.music;

import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.AudioChannel;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.GuildVoiceState;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
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
    public void handle(CommandContext ctx, UserDto requestingUserDto, Integer deleteDelay) {
        ICommand.deleteAfter(ctx.getEvent().getHook(), deleteDelay);
        final SlashCommandInteractionEvent event = ctx.getEvent();
        final Member self = ctx.getSelfMember();
        final GuildVoiceState selfVoiceState = self.getVoiceState();

        if (!requestingUserDto.hasMusicPermission()) {
            sendErrorMessage(event, deleteDelay);
            return;
        }

        if (!selfVoiceState.inAudioChannel()) {
            event.reply("I'm not in a voice channel, somebody shoot this guy").queue(message -> ICommand.deleteAfter(message, deleteDelay));
            return;
        }

        final Member member = ctx.getMember();
        final GuildVoiceState memberVoiceState = member.getVoiceState();


        Guild guild = event.getGuild();
        final AudioManager audioManager = guild.getAudioManager();
        final AudioChannel memberChannel = memberVoiceState.getChannel();

        GuildMusicManager musicManager = PlayerManager.getInstance().getMusicManager(guild);
        if (PlayerManager.getInstance().isCurrentlyStoppable() || member.hasPermission(Permission.KICK_MEMBERS)) {
            String volumePropertyName = ConfigDto.Configurations.VOLUME.getConfigValue();
            ConfigDto databaseConfig = configService.getConfigByName(volumePropertyName, event.getGuild().getId());
            int defaultVolume = databaseConfig!=null ? Integer.parseInt(databaseConfig.getValue()) : 100;
            musicManager.getScheduler().setLooping(false);
            musicManager.getScheduler().getQueue().clear();
            musicManager.getAudioPlayer().stopTrack();
            musicManager.getAudioPlayer().setVolume(defaultVolume);
            audioManager.closeAudioConnection();
            event.replyFormat("Disconnecting from `\uD83D\uDD0A %s`", memberChannel.getName()).queue(message -> ICommand.deleteAfter(message, deleteDelay));
        } else {
            sendDeniedStoppableMessage(event, musicManager, deleteDelay);
        }
    }


    @Override
    public String getName() {
        return "leave";
    }

    @Override
    public String getDescription() {
        return "Makes the TobyBot leave the voice channel it's currently in";
    }


}
