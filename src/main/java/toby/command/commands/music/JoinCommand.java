package toby.command.commands.music;

import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.GuildVoiceState;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.TextChannel;
import net.dv8tion.jda.api.entities.VoiceChannel;
import net.dv8tion.jda.api.managers.AudioManager;
import org.jetbrains.annotations.Nullable;
import toby.command.CommandContext;
import toby.command.ICommand;

public class JoinCommand implements ICommand {
    @Override
    public void handle(CommandContext ctx, String prefix) {
        final TextChannel channel = ctx.getChannel();
        final Member self = ctx.getSelfMember();
        final GuildVoiceState selfVoiceState = self.getVoiceState();

        final GuildVoiceState memberVoiceState = doChannelValidation(ctx, channel, selfVoiceState);
        if (memberVoiceState == null) return;

        final AudioManager audioManager = ctx.getGuild().getAudioManager();
        final VoiceChannel memberChannel = memberVoiceState.getChannel();

        if(self.hasPermission(Permission.VOICE_CONNECT)){
        audioManager.openAudioConnection(memberChannel);
        channel.sendMessageFormat("Connecting to `\uD83D\uDD0A %s`", memberChannel.getName()).queue();
        }
    }

    @Nullable
    private GuildVoiceState doChannelValidation(CommandContext ctx, TextChannel channel, GuildVoiceState selfVoiceState) {
        if (selfVoiceState.inVoiceChannel()) {
            channel.sendMessage("I'm already in a voice channel").queue();
            return null;
        }

        final Member member = ctx.getMember();
        final GuildVoiceState memberVoiceState = member.getVoiceState();

        if (!memberVoiceState.inVoiceChannel()) {
            channel.sendMessage("You need to be in a voice channel for this command to work").queue();
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
