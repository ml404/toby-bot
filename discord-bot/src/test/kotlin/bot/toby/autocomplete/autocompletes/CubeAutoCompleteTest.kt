package bot.toby.autocomplete.autocompletes

import bot.toby.command.commands.mtg.CubeCommand
import database.dto.user.CubeListDto
import database.service.user.CubeListService
import io.mockk.Runs
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent
import net.dv8tion.jda.api.interactions.commands.Command.Choice
import net.dv8tion.jda.api.requests.restaction.interactions.AutoCompleteCallbackAction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant

class CubeAutoCompleteTest {

    private val discordId = 100L
    private val cubeListService: CubeListService = mockk(relaxed = true)
    private val event: CommandAutoCompleteInteractionEvent = mockk(relaxed = true)
    private val callback: AutoCompleteCallbackAction = mockk(relaxed = true)
    private lateinit var autoComplete: CubeAutoComplete

    private fun cube(name: String) =
        CubeListDto(discordId, name, "Bolt", Instant.EPOCH, Instant.EPOCH)

    @BeforeEach
    fun setUp() {
        autoComplete = CubeAutoComplete(cubeListService)
        every { event.user.idLong } returns discordId
        every { event.replyChoices(any<Collection<Choice>>()) } returns callback
        every { callback.queue() } just Runs
    }

    @AfterEach
    fun tearDown() = clearAllMocks()

    @Test
    fun `suggests the user's saved cubes matching the typed prefix`() {
        every { event.focusedOption.name } returns CubeCommand.OPT_SAVED
        every { event.focusedOption.value } returns "vin"
        every { cubeListService.listForUser(discordId) } returns
            listOf(cube("Vintage Cube"), cube("Pauper Cube"), cube("My Vintage 360"))

        val choices = slot<Collection<Choice>>()
        every { event.replyChoices(capture(choices)) } returns callback

        autoComplete.handle(event)

        verify(exactly = 1) { cubeListService.listForUser(discordId) }
        assertEquals(setOf("Vintage Cube", "My Vintage 360"), choices.captured.map { it.name }.toSet())
    }

    @Test
    fun `matches case-insensitively and returns the name as both label and value`() {
        every { event.focusedOption.name } returns CubeCommand.OPT_SAVED
        every { event.focusedOption.value } returns "PAUPER"
        every { cubeListService.listForUser(discordId) } returns listOf(cube("Pauper Cube"))

        val choices = slot<Collection<Choice>>()
        every { event.replyChoices(capture(choices)) } returns callback

        autoComplete.handle(event)

        val choice = choices.captured.single()
        assertEquals("Pauper Cube", choice.name)
        assertEquals("Pauper Cube", choice.asString)
    }

    @Test
    fun `an empty input lists all of the user's cubes, capped at 25`() {
        every { event.focusedOption.name } returns CubeCommand.OPT_SAVED
        every { event.focusedOption.value } returns ""
        every { cubeListService.listForUser(discordId) } returns (1..30).map { cube("Cube $it") }

        val choices = slot<Collection<Choice>>()
        every { event.replyChoices(capture(choices)) } returns callback

        autoComplete.handle(event)

        assertTrue(choices.captured.size <= 25)
    }

    @Test
    fun `does nothing for a different focused option`() {
        every { event.focusedOption.name } returns "query"

        autoComplete.handle(event)

        verify(exactly = 0) { cubeListService.listForUser(any()) }
        verify(exactly = 0) { event.replyChoices(any<Collection<Choice>>()) }
    }

    @Test
    fun `is registered against the cube command`() {
        assertEquals("cube", autoComplete.name)
    }
}
