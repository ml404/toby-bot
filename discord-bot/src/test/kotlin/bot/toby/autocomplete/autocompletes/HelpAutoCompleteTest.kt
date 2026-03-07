package bot.toby.autocomplete.autocompletes

import core.command.Command
import core.managers.CommandManager
import io.mockk.*
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent
import net.dv8tion.jda.api.interactions.commands.Command.Choice
import net.dv8tion.jda.api.requests.restaction.interactions.AutoCompleteCallbackAction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class HelpAutoCompleteTest {

    private val commandManager: CommandManager = mockk(relaxed = true)
    private val event: CommandAutoCompleteInteractionEvent = mockk(relaxed = true)
    private val autoCompleteCallbackAction: AutoCompleteCallbackAction = mockk(relaxed = true)
    private lateinit var helpAutoComplete: HelpAutoComplete

    private fun mockCommand(name: String): Command {
        return mockk { every { this@mockk.name } returns name }
    }

    @BeforeEach
    fun setUp() {
        helpAutoComplete = HelpAutoComplete(commandManager)
        every { event.replyChoices(any<Collection<Choice>>()) } returns autoCompleteCallbackAction
        every { autoCompleteCallbackAction.queue() } just Runs
    }

    @AfterEach
    fun tearDown() {
        clearAllMocks()
    }

    @Test
    fun `handle replies with matching commands when focused option is command`() {
        every { event.focusedOption.name } returns "command"
        every { event.focusedOption.value } returns "play"
        every { commandManager.commands } returns listOf(
            mockCommand("play"),
            mockCommand("pause"),
            mockCommand("playlist")
        )

        helpAutoComplete.handle(event)

        verify(exactly = 1) { event.replyChoices(any<Collection<Choice>>()) }
        verify(exactly = 1) { autoCompleteCallbackAction.queue() }
    }

    @Test
    fun `handle replies with empty list when no commands match input`() {
        every { event.focusedOption.name } returns "command"
        every { event.focusedOption.value } returns "xyz"
        every { commandManager.commands } returns listOf(
            mockCommand("play"),
            mockCommand("pause")
        )

        val choicesSlot = slot<Collection<Choice>>()
        every { event.replyChoices(capture(choicesSlot)) } returns autoCompleteCallbackAction

        helpAutoComplete.handle(event)

        assert(choicesSlot.captured.isEmpty())
    }

    @Test
    fun `handle returns at most 25 choices`() {
        every { event.focusedOption.name } returns "command"
        every { event.focusedOption.value } returns ""
        val manyCommands = (1..30).map { mockCommand("command$it") }
        every { commandManager.commands } returns manyCommands

        val choicesSlot = slot<Collection<Choice>>()
        every { event.replyChoices(capture(choicesSlot)) } returns autoCompleteCallbackAction

        helpAutoComplete.handle(event)

        assert(choicesSlot.captured.size <= 25)
    }

    @Test
    fun `handle does not reply when focused option is not command`() {
        every { event.focusedOption.name } returns "other"

        helpAutoComplete.handle(event)

        verify(exactly = 0) { event.replyChoices(any<Collection<Choice>>()) }
    }

    @Test
    fun `handle matches commands case insensitively`() {
        every { event.focusedOption.name } returns "command"
        every { event.focusedOption.value } returns "PLAY"
        every { commandManager.commands } returns listOf(
            mockCommand("play"),
            mockCommand("pause")
        )

        val choicesSlot = slot<Collection<Choice>>()
        every { event.replyChoices(capture(choicesSlot)) } returns autoCompleteCallbackAction

        helpAutoComplete.handle(event)

        assert(choicesSlot.captured.any { it.name == "play" })
    }
}
