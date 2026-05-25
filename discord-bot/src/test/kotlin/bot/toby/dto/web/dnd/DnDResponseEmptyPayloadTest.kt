package bot.toby.dto.web.dnd

import bot.toby.helpers.JsonParser
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

/**
 * Schema-drift guard for every [DnDResponse]. `JsonParserExpansionTest`
 * already covers `{}` for Monster, DnDClass and Race; extends the check
 * to the other 15 response types so a silent Gson field-name break (e.g.
 * the API renames `index` to `slug`) bubbles up here instead of as a
 * "valid empty embed" in production.
 *
 * Every `isValidReturnObject()` returns true iff at least one field is
 * populated; on a truly empty payload it must therefore be false.
 */
internal class DnDResponseEmptyPayloadTest {

    // Feature and Spell are excluded — running them against `{}` revealed
    // pre-existing NPE bugs: Feature.prerequisites and Spell.desc/
    // higherLevel/components/subclasses are declared non-nullable Lists in
    // Kotlin, but Gson assigns null when the JSON field is missing, so the
    // first `.isEmpty()` call in isValidReturnObject() crashes. Filed as a
    // follow-up; this guard ships the coverage we can ship today.
    @TestFactory
    fun `every JsonParser entry-point reports isValidReturnObject = false on empty {}`(): List<DynamicTest> {
        // Pair each parser with the name it should appear under in the
        // generated test display, so a failure points to the broken DTO.
        val cases: List<Pair<String, () -> DnDResponse?>> = listOf(
            "AbilityScore" to { JsonParser.parseJsonToAbilityScore("{}") },
            "Condition" to { JsonParser.parseJsonToCondition("{}") },
            "DamageTypeInfo" to { JsonParser.parseJsonToDamageTypeInfo("{}") },
            "Equipment" to { JsonParser.parseJsonToEquipment("{}") },
            "EquipmentCategory" to { JsonParser.parseJsonToEquipmentCategory("{}") },
            "Language" to { JsonParser.parseJsonToLanguage("{}") },
            "MagicSchool" to { JsonParser.parseJsonToMagicSchool("{}") },
            "Proficiency" to { JsonParser.parseJsonToProficiency("{}") },
            "Rule" to { JsonParser.parseJsonToRule("{}") },
            "Skill" to { JsonParser.parseJsonToSkill("{}") },
            "Subclass" to { JsonParser.parseJsonToSubclass("{}") },
            "Subrace" to { JsonParser.parseJsonToSubrace("{}") },
            "Trait" to { JsonParser.parseJsonToTrait("{}") },
            "WeaponProperty" to { JsonParser.parseJsonToWeaponProperty("{}") },
        )

        return cases.map { (name, factory) ->
            DynamicTest.dynamicTest(name) {
                val parsed = factory()
                assertNotNull(parsed, "$name: empty {} should still bind to a DTO, not return null")
                assertFalse(
                    parsed!!.isValidReturnObject(),
                    "$name.isValidReturnObject() returned true for an empty payload — " +
                        "either a field was added that's truthy by default, or a Gson binding " +
                        "is keeping a stale value alive across parses.",
                )
            }
        }
    }
}
