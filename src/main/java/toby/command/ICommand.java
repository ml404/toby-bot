package toby.command;

import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import org.jetbrains.annotations.NotNull;
import toby.jpa.dto.UserDto;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public interface ICommand {

    void handle(CommandContext ctx, UserDto requestingUserDto, Integer deleteDelay);

    String getName();

    String getDescription();

    static void deleteAfter(InteractionHook interactionHook, int delay) {
        interactionHook.deleteOriginal().queueAfter(delay, TimeUnit.SECONDS);
    }

    static void deleteAfter(Message message, int delay) {
        message.delete().queueAfter(delay, TimeUnit.SECONDS);
    }


    default String getErrorMessage(String serverOwner) {
        return String.format("You do not have adequate permissions to use this command, if you believe this is a mistake talk to the server owner: %s", serverOwner);
    }

    default void sendErrorMessage(SlashCommandInteractionEvent event, Integer deleteDelay) {
        Member owner = event.getGuild().getOwner();
        event.getHook().sendMessageFormat(getErrorMessage(owner.getEffectiveName())).queue(message -> ICommand.deleteAfter(message, deleteDelay));
    }


    @NotNull
    static Consumer<Message> getConsumer(Integer deleteDelay) {
        return message -> ICommand.deleteAfter(message, deleteDelay);
    }
    @NotNull
    static Consumer<InteractionHook> getConsumerForHook(Integer deleteDelay) {
        return hook -> ICommand.deleteAfter(hook, deleteDelay);
    }

    default SlashCommandData getSlashCommand(){
        return Commands.slash(getName(), getDescription());
    }

    default List<OptionData> getOptionData() { return Collections.emptyList(); }
}