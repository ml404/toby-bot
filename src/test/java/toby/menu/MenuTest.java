package toby.menu;

import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.Mock;
import toby.command.CommandTest;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

public interface MenuTest extends CommandTest {

    @Mock
    StringSelectInteractionEvent menuEvent = mock(StringSelectInteractionEvent.class);

    @BeforeEach
    default void setUpMenuMocks(){
        setUpCommonMocks();
        when(menuEvent.getHook()).thenReturn(interactionHook);
        when(menuEvent.deferReply()).thenReturn(replyCallbackAction);
        when(menuEvent.deferReply()).thenReturn(replyCallbackAction);
        when(menuEvent.getGuild()).thenReturn(guild);
        when(menuEvent.getUser()).thenReturn(user);
        when(menuEvent.reply(anyString())).thenReturn(replyCallbackAction);
        when(menuEvent.replyFormat(anyString(), any())).thenReturn(replyCallbackAction);
    }

    @AfterEach
    default void tearDownMenuMocks(){
        tearDownCommonMocks();
        reset(menuEvent);
    }


}
