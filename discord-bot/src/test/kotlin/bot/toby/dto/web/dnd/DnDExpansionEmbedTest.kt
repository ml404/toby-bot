package bot.toby.dto.web.dnd

import bot.toby.helpers.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Verifies the rich Discord embeds produced by each new DnDResponse DTO.
 * These tests exercise toEmbed() end-to-end rather than mocking the EmbedBuilder.
 */
class DnDExpansionEmbedTest {

    @Test
    fun `AbilityScore embed includes full name title, description and skills`() {
        val a = JsonParser.parseJsonToAbilityScore(DnDExpansionFixtures.ABILITY_SCORE_STR)!!
        val embed = a.toEmbed()
        assertEquals("Strength", embed.title)
        assertNotNull(embed.description)
        assertTrue(embed.description!!.contains("Strength measures bodily power"))
        val skillsField = embed.fields.first { it.name == "Skills" }
        assertEquals("Athletics", skillsField.value)
        assertEquals(0x42f5a7, embed.colorRaw)
    }

    @Test
    fun `DamageTypeInfo embed renders name and joined description`() {
        val d = JsonParser.parseJsonToDamageTypeInfo(DnDExpansionFixtures.DAMAGE_TYPE_FIRE)!!
        val embed = d.toEmbed()
        assertEquals("Fire", embed.title)
        assertTrue(embed.description!!.contains("Red dragons breathe fire"))
    }

    @Test
    fun `MagicSchool embed renders name and string description`() {
        val m = JsonParser.parseJsonToMagicSchool(DnDExpansionFixtures.MAGIC_SCHOOL_EVOCATION)!!
        val embed = m.toEmbed()
        assertEquals("Evocation", embed.title)
        assertTrue(embed.description!!.startsWith("Evocation spells"))
    }

    @Test
    fun `WeaponProperty embed renders name and joined description`() {
        val w = JsonParser.parseJsonToWeaponProperty(DnDExpansionFixtures.WEAPON_PROPERTY_FINESSE)!!
        val embed = w.toEmbed()
        assertEquals("Finesse", embed.title)
        assertTrue(embed.description!!.contains("finesse weapon"))
    }

    @Test
    fun `Language embed includes type, script, and typical speakers`() {
        val l = JsonParser.parseJsonToLanguage(DnDExpansionFixtures.LANGUAGE_DWARVISH)!!
        val embed = l.toEmbed()
        assertEquals("Dwarvish", embed.title)
        assertEquals("Standard", embed.fields.first { it.name == "Type" }.value)
        assertEquals("Dwarvish", embed.fields.first { it.name == "Script" }.value)
        assertEquals("Dwarves", embed.fields.first { it.name == "Typical Speakers" }.value)
    }

    @Test
    fun `Skill embed includes ability score field and joined description`() {
        val s = JsonParser.parseJsonToSkill(DnDExpansionFixtures.SKILL_ATHLETICS)!!
        val embed = s.toEmbed()
        assertEquals("Athletics", embed.title)
        assertEquals("STR", embed.fields.first { it.name == "Ability Score" }.value)
    }

    @Test
    fun `Trait embed lists races and joins description`() {
        val t = JsonParser.parseJsonToTrait(DnDExpansionFixtures.TRAIT_DARKVISION)!!
        val embed = t.toEmbed()
        assertEquals("Darkvision", embed.title)
        val racesField = embed.fields.first { it.name == "Races" }
        assertTrue(racesField.value!!.contains("Dwarf"))
        assertTrue(racesField.value!!.contains("Elf"))
    }

    @Test
    fun `Proficiency embed renders type, classes, and reference`() {
        val p = JsonParser.parseJsonToProficiency(DnDExpansionFixtures.PROFICIENCY_LIGHT_ARMOR)!!
        val embed = p.toEmbed()
        assertEquals("Light Armor", embed.title)
        assertEquals("Armor", embed.fields.first { it.name == "Type" }.value)
        assertEquals("Bard", embed.fields.first { it.name == "Classes" }.value)
        assertEquals("Light Armor", embed.fields.first { it.name == "Reference" }.value)
    }

    @Test
    fun `EquipmentCategory embed lists equipment with count header`() {
        val e = JsonParser.parseJsonToEquipmentCategory(DnDExpansionFixtures.EQUIPMENT_CATEGORY_SIMPLE_WEAPONS)!!
        val embed = e.toEmbed()
        assertEquals("Simple Weapons", embed.title)
        val field = embed.fields.first { it.name.startsWith("Equipment") }
        assertTrue(field.name.contains("(2)"))
        assertTrue(field.value!!.contains("Club"))
        assertTrue(field.value!!.contains("Dagger"))
    }

    @Test
    fun `Equipment embed renders cost, weight, damage, and properties for a weapon`() {
        val e = JsonParser.parseJsonToEquipment(DnDExpansionFixtures.EQUIPMENT_LONGSWORD)!!
        val embed = e.toEmbed()
        assertEquals("Longsword", embed.title)
        assertEquals("15 gp", embed.fields.first { it.name == "Cost" }.value)
        assertEquals("3.0 lb", embed.fields.first { it.name == "Weight" }.value)
        assertEquals("1d8 Slashing", embed.fields.first { it.name == "Damage" }.value)
        assertEquals("1d10 Slashing", embed.fields.first { it.name == "Two-Handed Damage" }.value)
        assertEquals("Versatile", embed.fields.first { it.name == "Properties" }.value)
    }

    @Test
    fun `Equipment embed renders armor class info for armor`() {
        val e = JsonParser.parseJsonToEquipment(DnDExpansionFixtures.EQUIPMENT_PLATE_ARMOR)!!
        val embed = e.toEmbed()
        assertEquals("Plate", embed.title)
        assertEquals("Heavy", embed.fields.first { it.name == "Armor Category" }.value)
        assertEquals("18", embed.fields.first { it.name == "Armor Class" }.value)
        assertEquals("15", embed.fields.first { it.name == "Strength Min" }.value)
        assertEquals("Disadvantage", embed.fields.first { it.name == "Stealth" }.value)
    }

    @Test
    fun `DnDClass embed renders hit die, saving throws, proficiencies, and subclasses`() {
        val c = JsonParser.parseJsonToDnDClass(DnDExpansionFixtures.CLASS_FIGHTER)!!
        val embed = c.toEmbed()
        assertEquals("Fighter", embed.title)
        assertEquals("d10", embed.fields.first { it.name == "Hit Die" }.value)
        val saves = embed.fields.first { it.name == "Saving Throws" }.value!!
        assertTrue(saves.contains("STR"))
        assertTrue(saves.contains("CON"))
        val starting = embed.fields.first { it.name == "Starting Equipment" }.value!!
        assertTrue(starting.contains("Chain Mail"))
        assertEquals("Champion", embed.fields.first { it.name == "Subclasses" }.value)
    }

    @Test
    fun `Subclass embed renders parent class and flavor`() {
        val s = JsonParser.parseJsonToSubclass(DnDExpansionFixtures.SUBCLASS_CHAMPION)!!
        val embed = s.toEmbed()
        assertEquals("Champion", embed.title)
        assertEquals("Fighter", embed.fields.first { it.name == "Parent Class" }.value)
        assertEquals("Martial Archetype", embed.fields.first { it.name == "Flavor" }.value)
        assertNotNull(embed.description)
    }

    @Test
    fun `Race embed renders speed, size, ability bonuses, languages, traits and subraces`() {
        val r = JsonParser.parseJsonToRace(DnDExpansionFixtures.RACE_ELF)!!
        val embed = r.toEmbed()
        assertEquals("Elf", embed.title)
        assertEquals("30 ft", embed.fields.first { it.name == "Speed" }.value)
        assertEquals("Medium", embed.fields.first { it.name == "Size" }.value)
        assertEquals("DEX: +2", embed.fields.first { it.name == "Ability Bonuses" }.value)
        val langs = embed.fields.first { it.name == "Languages" }.value!!
        assertTrue(langs.contains("Common"))
        assertTrue(langs.contains("Elvish"))
        assertEquals("Darkvision", embed.fields.first { it.name == "Traits" }.value)
        assertEquals("High Elf", embed.fields.first { it.name == "Subraces" }.value)
    }

    @Test
    fun `Subrace embed renders parent race, ability bonuses, and racial traits`() {
        val s = JsonParser.parseJsonToSubrace(DnDExpansionFixtures.SUBRACE_HIGH_ELF)!!
        val embed = s.toEmbed()
        assertEquals("High Elf", embed.title)
        assertEquals("Elf", embed.fields.first { it.name == "Parent Race" }.value)
        assertEquals("INT: +1", embed.fields.first { it.name == "Ability Bonuses" }.value)
        assertEquals("Elf Weapon Training", embed.fields.first { it.name == "Racial Traits" }.value)
    }

    @Test
    fun `Monster embed renders header, AC, HP, speed, ability scores, CR, languages and actions`() {
        val m = JsonParser.parseJsonToMonster(DnDExpansionFixtures.MONSTER_GOBLIN)!!
        val embed = m.toEmbed()
        assertEquals("Goblin", embed.title)
        assertNotNull(embed.description)
        assertTrue(embed.description!!.contains("Small"))
        assertTrue(embed.description!!.contains("humanoid (goblinoid)"))
        assertTrue(embed.description!!.contains("neutral evil"))

        assertEquals("15 (armor)", embed.fields.first { it.name == "AC" }.value)
        assertEquals("7 (2d6)", embed.fields.first { it.name == "HP" }.value)
        assertEquals("walk 30 ft.", embed.fields.first { it.name == "Speed" }.value)

        val abilities = embed.fields.first { it.name == "Ability Scores" }.value!!
        assertTrue(abilities.contains("STR 8 (-1)"))
        assertTrue(abilities.contains("DEX 14 (+2)"))
        assertTrue(abilities.contains("CHA 8 (-1)"))

        // CR 0.25 and 50 XP
        val challenge = embed.fields.first { it.name == "Challenge" }.value!!
        assertTrue(challenge.contains("0.25"))
        assertTrue(challenge.contains("50 XP"))

        assertEquals("Common, Goblin", embed.fields.first { it.name == "Languages" }.value)

        val senses = embed.fields.first { it.name == "Senses" }.value!!
        assertTrue(senses.contains("darkvision 60 ft."))
        // 9.0 should be normalised to "9", not "9.0"
        assertTrue(senses.contains("passive perception 9"))

        val actions = embed.fields.first { it.name == "Actions" }.value!!
        assertTrue(actions.contains("Scimitar"))
        assertTrue(actions.contains("Shortbow"))

        val specials = embed.fields.first { it.name == "Special Abilities" }.value!!
        assertTrue(specials.contains("Nimble Escape"))
    }

    @Test
    fun `Monster CR renders integer CRs without decimal`() {
        val json = DnDExpansionFixtures.MONSTER_GOBLIN.replace(""""challenge_rating":0.25""", """"challenge_rating":5""")
        val m = JsonParser.parseJsonToMonster(json)!!
        val embed = m.toEmbed()
        val challenge = embed.fields.first { it.name == "Challenge" }.value!!
        assertTrue(challenge.startsWith("5 "))
    }

    @Test
    fun `Monster ability modifier formatting is correct for boundary values`() {
        // 1 -> -5, 10 -> 0, 11 -> 0, 20 -> +5
        val m = JsonParser.parseJsonToMonster(DnDExpansionFixtures.MONSTER_GOBLIN)!!
            .copy(strength = 1, dexterity = 10, constitution = 11, intelligence = 20)
        val embed = m.toEmbed()
        val abilities = embed.fields.first { it.name == "Ability Scores" }.value!!
        assertTrue(abilities.contains("STR 1 (-5)"))
        assertTrue(abilities.contains("DEX 10 (+0)"))
        assertTrue(abilities.contains("CON 11 (+0)"))
        assertTrue(abilities.contains("INT 20 (+5)"))
    }

    @Test
    fun `every embed uses the standard color`() {
        val all = listOf(
            JsonParser.parseJsonToAbilityScore(DnDExpansionFixtures.ABILITY_SCORE_STR)!!.toEmbed(),
            JsonParser.parseJsonToDamageTypeInfo(DnDExpansionFixtures.DAMAGE_TYPE_FIRE)!!.toEmbed(),
            JsonParser.parseJsonToMagicSchool(DnDExpansionFixtures.MAGIC_SCHOOL_EVOCATION)!!.toEmbed(),
            JsonParser.parseJsonToWeaponProperty(DnDExpansionFixtures.WEAPON_PROPERTY_FINESSE)!!.toEmbed(),
            JsonParser.parseJsonToLanguage(DnDExpansionFixtures.LANGUAGE_DWARVISH)!!.toEmbed(),
            JsonParser.parseJsonToSkill(DnDExpansionFixtures.SKILL_ATHLETICS)!!.toEmbed(),
            JsonParser.parseJsonToTrait(DnDExpansionFixtures.TRAIT_DARKVISION)!!.toEmbed(),
            JsonParser.parseJsonToProficiency(DnDExpansionFixtures.PROFICIENCY_LIGHT_ARMOR)!!.toEmbed(),
            JsonParser.parseJsonToEquipmentCategory(DnDExpansionFixtures.EQUIPMENT_CATEGORY_SIMPLE_WEAPONS)!!.toEmbed(),
            JsonParser.parseJsonToEquipment(DnDExpansionFixtures.EQUIPMENT_LONGSWORD)!!.toEmbed(),
            JsonParser.parseJsonToDnDClass(DnDExpansionFixtures.CLASS_FIGHTER)!!.toEmbed(),
            JsonParser.parseJsonToSubclass(DnDExpansionFixtures.SUBCLASS_CHAMPION)!!.toEmbed(),
            JsonParser.parseJsonToRace(DnDExpansionFixtures.RACE_ELF)!!.toEmbed(),
            JsonParser.parseJsonToSubrace(DnDExpansionFixtures.SUBRACE_HIGH_ELF)!!.toEmbed(),
            JsonParser.parseJsonToMonster(DnDExpansionFixtures.MONSTER_GOBLIN)!!.toEmbed()
        )
        all.forEach { assertEquals(0x42f5a7, it.colorRaw) }
    }

    @Test
    fun `EquipmentCategory truncates very long lists to fit Discord field limit`() {
        // Build an EquipmentCategory with 200 items, each "long-name-xxxx"
        val items = (1..200).joinToString(",") { i ->
            """{"index":"item-$i","name":"Equipment Item Number $i Long Long Long","url":"/api/equipment/item-$i"}"""
        }
        val json = """{"index":"big","name":"Big","equipment":[$items],"url":"/api/equipment-categories/big"}"""
        val e = JsonParser.parseJsonToEquipmentCategory(json)!!
        val embed = e.toEmbed()
        val field = embed.fields.first { it.name.startsWith("Equipment") }
        assertTrue(field.value!!.length <= 1024, "Field value should be truncated to <= 1024 chars")
    }
}
