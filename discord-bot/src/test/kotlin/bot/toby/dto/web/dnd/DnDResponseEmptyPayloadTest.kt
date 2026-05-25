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

    // Includes Feature and Spell — they previously NPE'd here because
    // their list fields (Feature.prerequisites, Spell.desc/higherLevel/
    // components/subclasses) were declared non-nullable but Gson assigns
    // null when the JSON field is missing. Fixed in the same commit
    // that re-enables them in this matrix.
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
            "Feature" to { JsonParser.parseJsonToFeature("{}") },
            "Language" to { JsonParser.parseJsonToLanguage("{}") },
            "MagicSchool" to { JsonParser.parseJsonToMagicSchool("{}") },
            "Proficiency" to { JsonParser.parseJsonToProficiency("{}") },
            "Rule" to { JsonParser.parseJsonToRule("{}") },
            "Skill" to { JsonParser.parseJsonToSkill("{}") },
            "Spell" to { JsonParser.parseJSONToSpell("{}") },
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
