package toby.command.commands.music;


import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import net.dv8tion.jda.api.entities.GuildVoiceState;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.TextChannel;
import toby.command.CommandContext;
import toby.command.ICommand;
import toby.lavaplayer.PlayerManager;

public class ResumeCommand implements ICommand {
    @SuppressWarnings("ConstantConditions")
    @Override
    public void handle(CommandContext ctx, String prefix) {
        final TextChannel channel = ctx.getChannel();

        final Member self = ctx.getSelfMember();
        final GuildVoiceState selfVoiceState = self.getVoiceState();

        if (doChannelValidation(ctx, channel, selfVoiceState)) return;

        AudioPlayer audioPlayer = PlayerManager.getInstance().getMusicManager(ctx.getGuild()).getAudioPlayer();
        if(audioPlayer.isPaused()) {
            AudioTrack track = audioPlayer.getPlayingTrack();
            channel.sendMessage("Resuming: `")
                    .append(track.getInfo().title)
                    .append("` by `")
                    .append(track.getInfo().author)
                    .append('`')
                    .queue();
            audioPlayer.setPaused(false);
        }
    }

    private boolean doChannelValidation(CommandContext ctx, TextChannel channel, GuildVoiceState selfVoiceState) {
        if (!selfVoiceState.inVoiceChannel()) {
            channel.sendMessage("I need to be in a voice channel for this to work").queue();
            return true;
        }

        final Member member = ctx.getMember();
        final GuildVoiceState memberVoiceState = member.getVoiceState();

        if (!memberVoiceState.inVoiceChannel()) {
            channel.sendMessage("You need to be in a voice channel for this command to work").queue();
            return true;
        }

        if (!memberVoiceState.getChannel().equals(selfVoiceState.getChannel())) {
            channel.sendMessage("You need to be in the same voice channel as me for this to work").queue();
            return true;
        }
        return false;
    }

    @Override
    public String getName() {
        return "resume";
    }

    @Override
    public String getHelp(String prefix) {
        return "Resumes the current song if one is paused\n" +
                String.format("Usage: `%sresume`", prefix);
    }
}
