package toby.command.commands.misc;

import net.dv8tion.jda.api.entities.TextChannel;
import toby.command.CommandContext;
import toby.command.ICommand;
import toby.jpa.dto.UserDto;
import toby.managers.CommandManager;

import java.util.Arrays;
import java.util.List;

public class HelpCommand implements IMiscCommand {

    private final CommandManager manager;

    public HelpCommand(CommandManager manager) {
        this.manager = manager;
    }

    @Override
    public void handle(CommandContext ctx, String prefix, UserDto requestingUserDto, Integer deleteDelay) {

        List<String> args = ctx.getArgs();
        TextChannel channel = ctx.getChannel();

        if (args.isEmpty()) {
            StringBuilder builder = new StringBuilder();

            builder.append(String.format("List of all current commands below. If you want to find out how to use one of the commands try doing `%shelp commandName`\n", prefix));
            builder.append("**Music Commands**:\n");
            manager.getMusicCommands().stream().map(ICommand::getName).forEach(
                    (commandName) -> {
                        builder.append('`').append(prefix).append(commandName).append("`\n");
                    }
            );
            builder.append("**Miscellaneous Commands**:\n");
            manager.getMiscCommands().stream().map(ICommand::getName).forEach(
                    (commandName) -> {
                        builder.append('`').append(prefix).append(commandName).append("`\n");
                    }
            );
            builder.append("**Moderation Commands**:\n");
            manager.getModerationCommands().stream().map(ICommand::getName).forEach(
                    (commandName) -> {
                        builder.append('`').append(prefix).append(commandName).append("`\n");
                    }
            );

            builder.append("**Fetch Commands**:\n");
            manager.getFetchCommands().stream().map(ICommand::getName).forEach(
                    (commandName) -> {
                        builder.append('`').append(prefix).append(commandName).append("`\n");
                    }
            );


            channel.sendMessage(builder.toString()).queue();
            return;
        }

        String search = args.get(0);
        ICommand command = manager.getCommand(search);

        if (command == null) {
            channel.sendMessage("Nothing found for " + search).queue();
            return;
        }

        channel.sendMessage(command.getHelp(prefix)).queue();
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
