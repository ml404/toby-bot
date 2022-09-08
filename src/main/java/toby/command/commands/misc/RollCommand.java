package toby.command.commands.misc;

import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import org.jetbrains.annotations.NotNull;
import toby.command.CommandContext;
import toby.command.ICommand;
import toby.jpa.dto.UserDto;

import java.util.List;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

public class RollCommand implements IMiscCommand {

    @Override
    public void handle(CommandContext ctx, UserDto requestingUserDto, Integer deleteDelay) {
        ICommand.deleteAfter(ctx.getEvent().getHook(), deleteDelay);
        SlashCommandInteractionEvent event = ctx.getEvent();
        OptionMapping arg = event.getOption("Dice Number");
        Random rand = ThreadLocalRandom.current();
        String rollValue = arg.getAsString();
        int diceRoll = !rollValue.isEmpty() ? Integer.parseInt(rollValue) : 6;
        int roll = rand.nextInt(diceRoll) + 1; //This results in 1 - 6 (instead of 0 - 5) for default value
        event.replyFormat("You chose to roll a '%d' sided dice. You rolled a '%d'", diceRoll, roll).queue(message -> ICommand.deleteAfter(message, deleteDelay));
    }


    @Override
    public String getName() {
        return "roll";
    }

    @Override
    public String getDescription() {
        return "Roll an X sided dice. (Default 6)";
    }

    @Override
    @NotNull
    public List<OptionData> getOptionData() {
        return List.of(new OptionData(OptionType.NUMBER, "Dice number", "What sided dice would you like to roll?"));
    }
}
