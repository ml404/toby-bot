package bot.toby.autocomplete.autocompletes

import bot.coroutines.MainCoroutineExtension
import bot.toby.dto.web.dnd.DnDExpansionFixtures
import bot.toby.helpers.HttpHelper
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.Runs
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent
import net.dv8tion.jda.api.interactions.commands.Command.Choice
import net.dv8tion.jda.api.interactions.commands.OptionMapping
import net.dv8tion.jda.api.requests.restaction.interactions.AutoCompleteCallbackAction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

@OptIn(ExperimentalCoroutinesApi::class)
@ExtendWith(MainCoroutineExtension::class)
class DnDAutoCompleteTest {

    @Test
    fun `fetchChoices returns up to 25 choices for a name-filtered query`() = runTest {
        val httpHelper = mockk<HttpHelper>(relaxed = true)
        coEvery { httpHelper.fetchFromGet(match { it.contains("?name=gob") }) } returns
                DnDExpansionFixtures.QUERY_RESULT_MONSTERS_GOB

        val handler = DnDAutoComplete(httpHelper, StandardTestDispatcher(testScheduler))
        val choices = handler.fetchChoices("monsters", "gob")

        assertEquals(2, choices.size)
        assertEquals("Goblin", choices[0].name)
        assertEquals("goblin", choices[0].asString)
        assertEquals("Goblin Boss", choices[1].name)
        assertEquals("goblin-boss", choices[1].asString)
    }

    @Test
    fun `fetchChoices for blank input hits the unfiltered endpoint`() = runTest {
        val httpHelper = mockk<HttpHelper>(relaxed = true)
        coEvery { httpHelper.fetchFromGet("https://www.dnd5eapi.co/api/monsters") } returns
                DnDExpansionFixtures.QUERY_RESULT_MONSTERS_GOB

        val handler = DnDAutoComplete(httpHelper, StandardTestDispatcher(testScheduler))
        val choices = handler.fetchChoices("monsters", "")

        assertEquals(2, choices.size)
        coVerify { httpHelper.fetchFromGet("https://www.dnd5eapi.co/api/monsters") }
    }

    @Test
    fun `fetchChoices URL-encodes spaces in the query`() = runTest {
        val urlSlot = slot<String>()
        val httpHelper = mockk<HttpHelper>(relaxed = true)
        coEvery { httpHelper.fetchFromGet(capture(urlSlot)) } returns
                DnDExpansionFixtures.QUERY_RESULT_MONSTERS_GOB

        val handler = DnDAutoComplete(httpHelper, StandardTestDispatcher(testScheduler))
        handler.fetchChoices("monsters", "goblin boss")

        assertTrue(urlSlot.captured.contains("?name=goblin%20boss"))
    }

    @Test
    fun `fetchChoices returns empty list when API returns blank`() = runTest {
        val httpHelper = mockk<HttpHelper>(relaxed = true)
        coEvery { httpHelper.fetchFromGet(any()) } returns ""

        val handler = DnDAutoComplete(httpHelper, StandardTestDispatcher(testScheduler))
        val choices = handler.fetchChoices("monsters", "anything")

        assertTrue(choices.isEmpty())
    }

    @Test
    fun `fetchChoices returns empty list when HttpHelper throws`() = runTest {
        val httpHelper = mockk<HttpHelper>(relaxed = true)
        coEvery { httpHelper.fetchFromGet(any()) } throws RuntimeException("boom")

        val handler = DnDAutoComplete(httpHelper, StandardTestDispatcher(testScheduler))
        val choices = handler.fetchChoices("monsters", "anything")

        assertTrue(choices.isEmpty())
    }

    @Test
    fun `fetchChoices truncates choice names longer than the Discord limit`() = runTest {
        val longName = "A".repeat(150)
        val payload = """{"count":1,"results":[{"index":"x","name":"$longName","url":"/api/x"}]}"""
        val httpHelper = mockk<HttpHelper>(relaxed = true)
        coEvery { httpHelper.fetchFromGet(any()) } returns payload

        val handler = DnDAutoComplete(httpHelper, StandardTestDispatcher(testScheduler))
        val choices = handler.fetchChoices("monsters", "x")

        assertEquals(1, choices.size)
        assertTrue(choices[0].name.length <= 100)
        assertTrue(choices[0].name.endsWith("…"))
    }

    @Test
    fun `fetchChoices caps at 25 results even if the API returns more`() = runTest {
        val rows = (1..50).joinToString(",") { i ->
            """{"index":"i-$i","name":"Item $i","url":"/api/i-$i"}"""
        }
        val payload = """{"count":50,"results":[$rows]}"""
        val httpHelper = mockk<HttpHelper>(relaxed = true)
        coEvery { httpHelper.fetchFromGet(any()) } returns payload

        val handler = DnDAutoComplete(httpHelper, StandardTestDispatcher(testScheduler))
        val choices = handler.fetchChoices("monsters", "")

        assertEquals(25, choices.size)
    }

    @Test
    fun `handle ignores events focused on the type option`() = runTest {
        val httpHelper = mockk<HttpHelper>(relaxed = true)
        val event = mockk<CommandAutoCompleteInteractionEvent>(relaxed = true)
        every { event.focusedOption.name } returns "type"

        val handler = DnDAutoComplete(httpHelper, StandardTestDispatcher(testScheduler))
        handler.handle(event)
        advanceUntilIdle()

        coVerify(exactly = 0) { httpHelper.fetchFromGet(any()) }
    }

    @Test
    fun `handle replies with empty choices when no type has been chosen yet`() = runTest {
        val httpHelper = mockk<HttpHelper>(relaxed = true)
        val event = mockk<CommandAutoCompleteInteractionEvent>(relaxed = true)
        val callback = mockk<AutoCompleteCallbackAction>(relaxed = true)
        every { event.focusedOption.name } returns "query"
        every { event.focusedOption.value } returns "abc"
        every { event.getOption("type") } returns null
        every { event.replyChoices(any<List<Choice>>()) } returns callback
        every { callback.queue() } just Runs

        val handler = DnDAutoComplete(httpHelper, StandardTestDispatcher(testScheduler))
        handler.handle(event)
        advanceUntilIdle()

        coVerify(exactly = 0) { httpHelper.fetchFromGet(any()) }
        verify { event.replyChoices(emptyList<Choice>()) }
    }

    @Test
    fun `handle dispatches to fetchChoices and replies for valid event`() = runTest {
        val httpHelper = mockk<HttpHelper>(relaxed = true)
        coEvery { httpHelper.fetchFromGet(any()) } returns DnDExpansionFixtures.QUERY_RESULT_MONSTERS_GOB

        val event = mockk<CommandAutoCompleteInteractionEvent>(relaxed = true)
        val typeOption = mockk<OptionMapping>()
        every { typeOption.asString } returns "monsters"
        every { event.getOption("type") } returns typeOption
        every { event.focusedOption.name } returns "query"
        every { event.focusedOption.value } returns "gob"
        val callback = mockk<AutoCompleteCallbackAction>(relaxed = true)
        val choicesSlot = slot<List<Choice>>()
        every { event.replyChoices(capture(choicesSlot)) } returns callback
        every { callback.queue() } just Runs

        val handler = DnDAutoComplete(httpHelper, StandardTestDispatcher(testScheduler))
        handler.handle(event)
        advanceUntilIdle()

        verify { event.replyChoices(any<List<Choice>>()) }
        coVerify { httpHelper.fetchFromGet(match { it.contains("monsters") && it.contains("?name=gob") }) }
        assertEquals(2, choicesSlot.captured.size)
        assertEquals("Goblin", choicesSlot.captured.first().name)
    }

    @Test
    fun `autocomplete handler is registered with the dnd command name`() {
        val handler = DnDAutoComplete(mockk(relaxed = true), StandardTestDispatcher())
        assertEquals("dnd", handler.name)
    }
}
