package toby.command.commands.misc;

import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import toby.command.CommandContext;
import toby.command.ICommand;
import toby.jpa.dto.UserDto;
import toby.managers.CommandManager;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import static toby.command.ICommand.*;

public class HelpCommand implements IMiscCommand {

    private final CommandManager manager;
    private final String COMMAND = "command";

    public HelpCommand(CommandManager manager) {
        this.manager = manager;
    }

    @Override
    public void handle(CommandContext ctx, UserDto requestingUserDto, Integer deleteDelay) {
        deleteAfter(ctx.getEvent().getHook(), deleteDelay);

        List<OptionMapping> args = ctx.getEvent().getOptions();
        SlashCommandInteractionEvent event = ctx.getEvent();
        event.deferReply().queue();

        if (args.isEmpty()) {
            StringBuilder builder = new StringBuilder();
            Consumer<ICommand> commandConsumer = (command) -> builder.append('`').append("/").append(command.getName()).append('`').append("\n");

            builder.append(String.format("List of all current commands below. If you want to find out how to use one of the commands try doing `%shelp commandName`\n", "/"));
            builder.append("**Music Commands**:\n");
            manager.getMusicCommands().forEach(commandConsumer);
            builder.append("**Miscellaneous Commands**:\n");
            manager.getMiscCommands().forEach(commandConsumer);
            builder.append("**Moderation Commands**:\n");
            manager.getModerationCommands().forEach(commandConsumer);
            builder.append("**Fetch Commands**:\n");
            manager.getFetchCommands().forEach(commandConsumer);


            event.getHook().sendMessageFormat(builder.toString()).queue(invokeDeleteOnMessageResponse(deleteDelay));
            return;
        }

        Optional<String> searchOptional = Optional.ofNullable(event.getOption(COMMAND)).map(OptionMapping::getAsString);
        Optional<ICommand> command = searchOptional.map(manager::getCommand);

        if (command.isEmpty()) {
            event.getHook().sendMessage("Nothing found for command '%s" + searchOptional.get()).queue(invokeDeleteOnMessageResponse(deleteDelay));
            return;
        }

        event.getHook().sendMessage(command.get().getDescription()).setEphemeral(true).queue(invokeDeleteOnMessageResponse(deleteDelay));
    }

    @Override
    public String getName() {
        return "help";
    }

    @Override
    public String getDescription() {
        return "get help with the command you give this command";
    }

    @Override
    public List<OptionData> getOptionData() {
        return List.of(new OptionData(OptionType.STRING, COMMAND, "Command you would like help with"));
    }
}
