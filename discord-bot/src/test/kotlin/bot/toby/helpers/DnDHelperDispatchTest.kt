package bot.toby.helpers

import bot.toby.command.commands.dnd.DnDSearchCommand
import bot.toby.dto.web.dnd.AbilityScore
import bot.toby.dto.web.dnd.Condition
import bot.toby.dto.web.dnd.DamageTypeInfo
import bot.toby.dto.web.dnd.DnDClass
import bot.toby.dto.web.dnd.DnDExpansionFixtures
import bot.toby.dto.web.dnd.Equipment
import bot.toby.dto.web.dnd.EquipmentCategory
import bot.toby.dto.web.dnd.Feature
import bot.toby.dto.web.dnd.Language
import bot.toby.dto.web.dnd.MagicSchool
import bot.toby.dto.web.dnd.Monster
import bot.toby.dto.web.dnd.Proficiency
import bot.toby.dto.web.dnd.Race
import bot.toby.dto.web.dnd.Rule
import bot.toby.dto.web.dnd.Skill
import bot.toby.dto.web.dnd.Spell
import bot.toby.dto.web.dnd.Subclass
import bot.toby.dto.web.dnd.Subrace
import bot.toby.dto.web.dnd.Trait
import bot.toby.dto.web.dnd.WeaponProperty
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DnDHelperDispatchTest {

    private val helper = DnDHelper()

    @Test
    fun `doInitialLookup returns AbilityScore for ability-score typeName`() = runTest {
        val response = lookupWithFixture(DnDSearchCommand.ABILITY_SCORE_NAME, "ability-scores", "str", DnDExpansionFixtures.ABILITY_SCORE_STR)
        assertTrue(response is AbilityScore)
        assertEquals("STR", (response as AbilityScore).name)
    }

    @Test
    fun `doInitialLookup returns DnDClass for class typeName`() = runTest {
        val response = lookupWithFixture(DnDSearchCommand.CLASS_NAME, "classes", "fighter", DnDExpansionFixtures.CLASS_FIGHTER)
        assertTrue(response is DnDClass)
        assertEquals("Fighter", (response as DnDClass).name)
    }

    @Test
    fun `doInitialLookup returns DamageTypeInfo for damage-type typeName`() = runTest {
        val response = lookupWithFixture(DnDSearchCommand.DAMAGE_TYPE_NAME, "damage-types", "fire", DnDExpansionFixtures.DAMAGE_TYPE_FIRE)
        assertTrue(response is DamageTypeInfo)
    }

    @Test
    fun `doInitialLookup returns EquipmentCategory for equipment-category typeName`() = runTest {
        val response = lookupWithFixture(DnDSearchCommand.EQUIPMENT_CATEGORY_NAME, "equipment-categories", "simple-weapons", DnDExpansionFixtures.EQUIPMENT_CATEGORY_SIMPLE_WEAPONS)
        assertTrue(response is EquipmentCategory)
    }

    @Test
    fun `doInitialLookup returns Equipment for equipment typeName`() = runTest {
        val response = lookupWithFixture(DnDSearchCommand.EQUIPMENT_NAME, "equipment", "longsword", DnDExpansionFixtures.EQUIPMENT_LONGSWORD)
        assertTrue(response is Equipment)
        assertEquals("Longsword", (response as Equipment).name)
    }

    @Test
    fun `doInitialLookup returns Language for language typeName`() = runTest {
        val response = lookupWithFixture(DnDSearchCommand.LANGUAGE_NAME, "languages", "dwarvish", DnDExpansionFixtures.LANGUAGE_DWARVISH)
        assertTrue(response is Language)
    }

    @Test
    fun `doInitialLookup returns MagicSchool for magic-school typeName`() = runTest {
        val response = lookupWithFixture(DnDSearchCommand.MAGIC_SCHOOL_NAME, "magic-schools", "evocation", DnDExpansionFixtures.MAGIC_SCHOOL_EVOCATION)
        assertTrue(response is MagicSchool)
    }

    @Test
    fun `doInitialLookup returns Monster for monster typeName`() = runTest {
        val response = lookupWithFixture(DnDSearchCommand.MONSTER_NAME, "monsters", "goblin", DnDExpansionFixtures.MONSTER_GOBLIN)
        assertTrue(response is Monster)
        assertEquals("Goblin", (response as Monster).name)
    }

    @Test
    fun `doInitialLookup returns Proficiency for proficiency typeName`() = runTest {
        val response = lookupWithFixture(DnDSearchCommand.PROFICIENCY_NAME, "proficiencies", "light-armor", DnDExpansionFixtures.PROFICIENCY_LIGHT_ARMOR)
        assertTrue(response is Proficiency)
    }

    @Test
    fun `doInitialLookup returns Race for race typeName`() = runTest {
        val response = lookupWithFixture(DnDSearchCommand.RACE_NAME, "races", "elf", DnDExpansionFixtures.RACE_ELF)
        assertTrue(response is Race)
        assertEquals("Elf", (response as Race).name)
    }

    @Test
    fun `doInitialLookup returns Skill for skill typeName`() = runTest {
        val response = lookupWithFixture(DnDSearchCommand.SKILL_NAME, "skills", "athletics", DnDExpansionFixtures.SKILL_ATHLETICS)
        assertTrue(response is Skill)
    }

    @Test
    fun `doInitialLookup returns Subclass for subclass typeName`() = runTest {
        val response = lookupWithFixture(DnDSearchCommand.SUBCLASS_NAME, "subclasses", "champion", DnDExpansionFixtures.SUBCLASS_CHAMPION)
        assertTrue(response is Subclass)
    }

    @Test
    fun `doInitialLookup returns Subrace for subrace typeName`() = runTest {
        val response = lookupWithFixture(DnDSearchCommand.SUBRACE_NAME, "subraces", "high-elf", DnDExpansionFixtures.SUBRACE_HIGH_ELF)
        assertTrue(response is Subrace)
    }

    @Test
    fun `doInitialLookup returns Trait for trait typeName`() = runTest {
        val response = lookupWithFixture(DnDSearchCommand.TRAIT_NAME, "traits", "darkvision", DnDExpansionFixtures.TRAIT_DARKVISION)
        assertTrue(response is Trait)
    }

    @Test
    fun `doInitialLookup returns WeaponProperty for weapon-property typeName`() = runTest {
        val response = lookupWithFixture(DnDSearchCommand.WEAPON_PROPERTY_NAME, "weapon-properties", "finesse", DnDExpansionFixtures.WEAPON_PROPERTY_FINESSE)
        assertTrue(response is WeaponProperty)
    }

    @Test
    fun `doInitialLookup returns null for unknown typeName`() = runTest {
        val httpHelper = mockk<HttpHelper>(relaxed = true)
        coEvery { httpHelper.fetchFromGet(any()) } returns "{}"
        val response = helper.doInitialLookup("not-a-real-type", "monsters", "anything", httpHelper)
        assertNull(response)
    }

    @Test
    fun `doInitialLookup builds slugged URL with dashes for multi-word queries`() = runTest {
        val httpHelper = mockk<HttpHelper>(relaxed = true)
        val urlSlot = slot<String>()
        coEvery { httpHelper.fetchFromGet(capture(urlSlot)) } returns DnDExpansionFixtures.MONSTER_GOBLIN

        helper.doInitialLookup(DnDSearchCommand.MONSTER_NAME, "monsters", "goblin boss", httpHelper)

        assertTrue(urlSlot.captured.endsWith("/monsters/goblin-boss"))
    }

    @Test
    fun `queryNonMatchRetry url-encodes spaces in the query`() = runTest {
        val httpHelper = mockk<HttpHelper>(relaxed = true)
        val urlSlot = slot<String>()
        coEvery { httpHelper.fetchFromGet(capture(urlSlot)) } returns
                DnDExpansionFixtures.QUERY_RESULT_MONSTERS_GOB

        helper.queryNonMatchRetry("monsters", "goblin boss", httpHelper)

        assertTrue(urlSlot.captured.contains("?name=goblin%20boss"))
    }

    @Test
    fun `original 4 types still dispatch correctly after expansion`() = runTest {
        val spell = lookupWithFixture(
            DnDSearchCommand.SPELL_NAME, "spells", "fireball",
            """{"index":"fireball","name":"Fireball","desc":["x"],"higher_level":[],"range":"60","components":[],"material":null,"ritual":false,"duration":"i","concentration":false,"casting_time":"1 action","level":3,"damage":null,"dc":null,"area_of_effect":null,"school":null,"classes":null,"subclasses":[],"url":"/api/spells/fireball"}"""
        )
        assertTrue(spell is Spell)

        val cond = lookupWithFixture(
            DnDSearchCommand.CONDITION_NAME, "conditions", "blinded",
            """{"index":"blinded","name":"Blinded","desc":["x"],"url":"/api/conditions/blinded"}"""
        )
        assertTrue(cond is Condition)

        val rule = lookupWithFixture(
            DnDSearchCommand.RULE_NAME, "rule-sections", "cover",
            """{"name":"Cover","index":"cover","desc":"x","url":"/api/rule-sections/cover"}"""
        )
        assertTrue(rule is Rule)

        val feature = lookupWithFixture(
            DnDSearchCommand.FEATURE_NAME, "features", "x",
            """{"index":"x","class":null,"name":"X","level":1,"prerequisites":[],"desc":["d"],"url":"/api/features/x"}"""
        )
        assertTrue(feature is Feature)
    }

    private suspend fun lookupWithFixture(
        typeName: String,
        typeValue: String,
        slug: String,
        responseJson: String
    ): Any? {
        val httpHelper = mockk<HttpHelper>(relaxed = true)
        coEvery { httpHelper.fetchFromGet(any()) } returns responseJson
        val result = helper.doInitialLookup(typeName, typeValue, slug, httpHelper)
        assertNotNull(result, "expected a non-null parsed response for typeName=$typeName")
        return result
    }
}
