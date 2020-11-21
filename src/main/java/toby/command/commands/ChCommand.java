package toby.command.commands;

import net.dv8tion.jda.api.entities.Emote;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.TextChannel;
import toby.BotConfig;
import toby.command.CommandContext;
import toby.command.ICommand;
import toby.emote.Emotes;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public class ChCommand implements ICommand {
    @Override
    public void handle(CommandContext ctx) {
        final TextChannel channel = ctx.getChannel();
        final Message message = ctx.getMessage();
        String newMessage = Arrays.stream(message.getContentRaw().split(" ")).map(s -> {
            if (s.equalsIgnoreCase("!ch")) {
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
                if (s.substring(vowelIndex).equalsIgnoreCase("ink")) {
                    return "gamerword";
                } else return "ch" + s.substring(vowelIndex);
            }
        }).collect(Collectors.joining(" "));

        channel.sendMessage("Oh! I think you mean: '" + newMessage + "'").queue();
    }


    @Override
    public String getName() {
        return "ch";
    }

    @Override
    public String getHelp() {
        return "Allow me to translate whatever you type.\n" +
                "Usage: `!ch example message here`";
    }

    private boolean isVowel(String s) {
        List<String> vowels = List.of("a", "e", "i", "o", "u");
        return vowels.contains(s.toLowerCase());
    }
}