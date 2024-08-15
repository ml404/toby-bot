package toby.menu.menus

import coroutines.MainCoroutineExtension
import io.mockk.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.requests.restaction.AuditableRestAction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import toby.command.CommandTest
import toby.command.CommandTest.Companion.interactionHook
import toby.command.CommandTest.Companion.webhookMessageCreateAction
import toby.command.commands.fetch.TestHttpHelperHelper.ACTION_SURGE_RESPONSE
import toby.command.commands.fetch.TestHttpHelperHelper.ACTION_SURGE_INITIAL_URL
import toby.command.commands.fetch.TestHttpHelperHelper.COVER_INITIAL_RESPONSE
import toby.command.commands.fetch.TestHttpHelperHelper.COVER_INITIAL_URL
import toby.command.commands.fetch.TestHttpHelperHelper.createMockHttpClient
import toby.command.commands.fetch.TestHttpHelperHelper.FIREBALL_INITIAL_RESPONSE
import toby.command.commands.fetch.TestHttpHelperHelper.FIREBALL_URL
import toby.command.commands.fetch.TestHttpHelperHelper.GRAPPLED_INITIAL_RESPONSE
import toby.command.commands.fetch.TestHttpHelperHelper.GRAPPLED_URL
import toby.menu.MenuContext
import toby.menu.MenuTest
import toby.menu.MenuTest.Companion.menuEvent

@OptIn(ExperimentalCoroutinesApi::class)
@ExtendWith(MainCoroutineExtension::class)
internal class DndMenuTest : MenuTest {

    private lateinit var dndMenu: DndMenu

    @BeforeEach
    fun setup() {
        setUpMenuMocks()
        every { interactionHook.sendMessageEmbeds(any<MessageEmbed>(), *anyVararg()) } returns webhookMessageCreateAction
    }

    @AfterEach
    fun tearDown() {
        tearDownMenuMocks()
        unmockkAll()
    }

    @Test
    fun test_dndMenuWithSpell() = runTest {
        // Arrange
        val dispatcher = StandardTestDispatcher() as CoroutineDispatcher
        val httpHelper = createMockHttpClient(FIREBALL_URL, FIREBALL_INITIAL_RESPONSE, dispatcher = dispatcher)
        dndMenu = DndMenu(dispatcher, httpHelper)
        val ctx = mockAndCreateMenuContext("dnd:spell", "fireball")

        // Act
        dndMenu.handle(ctx, 0)

        // Ensure all asynchronous code completes
        advanceUntilIdle()

        // Assert
        verify { menuEvent.deferReply() }
        verify { interactionHook.sendMessageEmbeds(any<MessageEmbed>(), *anyVararg()) }
    }

    @Test
    fun test_dndMenuWithCondition() = runTest {
        // Arrange
        val dispatcher = StandardTestDispatcher() as CoroutineDispatcher

        val httpHelper = createMockHttpClient(GRAPPLED_URL, GRAPPLED_INITIAL_RESPONSE, dispatcher = dispatcher)
        dndMenu = DndMenu(dispatcher, httpHelper)
        val ctx = mockAndCreateMenuContext("dnd:condition", "grappled")

        // Act
        dndMenu.handle(ctx, 0)

        // Ensure all asynchronous code completes
        advanceUntilIdle()

        // Assert
        verify { menuEvent.deferReply() }
        verify { interactionHook.sendMessageEmbeds(any<MessageEmbed>(), *anyVararg()) }
    }

    @Test
    fun test_dndMenuWithRule() = runTest {
        // Arrange
        val dispatcher = StandardTestDispatcher() as CoroutineDispatcher
        val httpHelper = createMockHttpClient(COVER_INITIAL_URL, COVER_INITIAL_RESPONSE, dispatcher = dispatcher)
        dndMenu = DndMenu(dispatcher, httpHelper)
        val ctx = mockAndCreateMenuContext("dnd:rule", "cover")

        // Act
        dndMenu.handle(ctx, 0)

        // Ensure all asynchronous code completes
        advanceUntilIdle()

        // Assert
        verify { menuEvent.deferReply() }
        verify { interactionHook.sendMessageEmbeds(any<MessageEmbed>(), *anyVararg()) }
    }

    @Test
    fun test_dndMenuWithFeature() = runTest {
        // Arrange
        val dispatcher = StandardTestDispatcher() as CoroutineDispatcher
        val httpHelper = createMockHttpClient(ACTION_SURGE_INITIAL_URL, ACTION_SURGE_RESPONSE, dispatcher = dispatcher)
        dndMenu = DndMenu(dispatcher, httpHelper)
        val ctx = mockAndCreateMenuContext("dnd:feature", "action-surge-1-use")

        // Act
        dndMenu.handle(ctx, 0)

        // Ensure all asynchronous code completes
        advanceUntilIdle()

        // Assert
        verify { menuEvent.deferReply() }
        verify { interactionHook.sendMessageEmbeds(any<MessageEmbed>(), *anyVararg()) }
    }

    companion object {
        private fun mockAndCreateMenuContext(eventName: String, selectedValue: String): MenuContext {
            val auditableRestAction = mockk<AuditableRestAction<Void>>()
            every { menuEvent.componentId } returns eventName
            every { menuEvent.values } returns listOf(selectedValue)
            every { menuEvent.message } returns CommandTest.message
            every { CommandTest.message.delete() } returns auditableRestAction
            every { auditableRestAction.queue() } just Runs
            every { webhookMessageCreateAction.queue() } just Runs
            return MenuContext(menuEvent)
        }
    }
}
