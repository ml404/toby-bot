package toby.helpers;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.requests.restaction.MessageEditAction;
import net.dv8tion.jda.api.requests.restaction.WebhookMessageEditAction;
import net.dv8tion.jda.internal.requests.restaction.AuditableRestActionImpl;
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
    Member member;
    private List<Member> memberList;
    private Map<Member, Integer> initiativeMap;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        // Call rollInitiativeForMembers before testing other methods
        Member player1 = mock(Member.class);
        Member player2 = mock(Member.class);
        Member player3 = mock(Member.class);
        memberList = Arrays.asList(player1, player2, player3);
        initiativeMap = new HashMap<>();
        AuditableRestActionImpl auditableRestAction = mock(AuditableRestActionImpl.class);
        when(message.delete()).thenReturn(auditableRestAction);
        DnDHelper.clearInitiative(123456789L);
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
        long guildId = 123456789L;
        DnDHelper.setCurrentMessage(guildId, message);

        WebhookMessageEditAction webhookMessageEditAction = mock(WebhookMessageEditAction.class);
        when(message.editMessageEmbeds(any(MessageEmbed.class))).thenReturn(mock(MessageEditAction.class));
        when(hook.editOriginalEmbeds(any(MessageEmbed.class))).thenReturn(webhookMessageEditAction);

        // Mock the behavior of setActionRow to return the same WebhookMessageEditAction
        when(webhookMessageEditAction.setActionRow(any(), any(), any()))
                .thenReturn(webhookMessageEditAction);

        DnDHelper.rollInitiativeForMembers(memberList, member, initiativeMap);
        DnDHelper.incrementTurnTable(hook, guildId);

        // Verify that setActionRow and queue are called
        verify(webhookMessageEditAction, times(1)).setActionRow(any(), any(), any());
        verify(webhookMessageEditAction, times(1)).queue();

        verify(hook, times(1)).editOriginalEmbeds(any(MessageEmbed.class));
        assertEquals(1, DnDHelper.getInitiativeIndex().get());
    }

    @Test
    void testDecrementTurnTable() {
        long guildId = 123456789L;
        DnDHelper.setCurrentMessage(guildId, message);

        WebhookMessageEditAction webhookMessageEditAction = mock(WebhookMessageEditAction.class);
        when(message.editMessageEmbeds(any(MessageEmbed.class))).thenReturn(mock(MessageEditAction.class));
        when(hook.editOriginalEmbeds(any(MessageEmbed.class))).thenReturn(webhookMessageEditAction);

        // Mock the behavior of setActionRow to return the same WebhookMessageEditAction
        when(webhookMessageEditAction.setActionRow(any(), any(), any()))
                .thenReturn(webhookMessageEditAction);

        DnDHelper.rollInitiativeForMembers(memberList, member, initiativeMap);
        DnDHelper.decrementTurnTable(hook, guildId);

        // Verify that setActionRow and queue are called
        verify(webhookMessageEditAction, times(1)).setActionRow(any(), any(), any());
        verify(webhookMessageEditAction, times(1)).queue();

        verify(hook, times(1)).editOriginalEmbeds(any(MessageEmbed.class));
        assertEquals(2, DnDHelper.getInitiativeIndex().get());
    }


    @Test
    void testGetInitButtons() {
        DnDHelper.TableButtons buttons = DnDHelper.getInitButtons();

        assertNotNull(buttons);
        assertEquals(Emoji.fromUnicode("⬅️").getName(), buttons.prev().getEmoji().getName());
        assertEquals(Emoji.fromUnicode("❌").getName(), buttons.stop().getEmoji().getName());
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

        DnDHelper.setCurrentMessage(guildId, message);

        DnDHelper.rollInitiativeForMembers(memberList, member, initiativeMap);
        DnDHelper.clearInitiative(guildId);

        verify(message, times(1)).delete();
        assertNull(DnDHelper.getCurrentMessage(guildId));
        assertEquals(0, DnDHelper.getInitiativeIndex().get());
        assertEquals(0, DnDHelper.getSortedEntries().size());
    }

}
