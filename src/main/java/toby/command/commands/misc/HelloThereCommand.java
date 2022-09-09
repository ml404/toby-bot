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
import java.util.Optional;


public class HelloThereCommand implements IMiscCommand {

    private final IConfigService configService;
    private final String DATE = "date";
    private String DATE_FORMAT;

    public HelloThereCommand(IConfigService configService) {
        this.configService = configService;
    }

    @Override
    public void handle(CommandContext ctx, UserDto requestingUserDto, Integer deleteDelay) {
        ICommand.deleteAfter(ctx.getEvent().getHook(), deleteDelay);
        SlashCommandInteractionEvent event = ctx.getEvent();
        event.deferReply().queue();
        List <OptionMapping> args = ctx.getEvent().getOptions();

        DATE_FORMAT = "DATEFORMAT";
        String dateformat = configService.getConfigByName(DATE_FORMAT, event.getGuild().getId()).getValue();
        DateTimeFormatter dateTimeFormatter = DateTimeFormatter.ofPattern(dateformat);
        LocalDate EP3Date = LocalDate.parse("2005/05/19", dateTimeFormatter);

        if (args.isEmpty()) {
            event.getHook().sendMessage(getDescription()).queue(message -> ICommand.deleteAfter(message, deleteDelay));
        } else
            try {
                LocalDate dateGiven = LocalDate.parse(Optional.ofNullable(event.getOption(DATE)).map(OptionMapping::getAsString).orElse(LocalDate.now().toString()), dateTimeFormatter);
                if (dateGiven.isBefore(EP3Date)) {
                    event.getHook().sendMessage("Hello.").queue(message -> ICommand.deleteAfter(message, deleteDelay));
                } else {
                    event.getHook().sendMessage("General Kenobi.").queue(message -> ICommand.deleteAfter(message, deleteDelay));
                }
            } catch (DateTimeParseException e) {
                event.getHook().sendMessageFormat("I don't recognise the format of the date you gave me, please use this format %s", dateformat).queue(message -> ICommand.deleteAfter(message, deleteDelay));
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
        return List.of(new OptionData(OptionType.STRING, "date", "What is the date you would like to say hello to TobyBot for?", true));
    }
}
