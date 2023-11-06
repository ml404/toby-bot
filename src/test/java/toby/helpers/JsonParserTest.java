package toby.helpers;

import org.junit.jupiter.api.Test;
import toby.dto.web.dnd.information.Information;
import toby.dto.web.dnd.spell.*;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JsonParserTest {

    @Test
    void parseJSONToSpell() {
        String data = """
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
                """;

        // Create an instance of Spell with the expected data
        Spell expectedSpell = new Spell(
                "fireball",
                "Fireball",
                List.of(
                        "A bright streak flashes from your pointing finger to a point you choose within range and then blossoms with a low roar into an explosion of flame. Each creature in a 20-foot-radius sphere centered on that point must make a dexterity saving throw. A target takes 8d6 fire damage on a failed save, or half as much damage on a successful one.",
                        "The fire spreads around corners. It ignites flammable objects in the area that aren't being worn or carried."
                ),
                List.of("When you cast this spell using a spell slot of 4th level or higher, the damage increases by 1d6 for each slot level above 3rd."),
                "150 feet",
                List.of("V", "S", "M"),
                "A tiny ball of bat guano and sulfur.",
                false,
                "Instantaneous",
                false,
                "1 action",
                3,
                new Damage(
                        new DamageType("fire", "Fire", "/api/damage-types/fire"),
                        Map.of("3", "8d6", "4", "9d6", "5", "10d6", "6", "11d6", "7", "12d6", "8", "13d6", "9", "14d6")
                ),
                new Dc(new DcType("dex", "DEX", "/api/ability-scores/dex"),
                        "half"),
                new AreaOfEffect("sphere", 20),
                new School("evocation", "Evocation", "/api/magic-schools/evocation"),
                List.of(
                        new Info("sorcerer", "Sorcerer", "/api/classes/sorcerer"),
                        new Info("wizard", "Wizard", "/api/classes/wizard")
                ),
                List.of(
                        new Info("lore", "Lore", "/api/subclasses/lore"),
                        new Info("fiend", "Fiend", "/api/subclasses/fiend")
                ),
                "/api/spells/fireball"
        );

        Spell spell = JsonParser.parseJSONToSpell(data);

        assertEquals(expectedSpell, spell);
    }

    @Test
    public void test_invalidSpellQuery_returnsListOfCloseApproximations() {
        QueryResult queryResult = JsonParser.parseJsonToQueryResult("""
                {"count":1,"results":[{"index":"bless","name":"Bless","url":"/api/spells/bless"}]}""");

        assertEquals(1, queryResult.count());
        assertEquals(1, queryResult.results().size());
        assertEquals("Bless", queryResult.results().get(0).name());

    }

    @Test
    public void test_conditionQuery_returnsConditionObject() {
        Information condition = JsonParser.parseJsonToInformation("""
                {"index":"grappled","name":"Grappled","desc":["- A grappled creature's speed becomes 0, and it can't benefit from any bonus to its speed.","- The information ends if the grappler is incapacitated (see the information).","- The information also ends if an effect removes the grappled creature from the reach of the grappler or grappling effect, such as when a creature is hurled away by the thunderwave spell."],"url":"/api/conditions/grappled"}""");
        assertEquals(3, condition.desc().size());
        assertEquals("grappled", condition.index());
        assertEquals("Grappled", condition.name());
        assertEquals("/api/conditions/grappled", condition.url());
    }
}