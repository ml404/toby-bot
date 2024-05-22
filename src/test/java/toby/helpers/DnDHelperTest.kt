package toby.helpers

import net.dv8tion.jda.api.entities.*
import net.dv8tion.jda.api.entities.emoji.Emoji
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent
import net.dv8tion.jda.api.interactions.InteractionHook
import net.dv8tion.jda.api.interactions.components.LayoutComponent
import net.dv8tion.jda.api.requests.RestAction
import net.dv8tion.jda.api.requests.restaction.AuditableRestAction
import net.dv8tion.jda.api.requests.restaction.MessageEditAction
import net.dv8tion.jda.api.requests.restaction.WebhookMessageCreateAction
import net.dv8tion.jda.api.requests.restaction.WebhookMessageEditAction
import net.dv8tion.jda.api.utils.messages.MessageRequest
import net.dv8tion.jda.internal.requests.restaction.AuditableRestActionImpl
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.anyVararg
import toby.helpers.DnDHelper.clearInitiative
import toby.helpers.DnDHelper.decrementTurnTable
import toby.helpers.DnDHelper.incrementTurnTable
import toby.helpers.DnDHelper.initButtons
import toby.helpers.DnDHelper.initiativeEmbedBuilder
import toby.helpers.DnDHelper.rollDice
import toby.helpers.DnDHelper.rollDiceWithModifier
import toby.helpers.DnDHelper.rollInitiativeForMembers
import toby.helpers.DnDHelper.sendOrEditInitiativeMessage
import toby.helpers.DnDHelper.sortedEntries
import toby.jpa.service.IUserService
import toby.jpa.service.impl.UserServiceImpl

internal class DnDHelperTest {
    @Mock
    lateinit var hook: InteractionHook

    @Mock
    lateinit var message: Message

    @Mock
    lateinit var event: ButtonInteractionEvent

    @Mock
    lateinit var member: Member

    @Mock
    lateinit var guild: Guild

    @Mock
    private lateinit var webhookMessageEditAction: WebhookMessageEditAction<Message>

    @Mock
    lateinit var webhookMessageCreateAction: WebhookMessageCreateAction<Message>

    @Mock
    lateinit var userService: IUserService

    @Mock
    private lateinit var messageEditAction: MessageEditAction
    private lateinit var memberList: List<Member>
    private lateinit var initiativeMap: MutableMap<String, Int>

    @BeforeEach
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        // Call rollInitiativeForMembers before testing other methods
        val player1 = Mockito.mock(Member::class.java)
        val player2 = Mockito.mock(Member::class.java)
        val player3 = Mockito.mock(Member::class.java)
        val user1 = Mockito.mock(User::class.java)
        val user2 = Mockito.mock(User::class.java)
        val user3 = Mockito.mock(User::class.java)
        userService = Mockito.mock(UserServiceImpl::class.java)
        memberList = listOf(player1, player2, player3)
        Mockito.`when`(player1.user).thenReturn(user1)
        Mockito.`when`(player2.user).thenReturn(user2)
        Mockito.`when`(player3.user).thenReturn(user3)
        guild = Mockito.mock(Guild::class.java)
        Mockito.`when`(player1.guild).thenReturn(guild)
        Mockito.`when`(player2.guild).thenReturn(guild)
        Mockito.`when`(player3.guild).thenReturn(guild)
        Mockito.`when`(guild.idLong).thenReturn(1L)
        Mockito.`when`(user1.isBot).thenReturn(false)
        Mockito.`when`(user2.isBot).thenReturn(false)
        Mockito.`when`(user3.isBot).thenReturn(false)
        Mockito.`when`(user1.effectiveName).thenReturn("name 1")
        Mockito.`when`(user2.effectiveName).thenReturn("name 2")
        Mockito.`when`(user3.effectiveName).thenReturn("name 3")
        val auditableRestAction = Mockito.mock(
            AuditableRestActionImpl::class.java
        ) as AuditableRestAction<Void>
        event = Mockito.mock(ButtonInteractionEvent::class.java)
        initiativeMap = HashMap()
        webhookMessageEditAction = Mockito.mock(WebhookMessageEditAction::class.java) as WebhookMessageEditAction<Message>
        webhookMessageCreateAction = Mockito.mock(WebhookMessageCreateAction::class.java) as WebhookMessageCreateAction<Message>
        Mockito.`when`(event.message).thenReturn(message)
        messageEditAction = Mockito.mock(MessageEditAction::class.java)
        Mockito.`when`(message.editMessageEmbeds(ArgumentMatchers.any(MessageEmbed::class.java)))
            .thenReturn(messageEditAction)
        Mockito.`when`(
            messageEditAction.setActionRow(
                ArgumentMatchers.any(),
                ArgumentMatchers.any(),
                ArgumentMatchers.any()
            )
        ).thenReturn(messageEditAction)
        Mockito.`when`(message.delete()).thenReturn(auditableRestAction)
        clearInitiative()
        Mockito.`when`(hook.deleteOriginal()).thenReturn(
            Mockito.mock(
                RestAction::class.java
            ) as RestAction<Void>?
        )
        Mockito.`when`(hook.setEphemeral(true)).thenReturn(hook)
        Mockito.`when`(hook.sendMessageFormat(ArgumentMatchers.anyString(), anyVararg()))
            .thenReturn(webhookMessageCreateAction)
    }

    @AfterEach
    fun tearDown() {
        Mockito.reset(hook)
        Mockito.reset(webhookMessageCreateAction)
        Mockito.reset(webhookMessageEditAction)
        Mockito.reset(message)
        Mockito.reset(event)
        Mockito.reset(messageEditAction)
        Mockito.reset(userService)
        Mockito.reset(guild)
        clearInitiative()
    }

    @Test
    fun testRollDiceWithModifier() {
        val result = rollDiceWithModifier(20, 1, 5)
        Assertions.assertTrue(result in 6..25, "Result should be between 6 and 25 (inclusive)")
    }

    @Test
    fun testRollDice() {
        val result = rollDice(20, 2)
        Assertions.assertTrue(result in 2..40, "Result should be between 2 and 40 (inclusive)")
    }

    @Test
    fun testIncrementTurnTable() {
        val webhookMessageCreateAction = Mockito.mock(
            WebhookMessageCreateAction::class.java
        ) as WebhookMessageCreateAction<Message>
        val webhookMessageEditAction = Mockito.mock(
            WebhookMessageEditAction::class.java
        ) as WebhookMessageEditAction<Message>
        Mockito.`when`(
            hook.sendMessageEmbeds(
                ArgumentMatchers.any(
                    MessageEmbed::class.java
                )
            )
        ).thenReturn(webhookMessageCreateAction)
        Mockito.`when`(
            hook.editOriginalComponents(
                ArgumentMatchers.any(
                    LayoutComponent::class.java
                )
            )
        ).thenReturn(webhookMessageEditAction)
        Mockito.`when`(
            hook.editOriginalEmbeds(
                ArgumentMatchers.any(
                    MessageEmbed::class.java
                )
            )
        ).thenReturn(webhookMessageEditAction)

        // Mock the behavior of setActionRow to return the same WebhookMessageEditAction
        Mockito.`when`<MessageRequest<*>>(
            webhookMessageCreateAction.setActionRow(
                ArgumentMatchers.any(),
                ArgumentMatchers.any(),
                ArgumentMatchers.any()
            )
        ).thenReturn(webhookMessageCreateAction)
        Mockito.`when`<MessageRequest<*>>(
            webhookMessageEditAction.setActionRow(
                ArgumentMatchers.any(),
                ArgumentMatchers.any(),
                ArgumentMatchers.any()
            )
        ).thenReturn(webhookMessageEditAction)

        rollInitiativeForMembers(memberList, member, initiativeMap, userService)
        sendOrEditInitiativeMessage(hook, initiativeEmbedBuilder, null, 0)
        incrementTurnTable(hook, event, 0)
        verifySetActionRows(webhookMessageCreateAction, messageEditAction)


        // Verify that queue is called once
        Mockito.verify(webhookMessageCreateAction, Mockito.times(1)).queue()

        Mockito.verify(hook, Mockito.times(1)).sendMessageEmbeds(
            ArgumentMatchers.any(
                MessageEmbed::class.java
            )
        )
        Mockito.verify(messageEditAction, Mockito.times(1)).queue()
        Assertions.assertEquals(1, DnDHelper.initiativeIndex.get())
    }

    @Test
    fun testDecrementTurnTable() {
        run {
            val webhookMessageCreateAction = Mockito.mock(
                WebhookMessageCreateAction::class.java
            ) as WebhookMessageCreateAction<Message>
            val webhookMessageEditAction = Mockito.mock(
                WebhookMessageEditAction::class.java
            ) as WebhookMessageEditAction<Message>
            Mockito.`when`(
                hook.sendMessageEmbeds(
                    ArgumentMatchers.any(
                        MessageEmbed::class.java
                    )
                )
            ).thenReturn(webhookMessageCreateAction)
            Mockito.`when`(
                hook.editOriginalComponents(
                    ArgumentMatchers.any(
                        LayoutComponent::class.java
                    )
                )
            ).thenReturn(webhookMessageEditAction)
            Mockito.`when`(
                hook.editOriginalEmbeds(
                    ArgumentMatchers.any(
                        MessageEmbed::class.java
                    )
                )
            ).thenReturn(webhookMessageEditAction)

            // Mock the behavior of setActionRow to return the same WebhookMessageEditAction
            Mockito.`when`<MessageRequest<*>>(
                webhookMessageCreateAction.setActionRow(
                    ArgumentMatchers.any(),
                    ArgumentMatchers.any(),
                    ArgumentMatchers.any()
                )
            ).thenReturn(webhookMessageCreateAction)
            Mockito.`when`<MessageRequest<*>>(
                webhookMessageEditAction.setActionRow(
                    ArgumentMatchers.any(),
                    ArgumentMatchers.any(),
                    ArgumentMatchers.any()
                )
            ).thenReturn(webhookMessageEditAction)

            rollInitiativeForMembers(memberList, member, initiativeMap, userService)
            sendOrEditInitiativeMessage(hook, initiativeEmbedBuilder, null, 0)
            decrementTurnTable(hook, event, 0)
            verifySetActionRows(webhookMessageCreateAction, messageEditAction)


            // Verify that queue is called once
            Mockito.verify(webhookMessageCreateAction, Mockito.times(1)).queue()

            Mockito.verify(hook, Mockito.times(1)).sendMessageEmbeds(
                ArgumentMatchers.any(
                    MessageEmbed::class.java
                )
            )
            Mockito.verify(messageEditAction, Mockito.times(1)).queue()
            Assertions.assertEquals(2, DnDHelper.initiativeIndex.get())
        }
    }

    @Test
    fun testGetInitButtons() {
        val buttons = initButtons

        Assertions.assertNotNull(buttons)
        Assertions.assertEquals(Emoji.fromUnicode("⬅️").name, buttons.prev.emoji?.name)
        Assertions.assertEquals(Emoji.fromUnicode("❌").name, buttons.clear.emoji?.name)
        Assertions.assertEquals(Emoji.fromUnicode("➡️").name, buttons.next.emoji?.name)
    }

    @Test
    fun testGetInitiativeEmbedBuilder() {
        val embedBuilder = initiativeEmbedBuilder

        Assertions.assertNotNull(embedBuilder)
        Assertions.assertEquals("Initiative Order", embedBuilder.build().title)
    }

    @Test
    fun testClearInitiative() {
        val guildId = 123456789L

        rollInitiativeForMembers(memberList, member, initiativeMap, userService)
        clearInitiative()

        Assertions.assertEquals(0, DnDHelper.initiativeIndex.get())
        Assertions.assertEquals(0, sortedEntries.size)
    }

    companion object {
        private fun verifySetActionRows(
            webhookMessageCreateAction: WebhookMessageCreateAction<*>,
            messageEditAction: MessageEditAction
        ) {
            // Verify that setActionRow is called once for initial setup with the correct buttons
            Mockito.verify(webhookMessageCreateAction, Mockito.times(1)).setActionRow(
                ArgumentMatchers.eq(initButtons.prev),
                ArgumentMatchers.eq(initButtons.clear),
                ArgumentMatchers.eq(initButtons.next)
            )

            Mockito.verify(messageEditAction, Mockito.times(1)).setActionRow(
                ArgumentMatchers.eq(initButtons.prev),
                ArgumentMatchers.eq(initButtons.clear),
                ArgumentMatchers.eq(initButtons.next)
            )
        }
    }
}
