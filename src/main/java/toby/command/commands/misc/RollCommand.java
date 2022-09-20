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
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

public class RollCommand implements IMiscCommand {

    private final String DICE_NUMBER = "number";
    private final String DICE_TO_ROLL = "amount";
    private final String MODIFIER = "modifier";

    @Override
    public void handle(CommandContext ctx, UserDto requestingUserDto, Integer deleteDelay) {
        ICommand.deleteAfter(ctx.getEvent().getHook(), deleteDelay);
        SlashCommandInteractionEvent event = ctx.getEvent();
        event.deferReply().queue();
        Optional<Integer> diceValueOptional = Optional.ofNullable(event.getOption(DICE_NUMBER)).map(OptionMapping::getAsInt);
        Optional<Integer> diceToRollOptional = Optional.ofNullable(event.getOption(DICE_TO_ROLL)).map(OptionMapping::getAsInt);
        Optional<Integer> diceModifierOptional = Optional.ofNullable(event.getOption(MODIFIER)).map(OptionMapping::getAsInt);
        Random rand = ThreadLocalRandom.current();
        int diceValue = diceValueOptional.orElse(6);
        Integer diceToRollInput = diceToRollOptional.orElse(1);
        int diceToRoll = (diceToRollInput < 1) ? 1 : diceToRollInput;
        int modifier = diceModifierOptional.orElse(0);
        int rollTotal = 0;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < diceToRoll; i++) {
            int roll = rand.nextInt(diceValue) + 1; //This results in 1 - 6 (instead of 0 - 5) for default value
            rollTotal += roll;
            sb.append(String.format("'%d' sided dice rolled. You got a '%d'. \n", diceValue, roll));
        }
        sb.append(String.format("Your final roll total was '%d' + '%d'.", rollTotal, modifier));
        event.getHook().sendMessageFormat(sb.toString()).queue(message -> ICommand.deleteAfter(message, deleteDelay));

    }


    @Override
    public String getName() {
        return "roll";
    }

    @Override
    public String getDescription() {
        return "Roll an X sided dice Y times with a Z modifier. (Default 6 sided dice, 1 roll and 0 modifier)";
    }

    @Override
    @NotNull
    public List<OptionData> getOptionData() {
        OptionData diceNumberOption = new OptionData(OptionType.INTEGER, DICE_NUMBER, "What sided dice would you like to roll?");
        OptionData diceToRollOption = new OptionData(OptionType.INTEGER, DICE_TO_ROLL, "How many dice would you like to roll?");
        OptionData modifierOption = new OptionData(OptionType.INTEGER, MODIFIER, "What modifier applies to your roll?");
        return List.of(diceNumberOption, diceToRollOption, modifierOption);
    }
}
