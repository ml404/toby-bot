package toby.command.commands.fetch

import coroutines.MainCoroutineExtension
import io.ktor.http.*
import io.mockk.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.interactions.components.selections.StringSelectMenu
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import toby.command.CommandTest
import toby.command.CommandTest.Companion.event
import toby.command.CommandTest.Companion.interactionHook
import toby.command.CommandTest.Companion.webhookMessageCreateAction
import toby.command.commands.dnd.DnDCommand
import toby.command.commands.fetch.TestHttpHelperHelper.BIN_INITIAL_URL
import toby.command.commands.fetch.TestHttpHelperHelper.BIN_QUERY_URL
import toby.command.commands.fetch.TestHttpHelperHelper.BLIND_INITIAL_URL
import toby.command.commands.fetch.TestHttpHelperHelper.BLIND_QUERY_URL
import toby.command.commands.fetch.TestHttpHelperHelper.BLIND_QUERY_RESPONSE
import toby.command.commands.fetch.TestHttpHelperHelper.EMPTY_QUERY_RESPONSE
import toby.command.commands.fetch.TestHttpHelperHelper.ERROR_NOT_FOUND_RESPONSE
import toby.command.commands.fetch.TestHttpHelperHelper.createMockHttpClient
import toby.command.commands.fetch.TestHttpHelperHelper.FIREBALL_INITIAL_RESPONSE
import toby.command.commands.fetch.TestHttpHelperHelper.FIREBALL_INITIAL_URL
import toby.helpers.HttpHelper

@OptIn(ExperimentalCoroutinesApi::class)
@ExtendWith(MainCoroutineExtension::class)
class DnDCommandTest : CommandTest {

    private lateinit var command: DnDCommand
    private lateinit var httpHelper: HttpHelper
    private val deleteDelay = 0

    @BeforeEach
    fun setUp() {
        setUpCommonMocks()
        every {
            interactionHook.sendMessageEmbeds(
                any<MessageEmbed>(),
                *anyVararg()
            )
        } returns webhookMessageCreateAction
        every { webhookMessageCreateAction.setActionRow(any<StringSelectMenu>()).queue(any()) } just Runs

    }

    @AfterEach
    fun tearDown() {
        tearDownCommonMocks()
        unmockkAll()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `should handle successful lookup and reply with embed`() = runTest {
        val dispatcher = StandardTestDispatcher() as CoroutineDispatcher
        httpHelper = createMockHttpClient(
            FIREBALL_INITIAL_URL,
            FIREBALL_INITIAL_RESPONSE,
            dispatcher = dispatcher
        )
        command = DnDCommand(dispatcher, httpHelper)
        val embedSlot = slot<MessageEmbed>()
        command.handleWithHttpObjects(
            event,
            "spell",
            "spells",
            "fireball",
            deleteDelay
        )

        // Ensure all asynchronous code completes
        advanceUntilIdle()

        // Verify interactions and responses
        coVerify {
            event.hook.sendMessageEmbeds(capture(embedSlot))
            webhookMessageCreateAction.queue()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `should handle no initial results but successful followup scenario`() = runTest {
        val dispatcher = StandardTestDispatcher() as CoroutineDispatcher
        httpHelper = createMockHttpClient(
            BLIND_INITIAL_URL,
            ERROR_NOT_FOUND_RESPONSE,
            BLIND_QUERY_URL,
            BLIND_QUERY_RESPONSE,
            initialResponseType = HttpStatusCode.NotFound,
            dispatcher = dispatcher
        )
        command = DnDCommand(dispatcher, httpHelper)

        command.handleWithHttpObjects(
            event,
            "condition",
            "conditions",
            "blind",
            deleteDelay
        )

        // Ensure all asynchronous code completes
        advanceUntilIdle()

        // Verify interactions and responses
        coVerify {
            event.hook.sendMessage(any<String>())
            webhookMessageCreateAction.setActionRow(any<StringSelectMenu>()).queue()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `should handle no results scenario`() = runTest {

        val dispatcher = StandardTestDispatcher() as CoroutineDispatcher
        httpHelper = createMockHttpClient(
            BIN_INITIAL_URL,
            ERROR_NOT_FOUND_RESPONSE,
            BIN_QUERY_URL,
            EMPTY_QUERY_RESPONSE,
            initialResponseType = HttpStatusCode.NotFound,
            queryResponseType = HttpStatusCode.NotFound,
            dispatcher = dispatcher
        )
        command = DnDCommand(dispatcher, httpHelper)
        command.handleWithHttpObjects(
            event,
            "condition",
            "conditions",
            "bin",
            deleteDelay
        )

        // Ensure all asynchronous code completes
        advanceUntilIdle() // Advances the time until there are no more tasks left to process


        // Verify interactions and responses
        coVerify {
            event.hook.sendMessage(any<String>())
            webhookMessageCreateAction.queue(any())
        }
    }
}

