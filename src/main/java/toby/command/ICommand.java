package toby.command;

import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.TextChannel;
import toby.jpa.dto.UserDto;

import java.util.List;
import java.util.concurrent.TimeUnit;

public interface ICommand {

    void handle(CommandContext ctx, String prefix, UserDto requestingUserDto, Integer deleteDelay);

    String getName();

    String getHelp(String prefix);

    default List<String> getAliases() {
        return List.of();
    }

    static void deleteAfter(Message message, int delay) {
        message.delete().queueAfter(delay, TimeUnit.SECONDS);
    }

    default String getErrorMessage() {
        return "You do not have adequate permissions to use this command, if you believe this is a mistake talk to the server owner: %s";
    }

    default void sendErrorMessage(CommandContext ctx, TextChannel channel, Integer deleteDelay) {
        channel.sendMessageFormat(getErrorMessage(), ctx.getGuild().getOwner().getNickname()).queue(message -> ICommand.deleteAfter(message, deleteDelay));
    }
}