package toby.command.commands.misc;

import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import toby.command.CommandContext;
import toby.command.ICommand;
import toby.jpa.dto.UserDto;
import toby.jpa.service.IConfigService;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;


public class HelloThereCommand implements IMiscCommand {

    private final IConfigService configService;

    public HelloThereCommand(IConfigService configService) {
        this.configService = configService;
    }

    @Override
    public void handle(CommandContext ctx, UserDto requestingUserDto, Integer deleteDelay) {
        ICommand.deleteAfter(ctx.getEvent().getHook(), deleteDelay);
        SlashCommandInteractionEvent event = ctx.getEvent();
        List <OptionMapping> args = ctx.getEvent().getOptions();

        String dateformat = configService.getConfigByName("DATEFORMAT", event.getGuild().getId()).getValue();
        DateTimeFormatter dateTimeFormatter = DateTimeFormatter.ofPattern(dateformat);
        LocalDate EP3Date = LocalDate.parse("2005/05/19", dateTimeFormatter);

        if (args.isEmpty()) {
            event.reply(getDescription()).queue(message -> ICommand.deleteAfter(message, deleteDelay));
        } else
            try {
                LocalDate dateGiven = LocalDate.parse(event.getOption("Date").getAsString(), dateTimeFormatter);
                if (dateGiven.isBefore(EP3Date)) {
                    event.reply("Hello.").queue(message -> ICommand.deleteAfter(message, deleteDelay));
                } else {
                    event.reply("General Kenobi.").queue(message -> ICommand.deleteAfter(message, deleteDelay));
                }
            } catch (DateTimeParseException e) {
                event.replyFormat("I don't recognise the format of the date you gave me, please use this format %s", dateformat).queue(message -> ICommand.deleteAfter(message, deleteDelay));
            }

    }


    @Override
    public String getName() {
        return "hellothere";
    }

    @Override
    public String getDescription() {
        return  "I have a bad understanding of time, let me know what the date is so I can greet you appropriately";

    }

    @Override
    public List<OptionData> getOptionData() {
        return List.of(new OptionData(OptionType.STRING, "Date", "What is the date you would like to say hello to TobyBot for?"));
    }
}
