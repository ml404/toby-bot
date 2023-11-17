package toby.helpers;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.components.LayoutComponent;
import net.dv8tion.jda.api.requests.RestAction;
import net.dv8tion.jda.api.requests.restaction.MessageEditAction;
import net.dv8tion.jda.api.requests.restaction.WebhookMessageCreateAction;
import net.dv8tion.jda.api.requests.restaction.WebhookMessageEditAction;
import net.dv8tion.jda.internal.requests.restaction.AuditableRestActionImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DnDHelperTest {

    @Mock
    InteractionHook hook;

    @Mock
    Message message;

    @Mock
    ButtonInteractionEvent event;

    @Mock
    Member member;
    @Mock
    private WebhookMessageEditAction webhookMessageEditAction;

    @Mock
    WebhookMessageCreateAction webhookMessageCreateAction;
    @Mock
    private MessageEditAction messageEditAction;
    private List<Member> memberList;
    private Map<String, Integer> initiativeMap;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        // Call rollInitiativeForMembers before testing other methods
        Member player1 = mock(Member.class);
        Member player2 = mock(Member.class);
        Member player3 = mock(Member.class);
        User user1 = mock(User.class);
        User user2 = mock(User.class);
        User user3 = mock(User.class);
        memberList = Arrays.asList(player1, player2, player3);
        when(player1.getUser()).thenReturn(user1);
        when(player2.getUser()).thenReturn(user2);
        when(player3.getUser()).thenReturn(user3);
        when(user1.isBot()).thenReturn(false);
        when(user2.isBot()).thenReturn(false);
        when(user3.isBot()).thenReturn(false);
        when(user1.getEffectiveName()).thenReturn("name 1");
        when(user2.getEffectiveName()).thenReturn("name 2");
        when(user3.getEffectiveName()).thenReturn("name 3");
        AuditableRestActionImpl auditableRestAction = mock(AuditableRestActionImpl.class);
        event = mock(ButtonInteractionEvent.class);
        initiativeMap = new HashMap<>();
        webhookMessageEditAction = mock(WebhookMessageEditAction.class);
        webhookMessageCreateAction = mock(WebhookMessageCreateAction.class);
        when(event.getMessage()).thenReturn(message);
        messageEditAction = mock(MessageEditAction.class);
        when(message.editMessageEmbeds(any(MessageEmbed.class))).thenReturn(messageEditAction);
        when(messageEditAction.setActionRow(any(), any(), any())).thenReturn(messageEditAction);
        when(message.delete()).thenReturn(auditableRestAction);
        DnDHelper.clearInitiative();
        when(hook.deleteOriginal()).thenReturn(mock(RestAction.class));
        when(hook.setEphemeral(true)).thenReturn(hook);
        when(hook.sendMessageFormat(anyString(), any())).thenReturn(webhookMessageCreateAction);
    }

    @AfterEach
    public void tearDown() {
        reset(hook);
        reset(webhookMessageCreateAction);
        reset(webhookMessageEditAction);
        reset(message);
        reset(event);
        reset(messageEditAction);
        DnDHelper.clearInitiative();
    }

    @Test
    void testRollDiceWithModifier() {
        int result = DnDHelper.rollDiceWithModifier(20, 1, 5);
        assertTrue(result >= 6 && result <= 25, "Result should be between 6 and 25 (inclusive)");
    }

    @Test
    void testRollDice() {
        int result = DnDHelper.rollDice(20, 2);
        assertTrue(result >= 2 && result <= 40, "Result should be between 2 and 40 (inclusive)");
    }

    @Test
    void testIncrementTurnTable() {
        WebhookMessageCreateAction webhookMessageCreateAction = mock(WebhookMessageCreateAction.class);
        WebhookMessageEditAction webhookMessageEditAction = mock(WebhookMessageEditAction.class);
        when(hook.sendMessageEmbeds(any(MessageEmbed.class))).thenReturn(webhookMessageCreateAction);
        when(hook.editOriginalComponents(any(LayoutComponent.class))).thenReturn(webhookMessageEditAction);
        when(hook.editOriginalEmbeds(any(MessageEmbed.class))).thenReturn(webhookMessageEditAction);

        // Mock the behavior of setActionRow to return the same WebhookMessageEditAction
        when(webhookMessageCreateAction.setActionRow(any(), any(), any())).thenReturn(webhookMessageCreateAction);
        when(webhookMessageEditAction.setActionRow(any(), any(), any())).thenReturn(webhookMessageEditAction);

        DnDHelper.rollInitiativeForMembers(memberList, member, initiativeMap);
        DnDHelper.sendOrEditInitiativeMessage(hook, DnDHelper.getInitiativeEmbedBuilder(), null);
        DnDHelper.incrementTurnTable(hook, event);
        verifySetActionRows(webhookMessageCreateAction, messageEditAction);


        // Verify that queue is called once
        verify(webhookMessageCreateAction, times(1)).queue();

        verify(hook, times(1)).sendMessageEmbeds(any(MessageEmbed.class));
        verify(messageEditAction, times(1)).queue();
        assertEquals(1, DnDHelper.getInitiativeIndex().get());
    }

    @Test
    void testDecrementTurnTable() {
        {
            WebhookMessageCreateAction webhookMessageCreateAction = mock(WebhookMessageCreateAction.class);
            WebhookMessageEditAction webhookMessageEditAction = mock(WebhookMessageEditAction.class);
            when(hook.sendMessageEmbeds(any(MessageEmbed.class))).thenReturn(webhookMessageCreateAction);
            when(hook.editOriginalComponents(any(LayoutComponent.class))).thenReturn(webhookMessageEditAction);
            when(hook.editOriginalEmbeds(any(MessageEmbed.class))).thenReturn(webhookMessageEditAction);

            // Mock the behavior of setActionRow to return the same WebhookMessageEditAction
            when(webhookMessageCreateAction.setActionRow(any(), any(), any())).thenReturn(webhookMessageCreateAction);
            when(webhookMessageEditAction.setActionRow(any(), any(), any())).thenReturn(webhookMessageEditAction);

            DnDHelper.rollInitiativeForMembers(memberList, member, initiativeMap);
            DnDHelper.sendOrEditInitiativeMessage(hook, DnDHelper.getInitiativeEmbedBuilder(), null);
            DnDHelper.decrementTurnTable(hook, event);
            verifySetActionRows(webhookMessageCreateAction, messageEditAction);


            // Verify that queue is called once
            verify(webhookMessageCreateAction, times(1)).queue();

            verify(hook, times(1)).sendMessageEmbeds(any(MessageEmbed.class));
            verify(messageEditAction, times(1)).queue();
            assertEquals(2, DnDHelper.getInitiativeIndex().get());
        }
    }

    @Test
    void testGetInitButtons() {
        DnDHelper.TableButtons buttons = DnDHelper.getInitButtons();

        assertNotNull(buttons);
        assertEquals(Emoji.fromUnicode("⬅️").getName(), buttons.prev().getEmoji().getName());
        assertEquals(Emoji.fromUnicode("❌").getName(), buttons.clear().getEmoji().getName());
        assertEquals(Emoji.fromUnicode("➡️").getName(), buttons.next().getEmoji().getName());
    }

    @Test
    void testGetInitiativeEmbedBuilder() {
        EmbedBuilder embedBuilder = DnDHelper.getInitiativeEmbedBuilder();

        assertNotNull(embedBuilder);
        assertEquals("Initiative Order", embedBuilder.build().getTitle());
    }

    @Test
    void testClearInitiative() {
        long guildId = 123456789L;

        DnDHelper.rollInitiativeForMembers(memberList, member, initiativeMap);
        DnDHelper.clearInitiative();

        assertEquals(0, DnDHelper.getInitiativeIndex().get());
        assertEquals(0, DnDHelper.getSortedEntries().size());
    }

    private static void verifySetActionRows(WebhookMessageCreateAction webhookMessageCreateAction, MessageEditAction messageEditAction) {
        // Verify that setActionRow is called once for initial setup with the correct buttons
        verify(webhookMessageCreateAction, times(1)).setActionRow(
                eq(DnDHelper.getInitButtons().prev()),
                eq(DnDHelper.getInitButtons().clear()),
                eq(DnDHelper.getInitButtons().next())
        );

        verify(messageEditAction, times(1)).setActionRow(
                eq(DnDHelper.getInitButtons().prev()),
                eq(DnDHelper.getInitButtons().clear()),
                eq(DnDHelper.getInitButtons().next())
        );
    }

}
