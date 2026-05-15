package bot.toby.menu.menus.dnd

import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent

class DndEventProcessor {
    fun determineTypeValue(typeName: String): String {
        return when (typeName) {
            DndMenu.SPELL_NAME -> "spells"
            DndMenu.CONDITION_NAME -> "conditions"
            DndMenu.RULE_NAME -> "rule-sections"
            DndMenu.FEATURE_NAME -> "features"
            DndMenu.ABILITY_SCORE_NAME -> "ability-scores"
            DndMenu.CLASS_NAME -> "classes"
            DndMenu.DAMAGE_TYPE_NAME -> "damage-types"
            DndMenu.EQUIPMENT_CATEGORY_NAME -> "equipment-categories"
            DndMenu.EQUIPMENT_NAME -> "equipment"
            DndMenu.LANGUAGE_NAME -> "languages"
            DndMenu.MAGIC_SCHOOL_NAME -> "magic-schools"
            DndMenu.MONSTER_NAME -> "monsters"
            DndMenu.PROFICIENCY_NAME -> "proficiencies"
            DndMenu.RACE_NAME -> "races"
            DndMenu.SKILL_NAME -> "skills"
            DndMenu.SUBCLASS_NAME -> "subclasses"
            DndMenu.SUBRACE_NAME -> "subraces"
            DndMenu.TRAIT_NAME -> "traits"
            DndMenu.WEAPON_PROPERTY_NAME -> "weapon-properties"
            else -> ""
        }
    }

    fun toTypeString(event: StringSelectInteractionEvent): String {
        return event.componentId.split(":").getOrNull(1) ?: ""
    }
}
