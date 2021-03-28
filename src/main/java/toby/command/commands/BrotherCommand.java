package toby.command.commands;

import net.dv8tion.jda.api.entities.Emote;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.TextChannel;
import org.springframework.beans.factory.annotation.Configurable;
import org.springframework.stereotype.Service;
import toby.command.CommandContext;
import toby.command.ICommand;
import toby.emote.Emotes;
import toby.jpa.dto.BrotherDto;
import toby.jpa.service.IBrotherService;


@Service
@Configurable
public class BrotherCommand implements ICommand {

    private final IBrotherService brotherService;
    public static Long tobyId = 320919876883447808L;

    public BrotherCommand(IBrotherService brotherService) {
        this.brotherService = brotherService;
    }

    @Override
    public void handle(CommandContext ctx, String prefix) {
        final TextChannel channel = ctx.getChannel();
        final Message message = ctx.getMessage();
        Guild guild = ctx.getGuild();
        Emote tobyEmote = guild.getJDA().getEmoteById(Emotes.TOBY);

        if (message.getMentionedMembers().isEmpty()) {
            BrotherDto brother = brotherService.getBrotherById(message.getAuthor().getIdLong());
            if (brother!=null) {
                channel.sendMessage(String.format("Of course you're my brother %s.", brother.getBrotherName())).queue();
            } else if (tobyId.equals(message.getAuthor().getIdLong())) {
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
    public String getHelp(String prefix) {
        return "Let me tell you if you're my brother.\n" +
                String.format("Usage: `%sbrother`", prefix);
    }
}