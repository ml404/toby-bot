package toby.command.commands;

import net.dv8tion.jda.api.entities.TextChannel;
import toby.BotConfig;
import toby.command.CommandContext;
import toby.command.ICommand;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.TemporalAccessor;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

public class HelloCommand implements ICommand {
    @Override
    public void handle(CommandContext ctx) {
        TextChannel channel = ctx.getChannel();
        String msg = ctx.getMessage().getContentDisplay();
        List<String> args = ctx.getArgs();

        String dateformat = BotConfig.get("DATEFORMAT");
        DateTimeFormatter dateTimeFormatter = DateTimeFormatter.ofPattern(dateformat);
        LocalDate EP3Date = LocalDate.parse("2005/05/19", dateTimeFormatter);

        if (args.size() == 0) {
            channel.sendMessage(getHelp()).queue();
        } else
            try {
                LocalDate dateGiven = LocalDate.parse(args.get(0), dateTimeFormatter);
                if (dateGiven.isBefore(EP3Date)) {
                    channel.sendMessage("Hello").queue();
                } else {
                    channel.sendMessage("General Kenobi.").queue();
                }
            } catch (DateTimeParseException e) {
                channel.sendMessage(String.format("I don't recognise the format of the date you gave me, please use this format %s", dateformat)).queue();
            }

    }


    @Override
    public String getName() {
        return "hello";
    }

    @Override
    public String getHelp() {
        return "Hmm... \n" +
                "As I am a bot I have a bad understanding of time, can you please let me know what the date is so I can greet you appropriately. \n" +
                "Usage: `!hello yyyy/MM/dd` \n" +
                "e.g. `!hello 2005/05/18` \n";

    }
}
