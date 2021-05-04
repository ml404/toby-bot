package toby.command.commands.misc;

import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.TextChannel;
import toby.command.CommandContext;
import toby.command.ICommand;
import toby.jpa.dto.UserDto;

import java.util.Arrays;
import java.util.List;


public class UserCommand implements IMiscCommand {


    public UserCommand() {
    }

    @Override
    public void handle(CommandContext ctx, String prefix, UserDto requestingUserDto, Integer deleteDelay) {
        ICommand.deleteAfter(ctx.getMessage(), deleteDelay);
        final TextChannel channel = ctx.getChannel();
        final Message message = ctx.getMessage();

        printUserInfo(channel, message, requestingUserDto, deleteDelay);
    }

    private void printUserInfo(TextChannel channel, Message message, UserDto requestingUserDto, Integer deleteDelay) {
        if (message.getMentionedMembers().isEmpty()) {
            if (requestingUserDto != null) {
                channel.sendMessage(String.format("Here are your permissions: '%s'.", requestingUserDto)).queue(message1 -> ICommand.deleteAfter(message1, deleteDelay));
                channel.sendMessage(String.format("Your intro song is currently set as: '%s'.", requestingUserDto.getMusicDto())).queue(message1 -> ICommand.deleteAfter(message1, deleteDelay));
            }
        }
    }

    @Override
    public String getName() {
        return "user";
    }

    @Override
    public String getHelp(String prefix) {
        return "Let me tell you about your permissions.\n" +
                String.format("Usage: `%suser`", prefix);
    }

    @Override
    public List<String> getAliases() {
        return Arrays.asList("getuser","userinfo");
    }
}