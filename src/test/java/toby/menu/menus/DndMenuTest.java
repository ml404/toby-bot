package toby.menu.menus;

import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.requests.restaction.AuditableRestAction;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import toby.menu.MenuContext;
import toby.menu.MenuTest;

import java.util.List;

import static org.mockito.Mockito.*;

class DndMenuTest implements MenuTest {


    private DndMenu dndMenu;

    @BeforeEach
    public void setup(){
        setUpMenuMocks();
        dndMenu = new DndMenu();
        doReturn(webhookMessageCreateAction)
                .when(interactionHook)
                .sendMessageEmbeds(any(), any(MessageEmbed[].class));
    }

    @AfterEach
    public void tearDown(){
        tearDownMenuMocks();
    }

    @Test
    public void test_dndMenuWithSpell(){
        //Arrange
        MenuContext ctx = mockAndCreateMenuContext("dnd:spell", "fireball");

        //Act
        dndMenu.handle(ctx, 0);

        //Assert
        verify(menuEvent, times(1)).deferReply();
        verify(menuEvent, times(1)).getHook();
        verify(interactionHook, times(1)).sendMessageEmbeds(any(MessageEmbed.class));
    }

    @Test
    public void test_dndMenuWithCondition(){
        //Arrange
        MenuContext ctx = mockAndCreateMenuContext("dnd:condition", "grappled");

        //Act
        dndMenu.handle(ctx, 0);

        //Assert
        verify(menuEvent, times(1)).deferReply();
        verify(menuEvent, times(1)).getHook();
        verify(interactionHook, times(1)).sendMessageEmbeds(any(MessageEmbed.class));
    }

    @Test
    public void test_dndMenuWithRule(){
        //Arrange
        MenuContext ctx = mockAndCreateMenuContext("dnd:rule", "cover");

        //Act
        dndMenu.handle(ctx, 0);

        //Assert
        verify(menuEvent, times(1)).deferReply();
        verify(menuEvent, times(1)).getHook();
        verify(interactionHook, times(1)).sendMessageEmbeds(any(MessageEmbed.class));
    }

    @Test
    public void test_dndMenuWithFeature(){
        //Arrange
        MenuContext ctx = mockAndCreateMenuContext("dnd:feature", "action-surge-1-use");

        //Act
        dndMenu.handle(ctx, 0);

        //Assert
        verify(menuEvent, times(1)).deferReply();
        verify(menuEvent, times(1)).getHook();
        verify(interactionHook, times(1)).sendMessageEmbeds(any(MessageEmbed.class));
    }


    @NotNull
    private static MenuContext mockAndCreateMenuContext(String eventName, String selectedValue) {
        AuditableRestAction auditableRestAction = mock(AuditableRestAction.class);
        when(menuEvent.getComponentId()).thenReturn(eventName);
        when(menuEvent.getValues()).thenReturn(List.of(selectedValue));
        when(menuEvent.getMessage()).thenReturn(message);
        when(message.delete()).thenReturn(auditableRestAction);
        return new MenuContext(menuEvent);
    }
}