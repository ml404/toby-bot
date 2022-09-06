package toby.command.commands.misc;

import com.jagrosh.jdautilities.commons.waiter.EventWaiter;
import net.dv8tion.jda.api.entities.TextChannel;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import toby.command.CommandContext;
import toby.command.ICommand;
import toby.emote.Emotes;
import toby.jpa.dto.UserDto;

import java.util.concurrent.TimeUnit;

public class EventWaiterCommand implements IMiscCommand {

    private final EventWaiter waiter;

    public EventWaiterCommand(EventWaiter waiter){
        this.waiter = waiter;
    }

    @Override
    public void handle(CommandContext ctx, String prefix, UserDto requestingUserDto, Integer deleteDelay) {
        ICommand.deleteAfter(ctx.getMessage(), deleteDelay);
        final TextChannel channel = ctx.getChannel();
        Emoji emoteById = ctx.getGuild().getJDA().getEmojiById(Emotes.TOBY);
        channel.sendMessageFormat("React with %s", emoteById)
                .queue(message -> {
                    message.addReaction(emoteById).queue();

                    this.waiter.waitForEvent(
                            MessageReceivedEvent.class,
                            e -> e.getMessageIdLong() == message.getIdLong() && !e.getAuthor().isBot(),
                            e -> {
                                channel.sendMessageFormat("%s was the first to react", e.getAuthor().getName()).queue();
                            },
                            5L, TimeUnit.SECONDS,
                            () -> channel.sendMessage("You waited too long").queue()
                    );
                });
    }

    @Override
    public String getName() {
        return "eventwaiter";
    }

    @Override
    public String getHelp(String prefix) {
        return "This is a test of the eventwaiter structure in JDA. It's not that useful yet.";
    }
}
