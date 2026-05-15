package bot.toby.helpers

import bot.toby.dto.web.dnd.DnDExpansionFixtures
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class JsonParserExpansionTest {

    @Test
    fun `parseJsonToAbilityScore parses STR fixture`() {
        val a = JsonParser.parseJsonToAbilityScore(DnDExpansionFixtures.ABILITY_SCORE_STR)
        assertNotNull(a)
        assertEquals("str", a!!.index)
        assertEquals("STR", a.name)
        assertEquals("Strength", a.fullName)
        assertEquals(2, a.desc?.size)
        assertEquals(1, a.skills?.size)
        assertEquals("Athletics", a.skills?.first()?.name)
        assertTrue(a.isValidReturnObject())
    }

    @Test
    fun `parseJsonToDamageTypeInfo parses fire fixture`() {
        val d = JsonParser.parseJsonToDamageTypeInfo(DnDExpansionFixtures.DAMAGE_TYPE_FIRE)
        assertNotNull(d)
        assertEquals("fire", d!!.index)
        assertEquals("Fire", d.name)
        assertEquals(1, d.desc?.size)
        assertTrue(d.isValidReturnObject())
    }

    @Test
    fun `parseJsonToMagicSchool parses evocation fixture`() {
        val m = JsonParser.parseJsonToMagicSchool(DnDExpansionFixtures.MAGIC_SCHOOL_EVOCATION)
        assertNotNull(m)
        assertEquals("evocation", m!!.index)
        assertEquals("Evocation", m.name)
        assertTrue(m.desc!!.startsWith("Evocation spells"))
        assertTrue(m.isValidReturnObject())
    }

    @Test
    fun `parseJsonToWeaponProperty parses finesse fixture`() {
        val w = JsonParser.parseJsonToWeaponProperty(DnDExpansionFixtures.WEAPON_PROPERTY_FINESSE)
        assertNotNull(w)
        assertEquals("finesse", w!!.index)
        assertEquals("Finesse", w.name)
        assertEquals(1, w.desc?.size)
        assertTrue(w.isValidReturnObject())
    }

    @Test
    fun `parseJsonToLanguage parses dwarvish fixture`() {
        val l = JsonParser.parseJsonToLanguage(DnDExpansionFixtures.LANGUAGE_DWARVISH)
        assertNotNull(l)
        assertEquals("dwarvish", l!!.index)
        assertEquals("Standard", l.type)
        assertEquals("Dwarvish", l.script)
        assertEquals(listOf("Dwarves"), l.typicalSpeakers)
        assertTrue(l.isValidReturnObject())
    }

    @Test
    fun `parseJsonToSkill parses athletics fixture`() {
        val s = JsonParser.parseJsonToSkill(DnDExpansionFixtures.SKILL_ATHLETICS)
        assertNotNull(s)
        assertEquals("athletics", s!!.index)
        assertEquals("STR", s.abilityScore?.name)
        assertEquals(1, s.desc?.size)
        assertTrue(s.isValidReturnObject())
    }

    @Test
    fun `parseJsonToTrait parses darkvision fixture`() {
        val t = JsonParser.parseJsonToTrait(DnDExpansionFixtures.TRAIT_DARKVISION)
        assertNotNull(t)
        assertEquals("Darkvision", t!!.name)
        assertEquals(2, t.races?.size)
        assertEquals("Dwarf", t.races!![0].name)
        assertEquals("Elf", t.races[1].name)
        assertTrue(t.isValidReturnObject())
    }

    @Test
    fun `parseJsonToProficiency parses light-armor fixture`() {
        val p = JsonParser.parseJsonToProficiency(DnDExpansionFixtures.PROFICIENCY_LIGHT_ARMOR)
        assertNotNull(p)
        assertEquals("Light Armor", p!!.name)
        assertEquals("Armor", p.type)
        assertEquals("Bard", p.classes?.first()?.name)
        assertEquals("Light Armor", p.reference?.name)
        assertTrue(p.isValidReturnObject())
    }

    @Test
    fun `parseJsonToEquipmentCategory parses simple-weapons fixture`() {
        val e = JsonParser.parseJsonToEquipmentCategory(DnDExpansionFixtures.EQUIPMENT_CATEGORY_SIMPLE_WEAPONS)
        assertNotNull(e)
        assertEquals("Simple Weapons", e!!.name)
        assertEquals(2, e.equipment?.size)
        assertTrue(e.isValidReturnObject())
    }

    @Test
    fun `parseJsonToEquipment parses longsword fixture with damage and properties`() {
        val e = JsonParser.parseJsonToEquipment(DnDExpansionFixtures.EQUIPMENT_LONGSWORD)
        assertNotNull(e)
        assertEquals("Longsword", e!!.name)
        assertEquals(15, e.cost?.quantity)
        assertEquals("gp", e.cost?.unit)
        assertEquals("1d8", e.damage?.damageDice)
        assertEquals("Slashing", e.damage?.damageType?.name)
        assertEquals("1d10", e.twoHandedDamage?.damageDice)
        assertEquals(3.0, e.weight)
        assertEquals(1, e.properties?.size)
        assertEquals("Versatile", e.properties?.first()?.name)
        assertEquals("/api/images/equipment/longsword.png", e.image)
        assertTrue(e.isValidReturnObject())
    }

    @Test
    fun `parseJsonToEquipment parses plate armor fixture with armor class`() {
        val e = JsonParser.parseJsonToEquipment(DnDExpansionFixtures.EQUIPMENT_PLATE_ARMOR)
        assertNotNull(e)
        assertEquals("Plate", e!!.name)
        assertEquals("Heavy", e.armorCategory)
        assertEquals(18, e.armorClass?.base)
        assertEquals(false, e.armorClass?.dexBonus)
        assertEquals(15, e.strMinimum)
        assertEquals(true, e.stealthDisadvantage)
        assertNull(e.image, "Plate fixture intentionally omits image; field should be null")
        assertTrue(e.isValidReturnObject())
    }

    @Test
    fun `parseJsonToDnDClass parses fighter fixture`() {
        val c = JsonParser.parseJsonToDnDClass(DnDExpansionFixtures.CLASS_FIGHTER)
        assertNotNull(c)
        assertEquals("Fighter", c!!.name)
        assertEquals(10, c.hitDie)
        assertEquals(2, c.savingThrows?.size)
        assertEquals("STR", c.savingThrows!![0].name)
        assertEquals(1, c.startingEquipment?.size)
        assertEquals("Chain Mail", c.startingEquipment!![0].equipment?.name)
        assertEquals("Champion", c.subclasses?.first()?.name)
        assertTrue(c.isValidReturnObject())
    }

    @Test
    fun `parseJsonToSubclass parses champion fixture`() {
        val s = JsonParser.parseJsonToSubclass(DnDExpansionFixtures.SUBCLASS_CHAMPION)
        assertNotNull(s)
        assertEquals("Champion", s!!.name)
        assertEquals("Fighter", s.parentClass?.name)
        assertEquals("Martial Archetype", s.subclassFlavor)
        assertTrue(s.isValidReturnObject())
    }

    @Test
    fun `parseJsonToRace parses elf fixture`() {
        val r = JsonParser.parseJsonToRace(DnDExpansionFixtures.RACE_ELF)
        assertNotNull(r)
        assertEquals("Elf", r!!.name)
        assertEquals(30, r.speed)
        assertEquals("Medium", r.size)
        assertEquals(1, r.abilityBonuses?.size)
        assertEquals("DEX", r.abilityBonuses!![0].abilityScore?.name)
        assertEquals(2, r.abilityBonuses[0].bonus)
        assertEquals(2, r.languages?.size)
        assertEquals(1, r.subraces?.size)
        assertTrue(r.isValidReturnObject())
    }

    @Test
    fun `parseJsonToSubrace parses high-elf fixture`() {
        val s = JsonParser.parseJsonToSubrace(DnDExpansionFixtures.SUBRACE_HIGH_ELF)
        assertNotNull(s)
        assertEquals("High Elf", s!!.name)
        assertEquals("Elf", s.race?.name)
        assertEquals("INT", s.abilityBonuses?.first()?.abilityScore?.name)
        assertEquals(1, s.abilityBonuses?.first()?.bonus)
        assertEquals("Elf Weapon Training", s.racialTraits?.first()?.name)
        assertTrue(s.isValidReturnObject())
    }

    @Test
    fun `parseJsonToMonster parses goblin fixture with full schema`() {
        val m = JsonParser.parseJsonToMonster(DnDExpansionFixtures.MONSTER_GOBLIN)
        assertNotNull(m)
        assertEquals("Goblin", m!!.name)
        assertEquals("Small", m.size)
        assertEquals("humanoid", m.type)
        assertEquals("goblinoid", m.subtype)
        assertEquals("neutral evil", m.alignment)
        assertEquals(1, m.armorClass?.size)
        assertEquals(15, m.armorClass?.first()?.value)
        assertEquals(7, m.hitPoints)
        assertEquals("2d6", m.hitDice)
        assertEquals("30 ft.", m.speed?.walk)
        assertEquals(8, m.strength)
        assertEquals(14, m.dexterity)
        assertEquals(0.25, m.challengeRating)
        assertEquals(50, m.xp)
        assertEquals(1, m.specialAbilities?.size)
        assertEquals("Nimble Escape", m.specialAbilities?.first()?.name)
        assertEquals(2, m.actions?.size)
        assertEquals("Scimitar", m.actions?.first()?.name)
        assertEquals("/api/images/monsters/goblin.png", m.image)
        assertTrue(m.isValidReturnObject())
    }

    @Test
    fun `parseJsonToMonster handles senses map with mixed numeric and string values`() {
        val m = JsonParser.parseJsonToMonster(DnDExpansionFixtures.MONSTER_GOBLIN)
        assertNotNull(m?.senses)
        val senses = m!!.senses!!
        assertEquals("60 ft.", senses["darkvision"])
        // Gson parses numeric senses as Double; the embed renderer normalises this
        assertEquals(9.0, senses["passive_perception"])
    }

    @Test
    fun `parseJsonToQueryResult returns null on blank input instead of throwing`() {
        assertNull(JsonParser.parseJsonToQueryResult(""))
        assertNull(JsonParser.parseJsonToQueryResult(null))
        assertNull(JsonParser.parseJsonToQueryResult("   "))
    }

    @Test
    fun `parseJsonToQueryResult parses results array`() {
        val q = JsonParser.parseJsonToQueryResult(DnDExpansionFixtures.QUERY_RESULT_MONSTERS_GOB)
        assertNotNull(q)
        assertEquals(2, q!!.count)
        assertEquals("Goblin", q.results[0].name)
        assertEquals("goblin-boss", q.results[1].index)
    }

    @Test
    fun `isValidReturnObject returns false for empty payloads`() {
        val emptyMonster = JsonParser.parseJsonToMonster("{}")
        assertNotNull(emptyMonster)
        assertFalse(emptyMonster!!.isValidReturnObject())

        val emptyClass = JsonParser.parseJsonToDnDClass("{}")
        assertNotNull(emptyClass)
        assertFalse(emptyClass!!.isValidReturnObject())

        val emptyRace = JsonParser.parseJsonToRace("{}")
        assertNotNull(emptyRace)
        assertFalse(emptyRace!!.isValidReturnObject())
    }
}
