package commands;

import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.requests.RestAction;
import net.dv8tion.jda.api.requests.restaction.WebhookMessageCreateAction;
import net.dv8tion.jda.api.requests.restaction.interactions.ReplyCallbackAction;
import net.dv8tion.jda.internal.requests.restaction.WebhookMessageCreateActionImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.Mock;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public interface CommandTest {

    @Mock
    SlashCommandInteractionEvent event = mock(SlashCommandInteractionEvent.class);

    @Mock
    InteractionHook interactionHook = mock(InteractionHook.class);

    @Mock
    WebhookMessageCreateAction<Message> messageCreateAction = mock(WebhookMessageCreateActionImpl.class);

    @BeforeEach
    default void setUpCommonMocks() {
        when(event.getHook()).thenReturn(interactionHook);
        when(event.deferReply()).thenReturn(mock(ReplyCallbackAction.class));
        when(interactionHook.deleteOriginal()).thenReturn(mock(RestAction.class));
        when(interactionHook.sendMessage(anyString())).thenReturn(messageCreateAction);
        when(interactionHook.sendMessageFormat(anyString(), any(Object[].class))).thenReturn(messageCreateAction);
        when(messageCreateAction.addActionRow(anyCollection())).thenReturn(messageCreateAction);
        when(messageCreateAction.setEphemeral(anyBoolean())).thenReturn(messageCreateAction);
    }

    @AfterEach
    default void tearDownCommonMocks(){
        reset(event);
        reset(interactionHook);
        reset(messageCreateAction);
    }

}
