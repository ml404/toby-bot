package toby.command;

import net.dv8tion.jda.api.entities.TextChannel;

public interface IMusicCommand extends ICommand {

    default String getErrorMessage() {
        return "You do not have adequate permissions to use this command, talk to the server owner: %s";
    }

    default void sendErrorMessage(CommandContext ctx, TextChannel channel) {
        channel.sendMessageFormat(getErrorMessage(), ctx.getGuild().getOwner().getNickname()).queue();
    }
}

