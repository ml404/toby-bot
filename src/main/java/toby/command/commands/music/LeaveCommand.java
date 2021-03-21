package toby.command.commands.music;

import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.*;
import net.dv8tion.jda.api.managers.AudioManager;
import toby.command.CommandContext;
import toby.command.ICommand;
import toby.lavaplayer.GuildMusicManager;
import toby.lavaplayer.PlayerManager;

import static toby.command.commands.music.NowDigOnThisCommand.sendDeniedStoppableMessage;

public class LeaveCommand implements ICommand {
    @Override
    public void handle(CommandContext ctx) {
        final TextChannel channel = ctx.getChannel();
        final Member self = ctx.getSelfMember();
        final GuildVoiceState selfVoiceState = self.getVoiceState();

        if (!selfVoiceState.inVoiceChannel()) {
            channel.sendMessage("I'm not in a voice channel, somebody shoot this guy").queue();
            return;
        }

        final Member member = ctx.getMember();
        final GuildVoiceState memberVoiceState = member.getVoiceState();


        Guild guild = ctx.getGuild();
        final AudioManager audioManager = guild.getAudioManager();
        final VoiceChannel memberChannel = memberVoiceState.getChannel();

        GuildMusicManager musicManager = PlayerManager.getInstance().getMusicManager(guild);
        if (PlayerManager.getInstance().isCurrentlyStoppable() || member.hasPermission(Permission.KICK_MEMBERS)) {
            musicManager.getScheduler().setLooping(false);
            musicManager.getScheduler().getQueue().clear();
            musicManager.getAudioPlayer().stopTrack();
            audioManager.closeAudioConnection();
            channel.sendMessageFormat("Disconnecting from `\uD83D\uDD0A %s`", memberChannel.getName()).queue();
        }
        else {
            sendDeniedStoppableMessage(channel, musicManager);
        }
    }

    @Override
    public String getName() {
        return "leave";
    }

    @Override
    public String getHelp() {
        return "Makes the bot leave your voice channel";
    }
}
