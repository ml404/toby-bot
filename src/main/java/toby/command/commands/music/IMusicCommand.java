package toby.command.commands.music;

import net.dv8tion.jda.api.entities.GuildVoiceState;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.TextChannel;
import toby.command.CommandContext;
import toby.command.ICommand;

public interface IMusicCommand extends ICommand {

    static boolean isInvalidChannelStateForCommand(CommandContext ctx, TextChannel channel, Integer deleteDelay) {
        final Member self = ctx.getSelfMember();
        final GuildVoiceState selfVoiceState = self.getVoiceState();
        if (!selfVoiceState.inAudioChannel()) {
            channel.sendMessage("I need to be in a voice channel for this to work").queue(message -> ICommand.deleteAfter(message, deleteDelay));
            return true;
        }

        final Member member = ctx.getMember();
        final GuildVoiceState memberVoiceState = member.getVoiceState();
        if (!memberVoiceState.inAudioChannel()) {
            channel.sendMessage("You need to be in a voice channel for this command to work").queue(message -> ICommand.deleteAfter(message, deleteDelay));
            return true;
        }

        if (!memberVoiceState.getChannel().equals(selfVoiceState.getChannel())) {
            channel.sendMessage("You need to be in the same voice channel as me for this to work").queue(message -> ICommand.deleteAfter(message, deleteDelay));
            return true;
        }
        return false;
    }

}

