package toby.command.commands.misc;

import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.TextChannel;
import toby.command.CommandContext;
import toby.jpa.dto.UserDto;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public class ChCommand implements IMiscCommand {

    @Override
    public void handle(CommandContext ctx, String prefix, UserDto requestingUserDto) {
        final TextChannel channel = ctx.getChannel();
        final Message message = ctx.getMessage();

        String newMessage = Arrays.stream(message.getContentRaw().split(" ")).map(s -> {
            if (s.equalsIgnoreCase(String.format("%sch", prefix))) {
                return "";
            } else {
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
        }).collect(Collectors.joining(" "));

        channel.sendMessage("Oh! I think you mean: '" + newMessage.stripLeading() + "'").queue();
    }


    @Override
    public String getName() {
        return "ch";
    }

    @Override
    public String getHelp(String prefix) {
        return "Allow me to translate whatever you type.\n" +
                String.format("Usage: `%sch example message here`", prefix);
    }

    private boolean isVowel(String s) {
        List<String> vowels = List.of("a", "e", "i", "o", "u");
        return vowels.contains(s.toLowerCase());
    }
}