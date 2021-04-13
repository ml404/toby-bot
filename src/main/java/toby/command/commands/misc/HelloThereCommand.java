package toby.command.commands.misc;

import net.dv8tion.jda.api.entities.TextChannel;
import toby.command.CommandContext;
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
    public void handle(CommandContext ctx, String prefix, UserDto requestingUserDto) {
        TextChannel channel = ctx.getChannel();
        List<String> args = ctx.getArgs();

        String dateformat = configService.getConfigByName("DATEFORMAT", ctx.getGuild().getId()).getValue();
        DateTimeFormatter dateTimeFormatter = DateTimeFormatter.ofPattern(dateformat);
        LocalDate EP3Date = LocalDate.parse("2005/05/19", dateTimeFormatter);

        if (args.size() == 0) {
            channel.sendMessage(getHelp(prefix)).queue();
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
    public String getHelp(String prefix) {
        return  "Hmm...\n" +
                "As I am a bot I have a bad understanding of time, can you please let me know what the date is so I can greet you appropriately.\n" +
                String.format("Usage: `%shellothere yyyy/MM/dd`\n", prefix) +
                String.format("e.g. `%shellothere 2005/05/18`\n", prefix);

    }
}
