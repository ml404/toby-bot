package toby.command.commands;

import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.TextChannel;
import toby.BotConfig;
import toby.command.CommandContext;
import toby.command.ICommand;

import java.util.List;

public class BrotherCommand implements ICommand {
    @Override
    public void handle(CommandContext ctx) {
        final TextChannel channel = ctx.getChannel();
        final Message message = ctx.getMessage();

        if (message.getMentionedMembers().isEmpty()) {
            if (BotConfig.brotherList.contains(message.getAuthor().getIdLong())) {
                channel.sendMessage(String.format("Of course you're my brother %s", message.getAuthor())).queue();
            } else if (BotConfig.tobyId.equals(message.getAuthor().getIdLong())) {
                channel.sendMessage(String.format("You're not my fucking brother %s, you're me", message.getAuthor())).queue();
            } else
                channel.sendMessage(String.format("You're not my fucking brother %s ffs", message.getAuthor())).queue();
        }
    }

    @Override
    public String getName() {
        return "brother";
    }

    @Override
    public String getHelp() {
        return "Let me tell you if you're my brother.\n" +
                "Usage: `!brother`";
    }
}