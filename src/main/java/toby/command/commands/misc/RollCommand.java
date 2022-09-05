package toby.command.commands.misc;

import net.dv8tion.jda.api.entities.TextChannel;
import toby.command.CommandContext;
import toby.command.ICommand;
import toby.jpa.dto.UserDto;

import java.util.List;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

public class RollCommand implements IMiscCommand {
    @Override
    public void handle(CommandContext ctx, String prefix, UserDto requestingUserDto, Integer deleteDelay) {
        ICommand.deleteAfter(ctx.getMessage(), deleteDelay);
        TextChannel channel = ctx.getChannel();
        List<String> args = ctx.getArgs();

        Random rand = ThreadLocalRandom.current();
        String rollValue = (!args.isEmpty()) ? args.get(0) : "";
        int diceRoll = !rollValue.isEmpty() ? Integer.parseInt(rollValue) : 6;
        int roll = rand.nextInt(diceRoll) + 1; //This results in 1 - 6 (instead of 0 - 5) for default value
        channel.sendMessageFormat("You rolled a '%d' sided dice. You rolled a '%d'", diceRoll, roll).queue(message -> ICommand.deleteAfter(message, deleteDelay));
    }


    @Override
    public String getName() {
        return "roll";
    }

    @Override
    public String getHelp(String prefix) {
        return "Roll an X sided dice.\n" +
                String.format("Usage: `%sroll number` \n", prefix) +
                "If no number is provided, 6 sided dice is rolled";

    }
}
