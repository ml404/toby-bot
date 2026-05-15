package bot.toby.command.commands.dnd

import bot.toby.helpers.DnDHelper
import bot.toby.helpers.HttpHelper
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DnDSearchCommandOptionsTest {

    private val command = DnDSearchCommand(
        Dispatchers.Unconfined,
        mockk<HttpHelper>(relaxed = true),
        mockk<DnDHelper>(relaxed = true)
    )

    @Test
    fun `command exposes both type and query options`() {
        val options = command.optionData
        assertEquals(2, options.size)
        assertEquals("type", options[0].name)
        assertEquals("query", options[1].name)
    }

    @Test
    fun `query option has autocomplete enabled`() {
        val queryOption = command.optionData.first { it.name == "query" }
        assertTrue(queryOption.isAutoComplete)
    }

    @Test
    fun `type option exposes all 19 D&D 5e SRD categories`() {
        val typeOption = command.optionData.first { it.name == "type" }
        val choices = typeOption.choices.map { it.asString }

        // Original 4
        assertTrue(choices.contains("spells"))
        assertTrue(choices.contains("conditions"))
        assertTrue(choices.contains("rule-sections"))
        assertTrue(choices.contains("features"))

        // The 14 newly-added categories
        assertTrue(choices.contains("ability-scores"))
        assertTrue(choices.contains("classes"))
        assertTrue(choices.contains("damage-types"))
        assertTrue(choices.contains("equipment-categories"))
        assertTrue(choices.contains("equipment"))
        assertTrue(choices.contains("languages"))
        assertTrue(choices.contains("magic-schools"))
        assertTrue(choices.contains("monsters"))
        assertTrue(choices.contains("proficiencies"))
        assertTrue(choices.contains("races"))
        assertTrue(choices.contains("skills"))
        assertTrue(choices.contains("subclasses"))
        assertTrue(choices.contains("subraces"))
        assertTrue(choices.contains("traits"))
        assertTrue(choices.contains("weapon-properties"))

        assertEquals(19, choices.size)
    }

    @Test
    fun `command name and description are set`() {
        assertEquals("dnd", command.name)
        assertNotNull(command.description)
        assertTrue(command.description.isNotBlank())
    }

    @Test
    fun `every type choice has a non-blank human label`() {
        val typeOption = command.optionData.first { it.name == "type" }
        typeOption.choices.forEach { choice ->
            assertTrue(choice.name.isNotBlank(), "choice for ${choice.asString} has a blank label")
        }
    }
}
