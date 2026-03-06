package bot.toby.dto.web.dnd

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class CharacterSheetTest {

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun sheet(
        stats: List<AbilityStat> = listOf(
            AbilityStat(CharacterSheet.STR, 10),
            AbilityStat(CharacterSheet.DEX, 14),
            AbilityStat(CharacterSheet.CON, 12),
            AbilityStat(CharacterSheet.INT, 8),
            AbilityStat(CharacterSheet.WIS, 16),
            AbilityStat(CharacterSheet.CHA, 10)
        ),
        bonusStats: List<AbilityStat>? = null,
        overrideStats: List<AbilityStat>? = null,
        baseHitPoints: Int? = 20,
        bonusHitPoints: Int? = null,
        removedHitPoints: Int? = 0,
        temporaryHitPoints: Int? = 0,
        race: CharacterRace? = null,
        classes: List<CharacterClass>? = null,
        alignmentId: Int? = null,
        background: CharacterBackground? = null,
        currency: CharacterCurrency? = null,
        inspiration: Boolean? = null,
        currentXp: Int? = null
    ) = CharacterSheet(
        id = 1L,
        name = "Test Character",
        stats = stats,
        baseHitPoints = baseHitPoints,
        bonusHitPoints = bonusHitPoints,
        removedHitPoints = removedHitPoints,
        temporaryHitPoints = temporaryHitPoints,
        race = race,
        classes = classes,
        bonusStats = bonusStats,
        overrideStats = overrideStats,
        alignmentId = alignmentId,
        background = background,
        currency = currency,
        inspiration = inspiration,
        currentXp = currentXp
    )

    // ── getStat ───────────────────────────────────────────────────────────────

    @Nested
    inner class GetStat {
        @Test
        fun `returns stat value for known id`() {
            assertEquals(14, sheet().getStat(CharacterSheet.DEX))
        }

        @Test
        fun `returns 10 when stat id not found`() {
            assertEquals(10, sheet(stats = emptyList()).getStat(CharacterSheet.STR))
        }

        @Test
        fun `returns 10 when stats list is null`() {
            val s = sheet().copy(stats = null)
            assertEquals(10, s.getStat(CharacterSheet.STR))
        }
    }

    // ── getEffectiveStat ──────────────────────────────────────────────────────

    @Nested
    inner class GetEffectiveStat {
        @Test
        fun `returns base stat when no bonus or override`() {
            assertEquals(14, sheet().getEffectiveStat(CharacterSheet.DEX))
        }

        @Test
        fun `returns base + bonus when bonus present`() {
            val s = sheet(bonusStats = listOf(AbilityStat(CharacterSheet.DEX, 2)))
            assertEquals(16, s.getEffectiveStat(CharacterSheet.DEX))
        }

        @Test
        fun `override takes precedence over base and bonus`() {
            val s = sheet(
                bonusStats = listOf(AbilityStat(CharacterSheet.STR, 4)),
                overrideStats = listOf(AbilityStat(CharacterSheet.STR, 20))
            )
            assertEquals(20, s.getEffectiveStat(CharacterSheet.STR))
        }

        @Test
        fun `override for one stat does not affect another`() {
            val s = sheet(overrideStats = listOf(AbilityStat(CharacterSheet.STR, 20)))
            assertEquals(14, s.getEffectiveStat(CharacterSheet.DEX))
        }
    }

    // ── modifier ──────────────────────────────────────────────────────────────

    @Nested
    inner class Modifier {
        @Test
        fun `score 10 gives modifier 0`() = assertEquals(0, sheet().modifier(CharacterSheet.STR))

        @Test
        fun `score 14 gives modifier +2`() = assertEquals(2, sheet().modifier(CharacterSheet.DEX))

        @Test
        fun `score 16 gives modifier +3`() = assertEquals(3, sheet().modifier(CharacterSheet.WIS))

        @Test
        fun `score 8 gives modifier -1`() = assertEquals(-1, sheet().modifier(CharacterSheet.INT))

        @Test
        fun `score 1 gives modifier -5`() {
            val s = sheet(stats = listOf(AbilityStat(CharacterSheet.STR, 1)))
            assertEquals(-5, s.modifier(CharacterSheet.STR))
        }

        @Test
        fun `score 20 gives modifier +5`() {
            val s = sheet(stats = listOf(AbilityStat(CharacterSheet.STR, 20)))
            assertEquals(5, s.modifier(CharacterSheet.STR))
        }
    }

    // ── HP helpers ────────────────────────────────────────────────────────────

    @Nested
    inner class HitPoints {
        @Test
        fun `currentHp is base minus removed`() {
            val s = sheet(baseHitPoints = 30, removedHitPoints = 8)
            assertEquals(22, s.currentHp())
        }

        @Test
        fun `currentHp includes bonus hp`() {
            val s = sheet(baseHitPoints = 20, bonusHitPoints = 10, removedHitPoints = 5)
            assertEquals(25, s.currentHp())
        }

        @Test
        fun `maxHp is base plus bonus`() {
            val s = sheet(baseHitPoints = 20, bonusHitPoints = 10)
            assertEquals(30, s.maxHp())
        }

        @Test
        fun `maxHp with no bonus equals base`() {
            assertEquals(20, sheet().maxHp())
        }
    }

    // ── raceName ──────────────────────────────────────────────────────────────

    @Nested
    inner class RaceName {
        @Test
        fun `prefers fullName`() {
            val s = sheet(race = CharacterRace(fullName = "High Elf", baseName = "Elf"))
            assertEquals("High Elf", s.raceName())
        }

        @Test
        fun `falls back to baseName when fullName null`() {
            val s = sheet(race = CharacterRace(fullName = null, baseName = "Elf"))
            assertEquals("Elf", s.raceName())
        }

        @Test
        fun `returns Unknown when race is null`() {
            assertEquals("Unknown", sheet().raceName())
        }
    }

    // ── classesString ─────────────────────────────────────────────────────────

    @Nested
    inner class ClassesString {
        @Test
        fun `single class without subclass`() {
            val s = sheet(classes = listOf(CharacterClass(level = 5, definition = ClassDefinition("Fighter"))))
            assertEquals("Fighter 5", s.classesString())
        }

        @Test
        fun `single class with subclass`() {
            val s = sheet(
                classes = listOf(
                    CharacterClass(
                        level = 3,
                        definition = ClassDefinition("Ranger"),
                        subclassDefinition = ClassDefinition("Gloom Stalker")
                    )
                )
            )
            assertEquals("Ranger (Gloom Stalker) 3", s.classesString())
        }

        @Test
        fun `multiclass is comma-separated`() {
            val s = sheet(
                classes = listOf(
                    CharacterClass(level = 5, definition = ClassDefinition("Fighter")),
                    CharacterClass(level = 3, definition = ClassDefinition("Rogue"))
                )
            )
            assertEquals("Fighter 5, Rogue 3", s.classesString())
        }

        @Test
        fun `returns Unknown when classes null`() {
            assertEquals("Unknown", sheet().classesString())
        }
    }

    // ── totalLevel & proficiencyBonus ─────────────────────────────────────────

    @Nested
    inner class Levels {
        @Test
        fun `totalLevel sums class levels`() {
            val s = sheet(
                classes = listOf(
                    CharacterClass(level = 5, definition = ClassDefinition("Fighter")),
                    CharacterClass(level = 3, definition = ClassDefinition("Rogue"))
                )
            )
            assertEquals(8, s.totalLevel())
        }

        @Test
        fun `totalLevel is 0 when classes null`() {
            assertEquals(0, sheet().totalLevel())
        }

        @Test
        fun `proficiency bonus is 2 at level 1`() {
            val s = sheet(classes = listOf(CharacterClass(level = 1, definition = ClassDefinition("Fighter"))))
            assertEquals(2, s.proficiencyBonus())
        }

        @Test
        fun `proficiency bonus is 3 at level 5`() {
            val s = sheet(classes = listOf(CharacterClass(level = 5, definition = ClassDefinition("Fighter"))))
            assertEquals(3, s.proficiencyBonus())
        }

        @Test
        fun `proficiency bonus is 4 at level 9`() {
            val s = sheet(classes = listOf(CharacterClass(level = 9, definition = ClassDefinition("Wizard"))))
            assertEquals(4, s.proficiencyBonus())
        }

        @Test
        fun `proficiency bonus is 6 at level 17`() {
            val s = sheet(classes = listOf(CharacterClass(level = 17, definition = ClassDefinition("Paladin"))))
            assertEquals(6, s.proficiencyBonus())
        }
    }

    // ── passivePerception ─────────────────────────────────────────────────────

    @Nested
    inner class PassivePerception {
        @Test
        fun `10 plus WIS modifier`() {
            // WIS 16 -> modifier +3 -> passive perception 13
            assertEquals(13, sheet().passivePerception())
        }

        @Test
        fun `WIS 10 gives passive perception 10`() {
            val s = sheet(stats = listOf(AbilityStat(CharacterSheet.WIS, 10)))
            assertEquals(10, s.passivePerception())
        }
    }

    // ── walkSpeed ─────────────────────────────────────────────────────────────

    @Nested
    inner class WalkSpeed {
        @Test
        fun `returns race normal speed`() {
            val s = sheet(race = CharacterRace(fullName = "Dwarf", baseName = "Dwarf", weightSpeeds = WeightSpeeds(normal = 25)))
            assertEquals(25, s.walkSpeed())
        }

        @Test
        fun `defaults to 30 when race is null`() {
            assertEquals(30, sheet().walkSpeed())
        }

        @Test
        fun `defaults to 30 when weightSpeeds is null`() {
            val s = sheet(race = CharacterRace(fullName = "Human", baseName = "Human", weightSpeeds = null))
            assertEquals(30, s.walkSpeed())
        }
    }

    // ── backgroundName ────────────────────────────────────────────────────────

    @Nested
    inner class BackgroundName {
        @Test
        fun `returns definition name`() {
            val s = sheet(background = CharacterBackground(definition = BackgroundDefinition(name = "Sage")))
            assertEquals("Sage", s.backgroundName())
        }

        @Test
        fun `returns Unknown when background null`() {
            assertEquals("Unknown", sheet().backgroundName())
        }

        @Test
        fun `returns Unknown when definition null`() {
            val s = sheet(background = CharacterBackground(definition = null))
            assertEquals("Unknown", s.backgroundName())
        }
    }

    // ── alignmentName ─────────────────────────────────────────────────────────

    @Nested
    inner class AlignmentName {
        @Test
        fun `id 1 is Lawful Good`() = assertEquals("Lawful Good", sheet(alignmentId = 1).alignmentName())

        @Test
        fun `id 2 is Neutral Good`() = assertEquals("Neutral Good", sheet(alignmentId = 2).alignmentName())

        @Test
        fun `id 3 is Chaotic Good`() = assertEquals("Chaotic Good", sheet(alignmentId = 3).alignmentName())

        @Test
        fun `id 4 is Lawful Neutral`() = assertEquals("Lawful Neutral", sheet(alignmentId = 4).alignmentName())

        @Test
        fun `id 5 is True Neutral`() = assertEquals("True Neutral", sheet(alignmentId = 5).alignmentName())

        @Test
        fun `id 6 is Chaotic Neutral`() = assertEquals("Chaotic Neutral", sheet(alignmentId = 6).alignmentName())

        @Test
        fun `id 7 is Lawful Evil`() = assertEquals("Lawful Evil", sheet(alignmentId = 7).alignmentName())

        @Test
        fun `id 8 is Neutral Evil`() = assertEquals("Neutral Evil", sheet(alignmentId = 8).alignmentName())

        @Test
        fun `id 9 is Chaotic Evil`() = assertEquals("Chaotic Evil", sheet(alignmentId = 9).alignmentName())

        @Test
        fun `null id returns Unknown`() = assertEquals("Unknown", sheet(alignmentId = null).alignmentName())

        @Test
        fun `out-of-range id returns Unknown`() = assertEquals("Unknown", sheet(alignmentId = 99).alignmentName())
    }

    // ── currencySummary ───────────────────────────────────────────────────────

    @Nested
    inner class CurrencySummary {
        @Test
        fun `returns dash when currency null`() {
            assertEquals("—", sheet().currencySummary())
        }

        @Test
        fun `returns dash when all denominations zero`() {
            val s = sheet(currency = CharacterCurrency(gp = 0, sp = 0, cp = 0))
            assertEquals("—", s.currencySummary())
        }

        @Test
        fun `formats gp and sp only`() {
            val s = sheet(currency = CharacterCurrency(gp = 50, sp = 10))
            assertEquals("50gp 10sp", s.currencySummary())
        }

        @Test
        fun `formats all denominations in order pp gp ep sp cp`() {
            val s = sheet(currency = CharacterCurrency(cp = 5, sp = 4, ep = 3, gp = 2, pp = 1))
            assertEquals("1pp 2gp 3ep 4sp 5cp", s.currencySummary())
        }

        @Test
        fun `omits zero denominations`() {
            val s = sheet(currency = CharacterCurrency(pp = 1, gp = 0, cp = 5))
            assertEquals("1pp 5cp", s.currencySummary())
        }
    }

    // ── toEmbed ───────────────────────────────────────────────────────────────

    @Nested
    inner class ToEmbed {
        private val fullSheet = sheet(
            stats = listOf(
                AbilityStat(CharacterSheet.STR, 10),
                AbilityStat(CharacterSheet.DEX, 14),
                AbilityStat(CharacterSheet.CON, 12),
                AbilityStat(CharacterSheet.INT, 8),
                AbilityStat(CharacterSheet.WIS, 16),
                AbilityStat(CharacterSheet.CHA, 10)
            ),
            race = CharacterRace(fullName = "High Elf", baseName = "Elf", weightSpeeds = WeightSpeeds(normal = 30)),
            classes = listOf(CharacterClass(level = 5, definition = ClassDefinition("Wizard"))),
            alignmentId = 3,
            background = CharacterBackground(definition = BackgroundDefinition(name = "Sage")),
            currency = CharacterCurrency(gp = 100),
            inspiration = true,
            currentXp = 6500
        )

        @Test
        fun `embed title is character name`() {
            val embed = fullSheet.toEmbed()
            assertEquals("Test Character", embed.title)
        }

        @Test
        fun `embed contains expected field names`() {
            val embed = fullSheet.toEmbed()
            val fieldNames = embed.fields.map { it.name }
            assert("Race" in fieldNames)
            assert("Class" in fieldNames)
            assert("Level" in fieldNames)
            assert("Alignment" in fieldNames)
            assert("Background" in fieldNames)
            assert("XP" in fieldNames)
            assert("Prof. Bonus" in fieldNames)
            assert("Speed" in fieldNames)
            assert("Inspiration" in fieldNames)
            assert("STR" in fieldNames)
            assert("DEX" in fieldNames)
            assert("CON" in fieldNames)
            assert("INT" in fieldNames)
            assert("WIS" in fieldNames)
            assert("CHA" in fieldNames)
            assert("HP" in fieldNames)
            assert("Passive Perception" in fieldNames)
            assert("Currency" in fieldNames)
        }

        @Test
        fun `embed field values are correct`() {
            val embed = fullSheet.toEmbed()
            fun field(name: String) = embed.fields.first { it.name == name }.value

            assertEquals("High Elf", field("Race"))
            assertEquals("Wizard 5", field("Class"))
            assertEquals("5", field("Level"))
            assertEquals("Chaotic Good", field("Alignment"))
            assertEquals("Sage", field("Background"))
            assertEquals("6500", field("XP"))
            assertEquals("+3", field("Prof. Bonus"))
            assertEquals("30 ft", field("Speed"))
            assertEquals("✓", field("Inspiration"))
            assertEquals("10 (+0)", field("STR"))
            assertEquals("14 (+2)", field("DEX"))
            assertEquals("12 (+1)", field("CON"))
            assertEquals("8 (-1)", field("INT"))
            assertEquals("16 (+3)", field("WIS"))
            assertEquals("10 (+0)", field("CHA"))
            assertEquals("13", field("Passive Perception")) // 10 + modifier(WIS 16) = 10 + 3 = 13
            assertEquals("100gp", field("Currency"))
        }

        @Test
        fun `inspiration shown as dash when false`() {
            val s = fullSheet.copy(inspiration = false)
            val field = s.toEmbed().fields.first { it.name == "Inspiration" }.value
            assertEquals("—", field)
        }

        @Test
        fun `temp HP field shown only when greater than zero`() {
            val withTempHp = fullSheet.copy(temporaryHitPoints = 5)
            val without = fullSheet.copy(temporaryHitPoints = 0)
            assert(withTempHp.toEmbed().fields.any { it.name == "Temp HP" })
            assert(without.toEmbed().fields.none { it.name == "Temp HP" })
        }
    }
}
