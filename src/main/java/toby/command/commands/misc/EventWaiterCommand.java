package toby.command.commands.misc;

import com.jagrosh.jdautilities.commons.waiter.EventWaiter;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import toby.command.CommandContext;
import toby.command.ICommand;
import toby.emote.Emotes;
import toby.jpa.dto.UserDto;

public class EventWaiterCommand implements IMiscCommand {

    private final EventWaiter waiter;

    public EventWaiterCommand(EventWaiter waiter){
        this.waiter = waiter;
    }

    @Override
    public void handle(CommandContext ctx, UserDto requestingUserDto, Integer deleteDelay) {
        ICommand.deleteAfter(ctx.getEvent().getHook(), deleteDelay);
        final SlashCommandInteractionEvent event = ctx.getEvent();
        Emoji emoteById = event.getGuild().getJDA().getEmojiById(Emotes.TOBY);
//        event.replyFormat("React with %s", emoteById)
//                .queue(message -> {
//                    message.addReaction(emoteById).queue();
//
//                    this.waiter.waitForEvent(
//                            MessageReceivedEvent.class,
//                            e -> e.getMessageIdLong() == message.getIdLong() && !e.getAuthor().isBot(),
//                            e -> {
//                                event.replyFormat("%s was the first to react", e.getAuthor().getName().queue();
//                            },
//                            5L, TimeUnit.SECONDS,
//                            () -> channel.sendMessage("You waited too long").queue()
//                    );
//                });
    }

    @Override
    public String getName() {
        return "eventwaiter";
    }

    @Override
    public String getDescription() {
        return "This is a test of the eventwaiter structure in JDA. It's not that useful yet.";
    }
}
