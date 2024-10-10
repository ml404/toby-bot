package toby.helpers

import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import toby.helpers.JsonParser.parseJSONToSpell
import toby.helpers.JsonParser.parseJsonToCondition
import toby.helpers.JsonParser.parseJsonToFeature
import toby.helpers.JsonParser.parseJsonToQueryResult
import toby.helpers.JsonParser.parseJsonToRule
import toby.web.dnd.*

internal class JsonParserTest {
    @Test
    fun parseJSONToSpell() {
        val data = fireBallDataJson

        // Create an instance of Spell with the expected data
        val expectedSpell = Spell(
            "fireball",
            "Fireball",
            listOf(
                "A bright streak flashes from your pointing finger to a point you choose within range and then blossoms with a low roar into an explosion of flame. Each creature in a 20-foot-radius sphere centered on that point must make a dexterity saving throw. A target takes 8d6 fire damage on a failed save, or half as much damage on a successful one.",
                "The fire spreads around corners. It ignites flammable objects in the area that aren't being worn or carried."
            ),
            listOf("When you cast this spell using a spell slot of 4th level or higher, the damage increases by 1d6 for each slot level above 3rd."),
            "150 feet",
            listOf("V", "S", "M"),
            "A tiny ball of bat guano and sulfur.",
            false,
            "Instantaneous",
            false,
            "1 action",
            3,
            Damage(
                DamageType("fire", "Fire", "/api/damage-types/fire"),
                mapOf(
                    "3" to "8d6",
                    "4" to "9d6",
                    "5" to "10d6",
                    "6" to "11d6",
                    "7" to "12d6",
                    "8" to "13d6",
                    "9" to "14d6"
                )
            ),
            Dc(
                ApiInfo("dex", "DEX", "/api/ability-scores/dex"),
                "half"
            ),
            AreaOfEffect("sphere", 20),
            ApiInfo("evocation", "Evocation", "/api/magic-schools/evocation"),
            listOf(
                ApiInfo("sorcerer", "Sorcerer", "/api/classes/sorcerer"),
                ApiInfo("wizard", "Wizard", "/api/classes/wizard")
            ),
            listOf(
                ApiInfo("lore", "Lore", "/api/subclasses/lore"),
                ApiInfo("fiend", "Fiend", "/api/subclasses/fiend")
            ),
            "/api/spells/fireball"
        )

        val spell = parseJSONToSpell(data)

        Assertions.assertEquals(expectedSpell, spell)
    }

    @Test
    fun test_invalidSpellQuery_returnsListOfCloseApproximations() {
        val queryResult = parseJsonToQueryResult(
            """
                {"count":1,"results":[{"index":"bless","name":"Bless","url":"/api/spells/bless"}]}
                """.trimIndent()
        )

        Assertions.assertEquals(1, queryResult?.count)
        Assertions.assertEquals(1, queryResult?.results?.size)
        Assertions.assertEquals("Bless", queryResult?.results?.get(0)?.name)
    }

    @Test
    fun test_conditionQuery_returnsConditionObject() {
        val condition = parseJsonToCondition(
            """
                {"index":"grappled","name":"Grappled","desc":["- A grappled creature's speed becomes 0, and it can't benefit from any bonus to its speed.","- The information ends if the grappler is incapacitated (see the information).","- The information also ends if an effect removes the grappled creature from the reach of the grappler or grappling effect, such as when a creature is hurled away by the thunderwave spell."],"url":"/api/conditions/grappled"}
                """.trimIndent()
        )
        Assertions.assertEquals(3, condition?.desc?.size)
        Assertions.assertEquals("grappled", condition?.index)
        Assertions.assertEquals("Grappled", condition?.name)
        Assertions.assertEquals("/api/conditions/grappled", condition?.url)
    }

    @Test
    fun test_ruleQuery_returnsRuleObject() {
        val jsonData = """
        {"name":"Cover","index":"cover","desc":"## Cover\n\nWalls, trees, creatures, and other obstacles can provide cover during combat, making a target more difficult to harm. A target can benefit from cover only when an attack or other effect originates on the opposite side of the cover.\n\nThere are three degrees of cover. If a target is behind multiple sources of cover, only the most protective degree of cover applies; the degrees aren't added together. For example, if a target is behind a creature that gives half cover and a tree trunk that gives three-quarters cover, the target has three-quarters cover.\n\nA target with **half cover** has a +2 bonus to AC and Dexterity saving throws. A target has half cover if an obstacle blocks at least half of its body. The obstacle might be a low wall, a large piece of furniture, a narrow tree trunk, or a creature, whether that creature is an enemy or a friend.\n\nA target with **three-quarters cover** has a +5 bonus to AC and Dexterity saving throws. A target has three-quarters cover if about three-quarters of it is covered by an obstacle. The obstacle might be a portcullis, an arrow slit, or a thick tree trunk.\n\nA target with **total cover** can't be targeted directly by an attack or a spell, although some spells can reach such a target by including it in an area of effect. A target has total cover if it is completely concealed by an obstacle.\n","url":"/api/rule-sections/cover"}
        """.trimIndent()

        val rule = parseJsonToRule(jsonData)
        Assertions.assertEquals("""
## Cover

Walls, trees, creatures, and other obstacles can provide cover during combat, making a target more difficult to harm. A target can benefit from cover only when an attack or other effect originates on the opposite side of the cover.

There are three degrees of cover. If a target is behind multiple sources of cover, only the most protective degree of cover applies; the degrees aren't added together. For example, if a target is behind a creature that gives half cover and a tree trunk that gives three-quarters cover, the target has three-quarters cover.

A target with **half cover** has a +2 bonus to AC and Dexterity saving throws. A target has half cover if an obstacle blocks at least half of its body. The obstacle might be a low wall, a large piece of furniture, a narrow tree trunk, or a creature, whether that creature is an enemy or a friend.

A target with **three-quarters cover** has a +5 bonus to AC and Dexterity saving throws. A target has three-quarters cover if about three-quarters of it is covered by an obstacle. The obstacle might be a portcullis, an arrow slit, or a thick tree trunk.

A target with **total cover** can't be targeted directly by an attack or a spell, although some spells can reach such a target by including it in an area of effect. A target has total cover if it is completely concealed by an obstacle.

""".trimIndent(), rule?.desc
        )
        Assertions.assertEquals("cover", rule?.index)
        Assertions.assertEquals("Cover", rule?.name)
        Assertions.assertEquals("/api/rule-sections/cover", rule?.url)
    }

    @Test
    fun test_featureQuery_returnsFeatureObject() {
        val jsonData = """
                {"index":"action-surge-1-use","class":{"index":"fighter","name":"Fighter","url":"/api/classes/fighter"},"name":"Action Surge (1 use)","level":2,"prerequisites":[],"desc":["Starting at 2nd level, you can push yourself beyond your normal limits for a moment. On your turn, you can take one additional action on top of your regular action and a possible bonus action.","Once you use this feature, you must finish a short or long rest before you can use it again. Starting at 17th level, you can use it twice before a rest, but only once on the same turn."],"url":"/api/features/action-surge-1-use"}
                """.trimIndent()
        val feature = parseJsonToFeature(jsonData)
        Assertions.assertEquals("action-surge-1-use", feature?.index)
        Assertions.assertEquals("Action Surge (1 use)", feature?.name)
        Assertions.assertEquals("/api/features/action-surge-1-use", feature?.url)
    }

    companion object {
        private val fireBallDataJson: String
            get() = """
                {
                	"index": "fireball",
                	"name": "Fireball",
                	"desc": [
                		"A bright streak flashes from your pointing finger to a point you choose within range and then blossoms with a low roar into an explosion of flame. Each creature in a 20-foot-radius sphere centered on that point must make a dexterity saving throw. A target takes 8d6 fire damage on a failed save, or half as much damage on a successful one.",
                		"The fire spreads around corners. It ignites flammable objects in the area that aren't being worn or carried."
                	],
                	"higher_level": [
                		"When you cast this spell using a spell slot of 4th level or higher, the damage increases by 1d6 for each slot level above 3rd."
                	],
                	"range": "150 feet",
                	"components": [
                		"V",
                		"S",
                		"M"
                	],
                	"material": "A tiny ball of bat guano and sulfur.",
                	"ritual": false,
                	"duration": "Instantaneous",
                	"concentration": false,
                	"casting_time": "1 action",
                	"level": 3,
                	"damage": {
                		"damage_type": {
                			"index": "fire",
                			"name": "Fire",
                			"url": "/api/damage-types/fire"
                		},
                		"damage_at_slot_level": {
                			"3": "8d6",
                			"4": "9d6",
                			"5": "10d6",
                			"6": "11d6",
                			"7": "12d6",
                			"8": "13d6",
                			"9": "14d6"
                		}
                	},
                	"dc": {
                		"dc_type": {
                			"index": "dex",
                			"name": "DEX",
                			"url": "/api/ability-scores/dex"
                		},
                		"dc_success": "half"
                	},
                	"area_of_effect": {
                		"type": "sphere",
                		"size": 20
                	},
                	"school": {
                		"index": "evocation",
                		"name": "Evocation",
                		"url": "/api/magic-schools/evocation"
                	},
                	"classes": [
                		{
                			"index": "sorcerer",
                			"name": "Sorcerer",
                			"url": "/api/classes/sorcerer"
                		},
                		{
                			"index": "wizard",
                			"name": "Wizard",
                			"url": "/api/classes/wizard"
                		}
                	],
                	"subclasses": [
                		{
                			"index": "lore",
                			"name": "Lore",
                			"url": "/api/subclasses/lore"
                		},
                		{
                			"index": "fiend",
                			"name": "Fiend",
                			"url": "/api/subclasses/fiend"
                		}
                	],
                	"url": "/api/spells/fireball"
                }
                
                """.trimIndent()
    }
}