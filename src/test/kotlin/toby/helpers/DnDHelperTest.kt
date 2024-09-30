package toby.helpers

import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
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
import toby.command.commands.dnd.DnDCommand.Companion.CONDITION_NAME
import toby.dto.web.dnd.Condition
import toby.dto.web.dnd.Feature
import toby.dto.web.dnd.Rule
import toby.dto.web.dnd.Spell
import toby.jpa.dto.UserDto
import toby.jpa.service.IUserService
import toby.jpa.service.impl.UserServiceImpl
import toby.menu.menus.dnd.DndMenu.Companion.FEATURE_NAME
import toby.menu.menus.dnd.DndMenu.Companion.RULE_NAME
import toby.menu.menus.dnd.DndMenu.Companion.SPELL_NAME

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
    lateinit var userDtoHelper: UserDtoHelper
    lateinit var dndHelper: DnDHelper

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
        userDtoHelper = mockk()
        dndHelper = DnDHelper(userDtoHelper)

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

        dndHelper.clearInitiative()

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
        every { userDtoHelper.calculateUserDto(1L, 1L) } returns userDto1
        every { userDtoHelper.calculateUserDto(1L, 2L) } returns userDto2
        every { userDtoHelper.calculateUserDto(1L, 3L) } returns userDto3
        every { hook.sendMessageEmbeds(any(), *anyVararg()) } returns webhookMessageCreateAction
        every { webhookMessageCreateAction.queue(any()) } just Runs
        every { webhookMessageCreateAction.setActionRow(any(), any(), any()).queue() } just Runs
        every { webhookMessageEditAction.setActionRow(any(), any(), any()).queue() } just Runs
        every { webhookMessageEditAction.queue() } just Runs
    }

    @AfterEach
    fun tearDown() {
        clearAllMocks()
        dndHelper.clearInitiative()
    }

    @Test
    fun testRollDiceWithModifier() {
        val result = dndHelper.rollDiceWithModifier(20, 1, 5)
        Assertions.assertTrue(result in 6..25, "Result should be between 6 and 25 (inclusive)")
    }

    @Test
    fun testRollDice() {
        val result = dndHelper.rollDice(20, 2)
        Assertions.assertTrue(result in 2..40, "Result should be between 2 and 40 (inclusive)")
    }

    @Test
    fun testIncrementTurnTable() {
        dndHelper.rollInitiativeForMembers(memberList, member, initiativeMap)
        dndHelper.sendOrEditInitiativeMessage(hook, dndHelper.initiativeEmbedBuilder, null, 0)
        dndHelper.incrementTurnTable(hook, event, 0)
        verifySetActionRows(webhookMessageCreateAction, messageEditAction)

        verify(exactly = 1) { webhookMessageCreateAction.queue(any()) }
        verify(exactly = 1) { hook.sendMessageEmbeds(any<MessageEmbed>()) }
        verify(exactly = 1) { messageEditAction.queue() }
        Assertions.assertEquals(1, dndHelper.initiativeIndex.get())
    }

    @Test
    fun testDecrementTurnTable() {
        dndHelper.rollInitiativeForMembers(memberList, member, initiativeMap)
        dndHelper.sendOrEditInitiativeMessage(hook, dndHelper.initiativeEmbedBuilder, null, 0)
        dndHelper.decrementTurnTable(hook, event, 0)
        verifySetActionRows(webhookMessageCreateAction, messageEditAction)

        verify(exactly = 1) { webhookMessageCreateAction.queue(any()) }
        verify(exactly = 1) { hook.sendMessageEmbeds(any<MessageEmbed>()) }
        verify(exactly = 1) { messageEditAction.queue() }
        Assertions.assertEquals(2, dndHelper.initiativeIndex.get())
    }

    @Test
    fun testGetInitButtons() {
        val buttons = dndHelper.initButtons

        Assertions.assertNotNull(buttons)
        Assertions.assertEquals(Emoji.fromUnicode("⬅️").name, buttons.prev.label)
        Assertions.assertEquals(Emoji.fromUnicode("❌").name, buttons.clear.label)
        Assertions.assertEquals(Emoji.fromUnicode("➡️").name, buttons.next.label)
    }

    @Test
    fun testGetInitiativeEmbedBuilder() {
        val embedBuilder = dndHelper.initiativeEmbedBuilder

        Assertions.assertNotNull(embedBuilder)
        Assertions.assertEquals("Initiative Order", embedBuilder.build().title)
    }

    @Test
    fun testClearInitiative() {
        dndHelper.rollInitiativeForMembers(memberList, member, initiativeMap)
        dndHelper.clearInitiative()

        Assertions.assertEquals(0, dndHelper.initiativeIndex.get())
        Assertions.assertEquals(0, dndHelper.sortedEntries.size)
    }

    @Test
    fun testRollInitiativeForMembersWithEmptyList() {
        dndHelper.clearInitiative()
        dndHelper.rollInitiativeForMembers(emptyList(), member, initiativeMap)
        Assertions.assertTrue(
            dndHelper.sortedEntries.isEmpty(),
            "Sorted entries should be empty for an empty member list"
        )
    }

    @Test
    fun testRollInitiativeForString() {
        val names = listOf("Alice", "Bob", "Charlie")
        dndHelper.rollInitiativeForString(names, initiativeMap)
        Assertions.assertEquals(3, initiativeMap.size, "There should be an entry for each name")
        names.forEach { name ->
            Assertions.assertTrue(initiativeMap.containsKey(name), "Initiative map should contain entry for $name")
        }
    }

    @Test
    fun testSendOrEditInitiativeMessageForNewMessage() {
        dndHelper.clearInitiative()
        dndHelper.rollInitiativeForMembers(memberList, member, initiativeMap)
        dndHelper.sendOrEditInitiativeMessage(hook, dndHelper.initiativeEmbedBuilder, null, 0)

        verify(exactly = 1) { hook.sendMessageEmbeds(any<MessageEmbed>()) }
        verify(exactly = 1) { webhookMessageCreateAction.setActionRow(any(), any(), any()).queue() }
    }

    @Test
    fun testSendOrEditInitiativeMessageForExistingMessage() {
        dndHelper.clearInitiative()
        dndHelper.rollInitiativeForMembers(memberList, member, initiativeMap)
        dndHelper.sendOrEditInitiativeMessage(hook, dndHelper.initiativeEmbedBuilder, event, 0)

        verify(exactly = 1) { message.editMessageEmbeds(any<MessageEmbed>()) }
        verify(exactly = 1) { messageEditAction.setActionRow(any(), any(), any()) }
        verify(exactly = 1) { messageEditAction.queue() }
        verify(exactly = 1) { hook.setEphemeral(true) }
        verify(exactly = 1) { hook.sendMessage(any<String>()) }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun testDoInitialLookupWithSpell() = runTest {
        val mockResponse = """{"name": "Fireball"}"""
        val httpHelper = mockk<HttpHelper>()
        coEvery { httpHelper.fetchFromGet(any()) } returns mockResponse

        val response = dndHelper.doInitialLookup(SPELL_NAME, "spell", "fireball", httpHelper)
        Assertions.assertTrue(response is Spell, "Response should be of type Spell")
        Assertions.assertEquals("Fireball", (response as Spell).name, "Spell name should be 'Fireball'")
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun testDoInitialLookupWithCondition() = runTest {

        val mockResponse = """{"name": "Blinded"}"""
        val httpHelper = mockk<HttpHelper>()
        coEvery { httpHelper.fetchFromGet(any()) } returns mockResponse

        val response = dndHelper.doInitialLookup(CONDITION_NAME, "condition", "blinded", httpHelper)
        Assertions.assertTrue(response is Condition, "Response should be of type Condition")
        Assertions.assertEquals("Blinded", (response as Condition).name, "Condition name should be 'Blinded'")
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun testDoInitialLookupWithRule() = runTest {
        val mockResponse = """{"name": "Cover"}"""
        val httpHelper = mockk<HttpHelper>()
        coEvery { httpHelper.fetchFromGet(any()) } returns mockResponse

        val response = dndHelper.doInitialLookup(RULE_NAME, "rule", "cover", httpHelper)
        Assertions.assertTrue(response is Rule, "Response should be of type Rule")
        Assertions.assertEquals("Cover", (response as Rule).name, "Rule name should be 'Cover'")
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun testDoInitialLookupWithFeature() = runTest {
        val mockResponse = """{"name": "Darkvision"}"""
        val httpHelper = mockk<HttpHelper>()
        coEvery { httpHelper.fetchFromGet(any()) } returns mockResponse

        val response = dndHelper.doInitialLookup(FEATURE_NAME, "feature", "darkvision", httpHelper)
        Assertions.assertTrue(response is Feature, "Response should be of type Feature")
        Assertions.assertEquals("Darkvision", (response as Feature).name, "Feature name should be 'Darkvision'")
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun testQueryNonMatchRetryWithSpell() = runTest {
        val mockResponse = """{"results": [{"name": "Fireball"}]}"""
        val httpHelper = mockk<HttpHelper>()
        coEvery { httpHelper.fetchFromGet(any()) } returns mockResponse

        val result = dndHelper.queryNonMatchRetry("spell", "fireball", httpHelper)
        Assertions.assertNotNull(result, "Query result should not be null")
        Assertions.assertEquals(
            "Fireball",
            result?.results?.firstOrNull()?.name,
            "Query result name should be 'Fireball'"
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun testQueryNonMatchRetryWithRule() = runTest {
        val mockResponse = """{"results": [{"name": "Cover"}]}"""
        val httpHelper = mockk<HttpHelper>()
        coEvery { httpHelper.fetchFromGet(any()) } returns mockResponse

        val result = dndHelper.queryNonMatchRetry("rule", "cover", httpHelper)
        Assertions.assertNotNull(result, "Query result should not be null")
        Assertions.assertEquals("Cover", result?.results?.firstOrNull()?.name, "Query result name should be 'Cover'")
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun testQueryNonMatchRetryWithFeature() = runTest {
        val mockResponse = """{"results": [{"name": "Darkvision"}]}"""
        val httpHelper = mockk<HttpHelper>()
        coEvery { httpHelper.fetchFromGet(any()) } returns mockResponse

        val result = dndHelper.queryNonMatchRetry("feature", "darkvision", httpHelper)
        Assertions.assertNotNull(result, "Query result should not be null")
        Assertions.assertEquals(
            "Darkvision",
            result?.results?.firstOrNull()?.name,
            "Query result name should be 'Darkvision'"
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun testQueryNonMatchRetryWithCondition() = runTest {
        val mockResponse = """{"results": [{"name": "Blinded"}]}"""
        val httpHelper = mockk<HttpHelper>()
        coEvery { httpHelper.fetchFromGet(any()) } returns mockResponse

        val result = dndHelper.queryNonMatchRetry("condition", "blinded", httpHelper)
        Assertions.assertNotNull(result, "Query result should not be null")
        Assertions.assertEquals(
            "Blinded",
            result?.results?.firstOrNull()?.name,
            "Query result name should be 'Blinded'"
        )
    }


    private fun verifySetActionRows(
        webhookMessageCreateAction: WebhookMessageCreateAction<*>,
        messageEditAction: MessageEditAction
    ) {
        val initButtons = dndHelper.initButtons
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
