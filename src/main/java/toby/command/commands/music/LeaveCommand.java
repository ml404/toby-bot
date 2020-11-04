package toby.command.commands.music;

import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.*;
import net.dv8tion.jda.api.managers.AudioManager;
import toby.command.CommandContext;
import toby.command.ICommand;

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


        final AudioManager audioManager = ctx.getGuild().getAudioManager();
        final VoiceChannel memberChannel = memberVoiceState.getChannel();

        Role chadmin = member.getGuild().getRoleById("553665444914003969");
        Role gibeGeneral = member.getGuild().getRoleById("765616921269764097");
        if(member.getRoles().contains(chadmin) || member.getRoles().contains(gibeGeneral)){
        audioManager.closeAudioConnection();
        channel.sendMessageFormat("Disconnecting from `\uD83D\uDD0A %s`", memberChannel.getName()).queue();
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
