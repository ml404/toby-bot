package toby.helpers;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.requests.RestAction;
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
    Member member;
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
        initiativeMap = new HashMap<>();
        AuditableRestActionImpl auditableRestAction = mock(AuditableRestActionImpl.class);
        when(message.delete()).thenReturn(auditableRestAction);
        DnDHelper.clearInitiative(123456789L);
        when(hook.deleteOriginal()).thenReturn(mock(RestAction.class));
    }

    @AfterEach
    public void tearDown(){
        reset(hook);
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

        WebhookMessageCreateAction webhookMessageCreateAction = mock(WebhookMessageCreateAction.class);
        WebhookMessageEditAction webhookMessageEditAction = mock(WebhookMessageEditAction.class);
        when(hook.sendMessageEmbeds(any(MessageEmbed.class))).thenReturn(webhookMessageCreateAction);
        when(hook.editOriginalEmbeds(any(MessageEmbed.class))).thenReturn(webhookMessageEditAction);

        // Mock the behavior of setActionRow to return the same WebhookMessageEditAction
        when(webhookMessageCreateAction.setActionRow(any(), any(), any())).thenReturn(webhookMessageCreateAction);
        when(webhookMessageEditAction.setActionRow(any(), any(), any())).thenReturn(webhookMessageEditAction);

        DnDHelper.rollInitiativeForMembers(memberList, member, initiativeMap);
        DnDHelper.sendOrEditInitiativeMessage(guildId, hook, DnDHelper.getInitiativeEmbedBuilder());
        DnDHelper.incrementTurnTable(hook, guildId);

        // Verify that setActionRow is called once for initial setup with the correct buttons
        verifySetActionRows(webhookMessageCreateAction, webhookMessageEditAction);


        // Verify that queue is called once
        verify(webhookMessageCreateAction, times(1)).queue();
        verify(webhookMessageEditAction, times(1)).queue();

        verify(hook, times(1)).sendMessageEmbeds(any(MessageEmbed.class));
        verify(hook, times(1)).editOriginalEmbeds(any(MessageEmbed.class));
        assertEquals(1, DnDHelper.getInitiativeIndex().get());
    }

    @Test
    void testDecrementTurnTable() {
        {
            long guildId = 123456789L;

            WebhookMessageCreateAction webhookMessageCreateAction = mock(WebhookMessageCreateAction.class);
            WebhookMessageEditAction webhookMessageEditAction = mock(WebhookMessageEditAction.class);
            when(hook.sendMessageEmbeds(any(MessageEmbed.class))).thenReturn(webhookMessageCreateAction);
            when(hook.editOriginalEmbeds(any(MessageEmbed.class))).thenReturn(webhookMessageEditAction);

            // Mock the behavior of setActionRow to return the same WebhookMessageEditAction
            when(webhookMessageCreateAction.setActionRow(any(), any(), any())).thenReturn(webhookMessageCreateAction);
            when(webhookMessageEditAction.setActionRow(any(), any(), any())).thenReturn(webhookMessageEditAction);

            DnDHelper.rollInitiativeForMembers(memberList, member, initiativeMap);
            DnDHelper.sendOrEditInitiativeMessage(guildId, hook, DnDHelper.getInitiativeEmbedBuilder());
            DnDHelper.decrementTurnTable(hook, guildId);
            verifySetActionRows(webhookMessageCreateAction, webhookMessageEditAction);


            // Verify that queue is called once
            verify(webhookMessageCreateAction, times(1)).queue();
            verify(webhookMessageEditAction, times(1)).queue();

            verify(hook, times(1)).sendMessageEmbeds(any(MessageEmbed.class));
            verify(hook, times(1)).editOriginalEmbeds(any(MessageEmbed.class));
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

        DnDHelper.setCurrentEmbed(guildId, mock(MessageEmbed.class));

        DnDHelper.rollInitiativeForMembers(memberList, member, initiativeMap);
        DnDHelper.clearInitiative(guildId);

        assertNull(DnDHelper.getCurrentEmbed(guildId));
        assertEquals(0, DnDHelper.getInitiativeIndex().get());
        assertEquals(0, DnDHelper.getSortedEntries().size());
    }

    private static void verifySetActionRows(WebhookMessageCreateAction webhookMessageCreateAction, WebhookMessageEditAction webhookMessageEditAction) {
        // Verify that setActionRow is called once for initial setup with the correct buttons
        verify(webhookMessageCreateAction, times(1)).setActionRow(
                eq(DnDHelper.getInitButtons().prev()),
                eq(DnDHelper.getInitButtons().clear()),
                eq(DnDHelper.getInitButtons().next())
        );

        verify(webhookMessageEditAction, times(1)).setActionRow(
                eq(DnDHelper.getInitButtons().prev()),
                eq(DnDHelper.getInitButtons().clear()),
                eq(DnDHelper.getInitButtons().next())
        );
    }

}
