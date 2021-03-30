package toby.command.commands.music;


import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.GuildVoiceState;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.TextChannel;
import toby.command.CommandContext;
import toby.command.ICommand;
import toby.lavaplayer.PlayerManager;

public class SetVolumeCommand implements ICommand {
    @SuppressWarnings("ConstantConditions")
    @Override
    public void handle(CommandContext ctx, String prefix) {
        final TextChannel channel = ctx.getChannel();

        final Member self = ctx.getSelfMember();
        final GuildVoiceState selfVoiceState = self.getVoiceState();

        if (ctx.getArgs().isEmpty()) {
            channel.sendMessage(getHelp(prefix)).queue();
        }

        if (!selfVoiceState.inVoiceChannel()) {
            channel.sendMessage("I need to be in a voice channel for this to work").queue();
            return;
        }

        final Member member = ctx.getMember();
        final GuildVoiceState memberVoiceState = member.getVoiceState();

        if (!memberVoiceState.inVoiceChannel()) {
            channel.sendMessage("You need to be in a voice channel for this command to work").queue();
            return;
        }

        if (!memberVoiceState.getChannel().equals(selfVoiceState.getChannel())) {
            channel.sendMessage("You need to be in the same voice channel as me for this to work").queue();
            return;
        }

        Guild guild = ctx.getGuild();
        int volume = Integer.parseInt(ctx.getArgs().get(0));
        if (PlayerManager.getInstance().isCurrentlyStoppable() || member.hasPermission(Permission.KICK_MEMBERS)) {
            AudioPlayer audioPlayer = PlayerManager.getInstance().getMusicManager(guild).getAudioPlayer();
            if (volume < 10) volume = 10;
            if (volume > 100) volume = 100;
            int oldVolume = audioPlayer.getVolume();
            audioPlayer.setVolume(volume);
            channel.sendMessageFormat("Changing volume from %s to %s", oldVolume, volume).queue();
        } else {
            channel.sendMessage("You aren't allowed to change the volume kid").queue();
        }
    }

    @Override
    public String getName() {
        return "setvolume";
    }

    @Override
    public String getHelp(String prefix) {
        return "Set the volume of the audio player for the server to a percent value between 10 and 100\n" +
                String.format("Usage: `%sssetvolume 10`", prefix);
    }
}
