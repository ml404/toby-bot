package toby.command.commands;

import com.jagrosh.jdautilities.commons.waiter.EventWaiter;
import net.dv8tion.jda.api.entities.TextChannel;
import net.dv8tion.jda.api.events.message.guild.react.GuildMessageReactionAddEvent;
import toby.command.CommandContext;
import toby.command.ICommand;
import toby.emote.Emotes;
import toby.jpa.dto.UserDto;

import java.util.Objects;
import java.util.concurrent.TimeUnit;

public class EventWaiterCommand implements ICommand {

    private final EventWaiter waiter;

    public EventWaiterCommand(EventWaiter waiter){
        this.waiter = waiter;
    }

    @Override
    public void handle(CommandContext ctx, String prefix, UserDto requestingUserDto) {
        final TextChannel channel = ctx.getChannel();
        channel.sendMessage("React with")
                .append(Emotes.TOBY)
                .queue(message -> {
                    message.addReaction(Objects.requireNonNull(ctx.getGuild().getJDA().getEmoteById(Emotes.TOBY))).queue();
                    this.waiter.waitForEvent(
                            GuildMessageReactionAddEvent.class,
                            e -> e.getMessageIdLong() == message.getIdLong() && !e.getUser().isBot(),
                            e -> {
                                channel.sendMessageFormat("%s was the first to react", e.getUser().getName()).queue();
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
