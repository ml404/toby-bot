package toby.command.commands.misc;

import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import toby.command.CommandContext;
import toby.jpa.dto.UserDto;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import static toby.command.ICommand.deleteAfter;
import static toby.command.ICommand.getConsumer;

public class ChCommand implements IMiscCommand {

    private final String MESSAGE = "message";

    @Override
    public void handle(CommandContext ctx, UserDto requestingUserDto, Integer deleteDelay) {
        deleteAfter(ctx.getEvent().getHook(), deleteDelay);
        final SlashCommandInteractionEvent event = ctx.getEvent();
        event.deferReply().queue();
        String message = Optional.ofNullable(event.getOption(MESSAGE)).map(OptionMapping::getAsString).orElse("");

        String newMessage = Arrays.stream(message.split(" ")).map(s -> {
                    int vowelIndex = 0;
                    for (int i = 0; i < s.length(); i++) {
                        char c = s.charAt(i);
                        if (isVowel(String.valueOf(c))) {
                            vowelIndex = i;
                            break;
                        }
                    }
                    if (s.substring(vowelIndex).toLowerCase().startsWith("ink")) {
                        return "gamerword";
                    } else return "ch" + s.substring(vowelIndex).toLowerCase();
                }
        ).collect(Collectors.joining(" "));

        event.getHook().sendMessage("Oh! I think you mean: '" + newMessage.stripLeading() + "'").queue(getConsumer(deleteDelay));
    }


    @Override
    public String getName() {
        return "ch";
    }

    @Override
    public String getDescription() {
        return "Allow me to 'ch' whatever you type.";
    }

    private boolean isVowel(String s) {
        List<String> vowels = List.of("a", "e", "i", "o", "u");
        return vowels.contains(s.toLowerCase());
    }

    @Override
    public List<OptionData> getOptionData() {
        return List.of(new OptionData(OptionType.STRING, MESSAGE, "Message to 'Ch'", true));
    }
}