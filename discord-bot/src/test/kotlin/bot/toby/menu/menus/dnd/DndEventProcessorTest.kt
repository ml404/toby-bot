package bot.toby.menu.menus.dnd

import io.mockk.every
import io.mockk.mockk
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DndEventProcessorTest {

    private val processor = DndEventProcessor()

    @Test
    fun `determineTypeValue returns correct mapping for the original 4 types`() {
        assertEquals("spells", processor.determineTypeValue("spell"))
        assertEquals("conditions", processor.determineTypeValue("condition"))
        assertEquals("rule-sections", processor.determineTypeValue("rule"))
        assertEquals("features", processor.determineTypeValue("feature"))
    }

    @Test
    fun `determineTypeValue returns correct mapping for all 14 newly-added types`() {
        assertEquals("ability-scores", processor.determineTypeValue("ability-score"))
        assertEquals("classes", processor.determineTypeValue("class"))
        assertEquals("damage-types", processor.determineTypeValue("damage-type"))
        assertEquals("equipment-categories", processor.determineTypeValue("equipment-category"))
        assertEquals("equipment", processor.determineTypeValue("equipment"))
        assertEquals("languages", processor.determineTypeValue("language"))
        assertEquals("magic-schools", processor.determineTypeValue("magic-school"))
        assertEquals("monsters", processor.determineTypeValue("monster"))
        assertEquals("proficiencies", processor.determineTypeValue("proficiency"))
        assertEquals("races", processor.determineTypeValue("race"))
        assertEquals("skills", processor.determineTypeValue("skill"))
        assertEquals("subclasses", processor.determineTypeValue("subclass"))
        assertEquals("subraces", processor.determineTypeValue("subrace"))
        assertEquals("traits", processor.determineTypeValue("trait"))
        assertEquals("weapon-properties", processor.determineTypeValue("weapon-property"))
    }

    @Test
    fun `determineTypeValue returns empty string for unknown types`() {
        assertEquals("", processor.determineTypeValue("invalid"))
        assertEquals("", processor.determineTypeValue(""))
    }

    @Test
    fun `toTypeString extracts type from componentId`() {
        val event = mockk<StringSelectInteractionEvent> {
            every { componentId } returns "dnd:monster"
        }
        assertEquals("monster", processor.toTypeString(event))
    }

    @Test
    fun `toTypeString returns empty string for invalid componentId`() {
        val event = mockk<StringSelectInteractionEvent> {
            every { componentId } returns "dnd"
        }
        assertEquals("", processor.toTypeString(event))
    }
}
