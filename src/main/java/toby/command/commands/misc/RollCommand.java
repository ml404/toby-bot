package toby.command.commands.misc;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.callbacks.IReplyCallback;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import org.jetbrains.annotations.NotNull;
import toby.command.CommandContext;
import toby.command.ICommand;
import toby.jpa.dto.UserDto;

import java.util.*;
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
        int diceValue = diceValueOptional.orElse(20);
        Integer diceToRollInput = diceToRollOptional.orElse(1);
        int diceToRoll = (diceToRollInput < 1) ? 1 : diceToRollInput;
        int modifier = diceModifierOptional.orElse(0);
        handleDiceRoll(deleteDelay, event, diceValue, diceToRoll, modifier);
    }

    public void handleDiceRoll(Integer deleteDelay, IReplyCallback event, int diceValue, int diceToRoll, int modifier) {
        int rollTotal = 0;
        Random rand = ThreadLocalRandom.current();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < diceToRoll; i++) {
            int roll = rand.nextInt(diceValue) + 1; //This results in 1 - 20 (instead of 0 - 19) for default value
            rollTotal += roll;
            sb.append(String.format("'%d' sided dice rolled. You got a '%d'. \n", diceValue, roll));
        }
        sb.append(String.format("Your final roll total was '%d' + '%d' modifier = '%d'.", rollTotal, modifier, rollTotal + modifier));
        EmbedBuilder embedBuilder = new EmbedBuilder()
                .addField(new MessageEmbed.Field(String.format("D%d * %d + '%d' modifier", diceValue, diceToRoll, modifier), sb.toString(), true))
                .setColor(0x00FF00); // Green color

        Button rollD20 = Button.primary(getName() + ":" + "20, 1, 0", "Roll D20");
        Button rollD10 = Button.primary(getName() + ":" + "10, 1, 0", "Roll D10");
        Button rollD6 = Button.primary(getName() + ":" + "6, 1, 0", "Roll D6");
        Button rollD4 = Button.primary(getName() + ":" + "4, 1, 0", "Roll D4");
        event.getHook().sendMessageEmbeds(embedBuilder.build())
                .addActionRow(Button.primary("resend_last_request", "Click to Reroll"), rollD20, rollD10, rollD6, rollD4)
                .queue(message -> ICommand.deleteAfter(message, deleteDelay));
    }


    @Override
    public String getName() {
        return "roll";
    }

    @Override
    public String getDescription() {
        return "Roll an X sided dice Y times with a Z modifier. (Default 20 sided dice, 1 roll and 0 modifier)";
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
