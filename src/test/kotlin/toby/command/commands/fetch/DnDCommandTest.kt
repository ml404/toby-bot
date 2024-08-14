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
import toby.command.CommandTest.Companion.webhookMessageCreateAction
import toby.command.commands.dnd.DnDCommand
import toby.helpers.DnDHelper
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
        httpHelper = HttpHelper()
        every {
            event.hook.sendMessageEmbeds(
                any<MessageEmbed>(),
                *anyVararg()
            )
        } returns webhookMessageCreateAction
    }

    @AfterEach
    fun tearDown() {
        tearDownCommonMocks()
        unmockkAll()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `should handle successful lookup and reply with embed`() = runTest {
        command = DnDCommand(StandardTestDispatcher() as CoroutineDispatcher)

        val embedSlot = slot<MessageEmbed>()
        command.handleWithHttpObjects(
            event,
            "spell",
            "spells",
            "fireball",
            httpHelper,
            deleteDelay
        )

       // Ensure all asynchronous code completes
        advanceUntilIdle()

        // Verify interactions and responses
        coVerify {
            DnDHelper.doInitialLookup("spell", "spells", "fireball", httpHelper)
            DnDHelper.queryNonMatchRetry("spells", "fireball", httpHelper)
            event.hook.sendMessageEmbeds(capture(embedSlot))
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `should handle no initial results but successful followup scenario`() = runTest {
        // Mocking JSON responses for doInitialLookup and queryNonMatchRetry
        command = DnDCommand(StandardTestDispatcher() as CoroutineDispatcher)

        command.handleWithHttpObjects(
            event,
            "condition",
            "conditions",
            "blind",
            httpHelper,
            deleteDelay
        )

        // Ensure all asynchronous code completes
        advanceUntilIdle()

        // Verify interactions and responses
        coVerify {
            DnDHelper.doInitialLookup("condition", "conditions", "blind", httpHelper)
            DnDHelper.queryNonMatchRetry("conditions", "blind", httpHelper)
            event.hook.sendMessage(any<String>()).setActionRow(any<StringSelectMenu>()).queue()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `should handle no results scenario`() = runTest {
        // Mocking JSON responses for doInitialLookup and queryNonMatchRetry
        command = DnDCommand(StandardTestDispatcher() as CoroutineDispatcher)

        command.handleWithHttpObjects(
            event,
            "condition",
            "conditions",
            "bin",
            httpHelper,
            deleteDelay
        )

        // Ensure all asynchronous code completes
        advanceUntilIdle()

        // Verify interactions and responses
        coVerify {
            DnDHelper.doInitialLookup("condition", "conditions", "bin", httpHelper)
            DnDHelper.queryNonMatchRetry("conditions", "bin", httpHelper)
            event.hook.sendMessage(any<String>()).queue(any())
        }
    }
}

