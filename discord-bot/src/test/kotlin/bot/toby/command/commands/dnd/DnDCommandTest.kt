package bot.toby.command.commands.dnd

import bot.coroutines.MainCoroutineExtension
import bot.toby.command.CommandTest
import bot.toby.command.CommandTest.Companion.event
import bot.toby.command.CommandTest.Companion.interactionHook
import bot.toby.command.CommandTest.Companion.webhookMessageCreateAction
import bot.toby.command.DefaultCommandContext
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

@OptIn(ExperimentalCoroutinesApi::class)
@ExtendWith(MainCoroutineExtension::class)
class DnDCommandTest : CommandTest {

    private lateinit var command: DnDSearchCommand
    private val dispatcher: CoroutineDispatcher = StandardTestDispatcher()
    private val deleteDelay = 0

    @BeforeEach
    fun setUp() {
        setUpCommonMocks()
        every { interactionHook.sendMessageEmbeds(any<MessageEmbed>(), *anyVararg()) } returns webhookMessageCreateAction
        every { webhookMessageCreateAction.setActionRow(any<StringSelectMenu>()).queue(any()) } just Runs

        // Mock the creation of DnDCommandQueryHandler
        mockkConstructor(DnDCommandQueryHandler::class)
        every { anyConstructed<DnDCommandQueryHandler>().processQuery(any(), any(), any()) } just Runs

        // Initialize the DnDCommand with mocked HttpHelper
        command = DnDSearchCommand(dispatcher, mockk(relaxed = true), mockk(relaxed = true))
    }

    @AfterEach
    fun tearDown() {
        tearDownCommonMocks()
        unmockkAll()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `should create and call DnDCommandQueryHandler for successful lookup`() = runTest {
        command.handle(DefaultCommandContext(event), mockk(), deleteDelay)

        advanceUntilIdle()

        // Verify that DnDCommandQueryHandler was created and its processQuery method was called
        verify { anyConstructed<DnDCommandQueryHandler>().processQuery(any(), any(), any()) }
    }

}
