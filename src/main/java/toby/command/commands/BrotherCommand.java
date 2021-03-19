package toby.command.commands;

import net.dv8tion.jda.api.entities.Emote;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.TextChannel;
import toby.DatabaseHelper;
import toby.command.CommandContext;
import toby.command.ICommand;
import toby.emote.Emotes;

public class BrotherCommand implements ICommand {
    @Override
    public void handle(CommandContext ctx) {
        final TextChannel channel = ctx.getChannel();
        final Message message = ctx.getMessage();
        Guild guild = ctx.getGuild();
        Emote tobyEmote = guild.getJDA().getEmoteById(Emotes.TOBY);

        if (message.getMentionedMembers().isEmpty()) {
            String brotherName = DatabaseHelper.getBrotherName(message.getAuthor().getId());
            if (brotherName!=null) {
                channel.sendMessage(String.format("Of course you're my brother %s.", brotherName)).queue();
            } else if (DatabaseHelper.tobyId.equals(message.getAuthor().getIdLong())) {
                channel.sendMessage(String.format("You're not my fucking brother Toby, you're me %s",tobyEmote)).queue();
            } else
                channel.sendMessage(String.format("You're not my fucking brother %s ffs %s", message.getMember().getEffectiveName(), tobyEmote)).queue();
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