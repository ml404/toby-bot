package bot.toby.menu.menus.dnd

import io.mockk.every
import io.mockk.mockk
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DndEventProcessorTest {

    private val processor = DndEventProcessor()

    @Test
    fun `determineTypeValue should return correct type values`() {
        assertEquals("spells", processor.determineTypeValue("spell"))
        assertEquals("conditions", processor.determineTypeValue("condition"))
        assertEquals("rule-sections", processor.determineTypeValue("rule"))
        assertEquals("features", processor.determineTypeValue("feature"))
        assertEquals("", processor.determineTypeValue("invalid"))
    }

    @Test
    fun `toTypeString should return correct type from event componentId`() {
        val event = mockk<StringSelectInteractionEvent> {
            every { componentId } returns "dnd:spell"
        }

        assertEquals("spell", processor.toTypeString(event))
    }

    @Test
    fun `toTypeString should return empty string for invalid componentId`() {
        val event = mockk<StringSelectInteractionEvent> {
            every { componentId } returns "dnd"
        }

        assertEquals("", processor.toTypeString(event))
    }
}
