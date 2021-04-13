package toby.command.commands.music;

import net.dv8tion.jda.api.entities.TextChannel;
import toby.command.CommandContext;
import toby.command.ICommand;

public interface IMusicCommand extends ICommand {

    default String getErrorMessage() {
        return "You do not have adequate permissions to use this command, talk to the server owner: %s";
    }

    default void sendErrorMessage(CommandContext ctx, TextChannel channel) {
        channel.sendMessageFormat(getErrorMessage(), ctx.getGuild().getOwner().getNickname()).queue();
    }
}

