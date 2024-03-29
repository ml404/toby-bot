package toby.command.commands.misc;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.callbacks.IReplyCallback;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import net.dv8tion.jda.api.requests.restaction.WebhookMessageCreateAction;
import org.jetbrains.annotations.NotNull;
import toby.command.CommandContext;
import toby.jpa.dto.UserDto;

import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

import static toby.command.ICommand.invokeDeleteOnMessageResponse;
import static toby.helpers.DnDHelper.rollDice;

public class RollCommand implements IMiscCommand {

    private final String DICE_NUMBER = "number";
    private final String DICE_TO_ROLL = "amount";
    private final String MODIFIER = "modifier";

    @Override
    public void handle(CommandContext ctx, UserDto requestingUserDto, Integer deleteDelay) {
        SlashCommandInteractionEvent event = ctx.getEvent();
        Optional<Integer> diceValueOptional = Optional.ofNullable(event.getOption(DICE_NUMBER)).map(OptionMapping::getAsInt);
        Optional<Integer> diceToRollOptional = Optional.ofNullable(event.getOption(DICE_TO_ROLL)).map(OptionMapping::getAsInt);
        Optional<Integer> diceModifierOptional = Optional.ofNullable(event.getOption(MODIFIER)).map(OptionMapping::getAsInt);
        int diceValue = diceValueOptional.orElse(20);
        Integer diceToRollInput = diceToRollOptional.orElse(1);
        int diceToRoll = (diceToRollInput < 1) ? 1 : diceToRollInput;
        int modifier = diceModifierOptional.orElse(0);
        handleDiceRoll(event, diceValue, diceToRoll, modifier).queue(invokeDeleteOnMessageResponse(deleteDelay));
    }

    public WebhookMessageCreateAction<Message> handleDiceRoll(IReplyCallback event, int diceValue, int diceToRoll, int modifier) {
        event.deferReply().queue();
        StringBuilder sb = buildStringForDiceRoll(diceValue, diceToRoll, modifier);
        EmbedBuilder embedBuilder = new EmbedBuilder()
                .addField(new MessageEmbed.Field(String.format("%dd%d + %d", diceToRoll, diceValue, modifier), sb.toString(), true))
                .setColor(0x00FF00); // Green color

        Button rollD20 = Button.primary(getName() + ":" + "20, 1, 0", "Roll D20");
        Button rollD10 = Button.primary(getName() + ":" + "10, 1, 0", "Roll D10");
        Button rollD6 = Button.primary(getName() + ":" + "6, 1, 0", "Roll D6");
        Button rollD4 = Button.primary(getName() + ":" + "4, 1, 0", "Roll D4");
        return event.getHook().sendMessageEmbeds(embedBuilder.build()).addActionRow(Button.primary("resend_last_request", "Click to Reroll"), rollD20, rollD10, rollD6, rollD4);
    }

    @NotNull
    private static StringBuilder buildStringForDiceRoll(int diceValue, int diceToRoll, int modifier) {
        StringBuilder sb = new StringBuilder();
        int rollTotal = rollDice(diceValue, diceToRoll);
        sb.append(String.format("Your final roll total was '%d' (%d + %d).", rollTotal + modifier, rollTotal, modifier));
        return sb;
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
