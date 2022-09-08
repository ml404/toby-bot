package toby.command.commands.misc;

import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import toby.command.CommandContext;
import toby.command.ICommand;
import toby.jpa.dto.UserDto;

import java.util.List;
import java.util.Random;

public class RandomCommand implements IMiscCommand {

    private final String LIST = "list";

    @Override
    public void handle(CommandContext ctx, UserDto requestingUserDto, Integer deleteDelay) {

        final SlashCommandInteractionEvent event = ctx.getEvent();
        event.deferReply().queue();
        ICommand.deleteAfter(event.getHook(), deleteDelay);
        if (ctx.getEvent().getOptions().isEmpty()) {
            event.reply(getDescription()).queue(message1 -> ICommand.deleteAfter(message1, deleteDelay));
        }
        List<String> args = List.of(event.getOption(LIST).getAsString().split(","));
        event.reply(getRandomElement(args)).queue(message1 -> ICommand.deleteAfter(message1, deleteDelay));
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
    public String getDescription() {
        return "Return one item from a list you provide with options separated by commas.";
    }

    @Override
    public List<OptionData> getOptionData() {
        return List.of(new OptionData(OptionType.STRING, LIST, "List of elements you want to pick a random value from"));
    }
}
