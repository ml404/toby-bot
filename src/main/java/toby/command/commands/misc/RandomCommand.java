package toby.command.commands.misc;

import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import toby.command.CommandContext;
import toby.jpa.dto.UserDto;

import java.util.List;
import java.util.Optional;
import java.util.Random;

import static toby.command.ICommand.invokeDeleteOnMessageResponse;

public class RandomCommand implements IMiscCommand {

    private final String LIST = "list";

    @Override
    public void handle(CommandContext ctx, UserDto requestingUserDto, Integer deleteDelay) {

        final SlashCommandInteractionEvent event = ctx.getEvent();
        event.deferReply().queue();
        if (ctx.getEvent().getOptions().isEmpty()) {
            event.getHook().sendMessage(getDescription()).queue(invokeDeleteOnMessageResponse(deleteDelay));
            return;
        }
        List<String> stringList = List.of(Optional.ofNullable(event.getOption(LIST)).map(OptionMapping::getAsString).orElse("").split(","));
        event.getHook().sendMessage(getRandomElement(stringList)).queue(invokeDeleteOnMessageResponse(deleteDelay));
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
        return List.of(new OptionData(OptionType.STRING, LIST, "List of elements you want to pick a random value from", true));
    }
}
