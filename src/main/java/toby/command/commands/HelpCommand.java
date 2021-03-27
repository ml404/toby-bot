package toby.command.commands;

import net.dv8tion.jda.api.entities.TextChannel;
import toby.command.CommandContext;
import toby.command.ICommand;
import toby.jpa.service.IConfigService;
import toby.managers.CommandManager;

import java.util.Arrays;
import java.util.List;

public class HelpCommand implements ICommand {

    private final CommandManager manager;
    private final IConfigService configService;

    public HelpCommand(CommandManager manager, IConfigService configService) {
        this.manager = manager;
        this.configService = configService;
    }

    @Override
    public void handle(CommandContext ctx) {

        List<String> args = ctx.getArgs();
        TextChannel channel = ctx.getChannel();

        if (args.isEmpty()) {
            StringBuilder builder = new StringBuilder();

            builder.append("List of commands\n");

            String prefix = configService.getConfigByName("PREFIX", ctx.getGuild().getId()).getValue();
            manager.getCommands().stream().map(ICommand::getName).forEach(
                    (it) -> {
                        builder.append('`').append(prefix).append(it).append("`\n");
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

        channel.sendMessage(command.getHelp()).queue();
    }

    @Override
    public String getName() {
        return "help";
    }

    @Override
    public String getHelp() {
        return "Shows the list with commands in the bot\n" +
                "Usage: `!help [command]`";
    }

    @Override
    public List<String> getAliases() {
        return Arrays.asList("commands", "cmds", "commandlist");
    }
}
