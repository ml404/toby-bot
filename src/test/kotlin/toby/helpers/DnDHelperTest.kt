package toby.helpers

import io.mockk.*
import net.dv8tion.jda.api.entities.*
import net.dv8tion.jda.api.entities.emoji.Emoji
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent
import net.dv8tion.jda.api.interactions.InteractionHook
import net.dv8tion.jda.api.requests.RestAction
import net.dv8tion.jda.api.requests.restaction.AuditableRestAction
import net.dv8tion.jda.api.requests.restaction.MessageEditAction
import net.dv8tion.jda.api.requests.restaction.WebhookMessageCreateAction
import net.dv8tion.jda.api.requests.restaction.WebhookMessageEditAction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
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
import toby.jpa.dto.UserDto
import toby.jpa.service.IUserService
import toby.jpa.service.impl.UserServiceImpl

internal class DnDHelperTest {
    lateinit var hook: InteractionHook
    lateinit var message: Message
    lateinit var event: ButtonInteractionEvent
    lateinit var member: Member
    lateinit var guild: Guild
    lateinit var webhookMessageEditAction: WebhookMessageEditAction<Message>
    lateinit var webhookMessageCreateAction: WebhookMessageCreateAction<Message>
    lateinit var userService: IUserService
    lateinit var messageEditAction: MessageEditAction
    lateinit var memberList: List<Member>
    lateinit var initiativeMap: MutableMap<String, Int>

    @BeforeEach
    fun setUp() {
        hook = mockk()
        message = mockk()
        event = mockk(relaxed = true)
        member = mockk()
        guild = mockk()
        webhookMessageEditAction = mockk()
        webhookMessageCreateAction = mockk()
        userService = mockk<UserServiceImpl>()
        messageEditAction = mockk()
        memberList = mockk()
        initiativeMap = mutableMapOf()

        val player1 = mockk<Member>()
        val player2 = mockk<Member>()
        val player3 = mockk<Member>()
        val user1 = mockk<User>()
        val user2 = mockk<User>()
        val user3 = mockk<User>()
        val userDto1 = mockk<UserDto>()
        val userDto2 = mockk<UserDto>()
        val userDto3 = mockk<UserDto>()

        memberList = listOf(player1, player2, player3)

        every { player1.user } returns user1
        every { player2.user } returns user2
        every { player3.user } returns user3

        every { player1.idLong } returns 1L
        every { player2.idLong } returns 1L
        every { player3.idLong } returns 1L

        every { player1.guild } returns guild
        every { player2.guild } returns guild
        every { player3.guild } returns guild

        every { player1.isOwner } returns false
        every { player2.isOwner } returns false
        every { player3.isOwner } returns false

        every { guild.idLong } returns 1L

        every { user1.isBot } returns false
        every { user2.isBot } returns false
        every { user3.isBot } returns false

        every { user1.effectiveName } returns "name 1"
        every { user2.effectiveName } returns "name 2"
        every { user3.effectiveName } returns "name 3"

        val auditableRestAction = mockk<AuditableRestAction<Void>>()

        every { event.message } returns message

        every { message.editMessageEmbeds(any<MessageEmbed>()) } returns messageEditAction
        every { messageEditAction.setActionRow(any(), any(), any()) } returns messageEditAction
        every { messageEditAction.queue() } just Runs
        every { message.delete() } returns auditableRestAction

        clearInitiative()

        every { hook.deleteOriginal() } returns mockk<RestAction<Void>>()
        every { hook.setEphemeral(true) } returns hook
        every { hook.sendMessage(any<String>()) } returns webhookMessageCreateAction

        every { userDto1.discordId } returns 1L
        every { userDto2.discordId } returns 2L
        every { userDto3.discordId } returns 3L
        every { userDto1.guildId } returns 1L
        every { userDto2.guildId } returns 1L
        every { userDto3.guildId } returns 1L
        every { userDto1.initiativeModifier } returns 0
        every { userDto2.initiativeModifier } returns 1
        every { userDto3.initiativeModifier } returns 2

        every { userService.listGuildUsers(any()) } returns listOf(userDto1, userDto2, userDto3)
        every { userService.getUserById(1L, 1L) } returns userDto1
        every { userService.getUserById(2L, 1L) } returns userDto2
        every { userService.getUserById(3L, 1L) } returns userDto3
        every { hook.sendMessageEmbeds(any(), *anyVararg()) } returns webhookMessageCreateAction
        every { webhookMessageCreateAction.queue(any()) } just Runs
        every { webhookMessageCreateAction.setActionRow(any(), any(), any()).queue() } just Runs
        every { webhookMessageEditAction.setActionRow(any(), any(), any()).queue() } just Runs
        every { webhookMessageEditAction.queue() } just Runs
    }

    @AfterEach
    fun tearDown() {
        clearAllMocks()
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
        rollInitiativeForMembers(memberList, member, initiativeMap, userService)
        sendOrEditInitiativeMessage(hook, initiativeEmbedBuilder, null, 0)
        incrementTurnTable(hook, event, 0)
        verifySetActionRows(webhookMessageCreateAction, messageEditAction)

        verify(exactly = 1) { webhookMessageCreateAction.queue(any()) }
        verify(exactly = 1) { hook.sendMessageEmbeds(any<MessageEmbed>()) }
        verify(exactly = 1) { messageEditAction.queue() }
        Assertions.assertEquals(1, DnDHelper.initiativeIndex.get())
    }

    @Test
    fun testDecrementTurnTable() {
        rollInitiativeForMembers(memberList, member, initiativeMap, userService)
        sendOrEditInitiativeMessage(hook, initiativeEmbedBuilder, null, 0)
        decrementTurnTable(hook, event, 0)
        verifySetActionRows(webhookMessageCreateAction, messageEditAction)

        verify(exactly = 1) { webhookMessageCreateAction.queue(any()) }
        verify(exactly = 1) { hook.sendMessageEmbeds(any<MessageEmbed>()) }
        verify(exactly = 1) { messageEditAction.queue() }
        Assertions.assertEquals(2, DnDHelper.initiativeIndex.get())
    }

    @Test
    fun testGetInitButtons() {
        val buttons = initButtons

        Assertions.assertNotNull(buttons)
        Assertions.assertEquals(Emoji.fromUnicode("⬅️").name, buttons.prev.label)
        Assertions.assertEquals(Emoji.fromUnicode("❌").name, buttons.clear.label)
        Assertions.assertEquals(Emoji.fromUnicode("➡️").name, buttons.next.label)
    }

    @Test
    fun testGetInitiativeEmbedBuilder() {
        val embedBuilder = initiativeEmbedBuilder

        Assertions.assertNotNull(embedBuilder)
        Assertions.assertEquals("Initiative Order", embedBuilder.build().title)
    }

    @Test
    fun testClearInitiative() {
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
            verify(exactly = 1) {
                webhookMessageCreateAction.setActionRow(
                    eq(initButtons.prev),
                    eq(initButtons.clear),
                    eq(initButtons.next)
                )
            }

            verify(exactly = 1) {
                messageEditAction.setActionRow(
                    eq(initButtons.prev),
                    eq(initButtons.clear),
                    eq(initButtons.next)
                )
            }
        }
    }
}
