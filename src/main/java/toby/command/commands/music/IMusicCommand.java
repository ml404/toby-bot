package toby.command.commands.music;

import net.dv8tion.jda.api.entities.GuildVoiceState;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import toby.command.CommandContext;
import toby.command.ICommand;

public interface IMusicCommand extends ICommand {

    static boolean isInvalidChannelStateForCommand(CommandContext ctx, Integer deleteDelay) {
        final Member self = ctx.getSelfMember();
        final GuildVoiceState selfVoiceState = self.getVoiceState();
        SlashCommandInteractionEvent event = ctx.getEvent();
        if (!selfVoiceState.inAudioChannel()) {
            event.reply("I need to be in a voice channel for this to work").setEphemeral(true).queue(message -> ICommand.deleteAfter(message, deleteDelay));
            return true;
        }

        final Member member = ctx.getMember();
        final GuildVoiceState memberVoiceState = member.getVoiceState();
        if (!memberVoiceState.inAudioChannel()) {
            event.reply("You need to be in a voice channel for this command to work").setEphemeral(true).queue(message -> ICommand.deleteAfter(message, deleteDelay));
            return true;
        }

        if (!memberVoiceState.getChannel().equals(selfVoiceState.getChannel())) {
            event.reply("You need to be in the same voice channel as me for this to work").setEphemeral(true).queue(message -> ICommand.deleteAfter(message, deleteDelay));
            return true;
        }
        return false;
    }

}

