package toby.command.commands;

import net.dv8tion.jda.api.entities.TextChannel;
import toby.command.CommandContext;
import toby.command.ICommand;
import toby.jpa.service.IConfigService;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;


public class HelloThereCommand implements ICommand {

    private final IConfigService configService;

    public HelloThereCommand(IConfigService configService) {
        this.configService = configService;
    }

    @Override
    public void handle(CommandContext ctx) {
        TextChannel channel = ctx.getChannel();
        List<String> args = ctx.getArgs();

        String dateformat = configService.getConfigByName("DATEFORMAT", ctx.getGuild().getId()).getValue();
        DateTimeFormatter dateTimeFormatter = DateTimeFormatter.ofPattern(dateformat);
        LocalDate EP3Date = LocalDate.parse("2005/05/19", dateTimeFormatter);

        if (args.size() == 0) {
            channel.sendMessage(getHelp()).queue();
        } else
            try {
                LocalDate dateGiven = LocalDate.parse(args.get(0), dateTimeFormatter);
                if (dateGiven.isBefore(EP3Date)) {
                    channel.sendMessage("Hello.").queue();
                } else {
                    channel.sendMessage("General Kenobi.").queue();
                }
            } catch (DateTimeParseException e) {
                channel.sendMessage(String.format("I don't recognise the format of the date you gave me, please use this format %s", dateformat)).queue();
            }

    }


    @Override
    public String getName() {
        return "hellothere";
    }

    @Override
    public String getHelp() {
        return """
                Hmm...\s
                As I am a bot I have a bad understanding of time, can you please let me know what the date is so I can greet you appropriately.\s
                Usage: `!hellothere yyyy/MM/dd`\s
                e.g. `!hellothere 2005/05/18`\s
                """;

    }
}
