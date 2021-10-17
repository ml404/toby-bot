package toby.command.commands.misc;

import net.dv8tion.jda.api.entities.TextChannel;
import toby.command.CommandContext;
import toby.command.ICommand;
import toby.jpa.dto.UserDto;
import toby.managers.CommandManager;

import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

public class HelpCommand implements IMiscCommand {

    private final CommandManager manager;

    public HelpCommand(CommandManager manager) {
        this.manager = manager;
    }

    @Override
    public void handle(CommandContext ctx, String prefix, UserDto requestingUserDto, Integer deleteDelay) {
        ICommand.deleteAfter(ctx.getMessage(), deleteDelay);

        List<String> args = ctx.getArgs();
        TextChannel channel = ctx.getChannel();

        if (args.isEmpty()) {
            StringBuilder builder = new StringBuilder();
            Consumer<ICommand> commandConsumer = (command) -> builder.append('`').append(prefix).append(command.getName()).append('`').append(String.format("Aliases are: '%s'", String.join(",", command.getAliases()))).append("\n");

            builder.append(String.format("List of all current commands below. If you want to find out how to use one of the commands try doing `%shelp commandName`\n", prefix));
            builder.append("**Music Commands**:\n");
            manager.getMusicCommands().forEach(commandConsumer);
            builder.append("**Miscellaneous Commands**:\n");
            manager.getMiscCommands().forEach(commandConsumer);
            builder.append("**Moderation Commands**:\n");
            manager.getModerationCommands().forEach(commandConsumer);
            builder.append("**Fetch Commands**:\n");
            manager.getFetchCommands().forEach(commandConsumer);


            channel.sendMessage(builder.toString()).queue(message -> ICommand.deleteAfter(message, deleteDelay));
            return;
        }

        String search = args.get(0);
        ICommand command = manager.getCommand(search);

        if (command == null) {
            channel.sendMessage("Nothing found for " + search).queue(message -> ICommand.deleteAfter(message, deleteDelay));
            return;
        }

        channel.sendMessage(command.getHelp(prefix)).queue(message -> ICommand.deleteAfter(message, deleteDelay));
    }

    @Override
    public String getName() {
        return "help";
    }

    @Override
    public String getHelp(String prefix) {
        return "Shows the list with commands in the bot\n" +
                String.format("Usage: `%shelp commandName`\n", prefix) +
                String.format("Aliases are: '%s'", String.join(",", getAliases()));
    }

    @Override
    public List<String> getAliases() {
        return Arrays.asList("commands", "cmds", "commandlist");
    }
}
