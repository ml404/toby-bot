package toby.command.commands.misc;

import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import toby.command.CommandContext;
import toby.command.ICommand;
import toby.jpa.dto.UserDto;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public class ChCommand implements IMiscCommand {

    @Override
    public void handle(CommandContext ctx, UserDto requestingUserDto, Integer deleteDelay) {
        ICommand.deleteAfter(ctx.getEvent().getHook(), deleteDelay);
        final SlashCommandInteractionEvent event = ctx.getEvent();
        String message = event.getOption("Message").getAsString();

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

        event.reply("Oh! I think you mean: '" + newMessage.stripLeading() + "'").

                queue(message1 -> ICommand.deleteAfter(message1, deleteDelay));
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
        return List.of(new OptionData(OptionType.STRING, "Message", "Message to 'Ch'", true));
    }
}