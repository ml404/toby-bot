package toby.command.commands.fetch

import coroutines.MainCoroutineExtension
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
        command = DnDCommand(dispatcher)
        httpHelper = HttpHelper(dispatcher)

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
        command = DnDCommand(dispatcher)
        httpHelper = HttpHelper(dispatcher)

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
        command = DnDCommand(dispatcher)
        httpHelper = HttpHelper(dispatcher)

        command.handleWithHttpObjects(
            event,
            "condition",
            "conditions",
            "bin",
            deleteDelay
        )

        // Ensure all asynchronous code completes
        advanceUntilIdle()

        // Verify interactions and responses
        coVerify {
            event.hook.sendMessage(any<String>())
            webhookMessageCreateAction.queue(any())
        }
    }
}

