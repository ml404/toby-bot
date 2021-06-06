package toby.command.commands.misc;

import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.TextChannel;
import toby.command.CommandContext;
import toby.command.ICommand;
import toby.jpa.dto.UserDto;

import java.util.Arrays;
import java.util.List;
import java.util.Random;

public class RandomCommand implements IMiscCommand {
    @Override
    public void handle(CommandContext ctx, String prefix, UserDto requestingUserDto, Integer deleteDelay) {

        final TextChannel channel = ctx.getChannel();
        final Message message = ctx.getMessage();
        ICommand.deleteAfter(message, deleteDelay);
        List<String> args = Arrays.asList(message.getContentRaw().split(" ", 2)[1].split(","));
        if (args.size() == 0) {
            channel.sendMessage(getHelp(prefix)).queue(message1 -> ICommand.deleteAfter(message1, deleteDelay));
        }
        Object randomElement = getRandomElement(args);
        channel.sendMessage(randomElement.toString()).queue(message1 -> ICommand.deleteAfter(message1, deleteDelay));

    }

    public static String getRandomElement(List<?> args) {
        int i = new Random().nextInt(args.size());
        return (String) args.get(i);
    }

    @Override
    public String getName() {
        return "random";
    }

    @Override
    public String getHelp(String prefix) {
        return "Return one item from a list you provide with options separated by commas. \n"
                + String.format("Usage: %srandom option1, option2, option3", prefix);
    }

    @Override
    public List<String> getAliases() {
        return Arrays.asList("rand", "rando");
    }
}
