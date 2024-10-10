package bot.toby.menu.menus.dnd

import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent

class DndEventProcessor {
    fun determineTypeValue(typeName: String): String {
        return when (typeName) {
            DndMenu.SPELL_NAME -> "spells"
            DndMenu.CONDITION_NAME -> "conditions"
            DndMenu.RULE_NAME -> "rule-sections"
            DndMenu.FEATURE_NAME -> "features"
            else -> ""
        }
    }

    fun toTypeString(event: StringSelectInteractionEvent): String {
        return event.componentId.split(":").getOrNull(1) ?: ""
    }
}