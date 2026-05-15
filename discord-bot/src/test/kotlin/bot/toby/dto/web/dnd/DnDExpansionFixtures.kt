package bot.toby.dto.web.dnd

/**
 * Realistic API response samples for the 14 newly-supported D&D 5e SRD endpoints.
 * Pulled from / modeled after https://www.dnd5eapi.co/api/{type}/{slug} responses.
 */
object DnDExpansionFixtures {

    const val ABILITY_SCORE_STR = """
        {"index":"str","name":"STR","full_name":"Strength",
         "desc":["Strength measures bodily power, athletic training, and the extent to which you can exert raw physical force.",
                 "A Strength check can model any attempt to lift, push, pull, or break something."],
         "skills":[{"index":"athletics","name":"Athletics","url":"/api/skills/athletics"}],
         "url":"/api/ability-scores/str"}
    """

    const val DAMAGE_TYPE_FIRE = """
        {"index":"fire","name":"Fire",
         "desc":["Red dragons breathe fire, and many spells conjure flames to deal fire damage."],
         "url":"/api/damage-types/fire"}
    """

    const val MAGIC_SCHOOL_EVOCATION = """
        {"index":"evocation","name":"Evocation",
         "desc":"Evocation spells manipulate magical energy to produce a desired effect.",
         "url":"/api/magic-schools/evocation"}
    """

    const val WEAPON_PROPERTY_FINESSE = """
        {"index":"finesse","name":"Finesse",
         "desc":["When making an attack with a finesse weapon, you use your choice of your Strength or Dexterity modifier."],
         "url":"/api/weapon-properties/finesse"}
    """

    const val LANGUAGE_DWARVISH = """
        {"index":"dwarvish","name":"Dwarvish","type":"Standard","script":"Dwarvish",
         "typical_speakers":["Dwarves"],
         "desc":"Dwarvish is full of hard consonants and guttural sounds.",
         "url":"/api/languages/dwarvish"}
    """

    const val SKILL_ATHLETICS = """
        {"index":"athletics","name":"Athletics",
         "desc":["Your Strength (Athletics) check covers difficult situations you encounter while climbing, jumping, or swimming."],
         "ability_score":{"index":"str","name":"STR","url":"/api/ability-scores/str"},
         "url":"/api/skills/athletics"}
    """

    const val TRAIT_DARKVISION = """
        {"index":"darkvision","name":"Darkvision",
         "races":[{"index":"dwarf","name":"Dwarf","url":"/api/races/dwarf"},
                  {"index":"elf","name":"Elf","url":"/api/races/elf"}],
         "subraces":[],
         "proficiencies":[],
         "desc":["Accustomed to life underground, you have superior vision in dark and dim conditions."],
         "url":"/api/traits/darkvision"}
    """

    const val PROFICIENCY_LIGHT_ARMOR = """
        {"index":"light-armor","name":"Light Armor","type":"Armor",
         "classes":[{"index":"bard","name":"Bard","url":"/api/classes/bard"}],
         "races":[],
         "reference":{"index":"light-armor","name":"Light Armor","url":"/api/equipment-categories/light-armor"},
         "url":"/api/proficiencies/light-armor"}
    """

    const val EQUIPMENT_CATEGORY_SIMPLE_WEAPONS = """
        {"index":"simple-weapons","name":"Simple Weapons",
         "equipment":[
            {"index":"club","name":"Club","url":"/api/equipment/club"},
            {"index":"dagger","name":"Dagger","url":"/api/equipment/dagger"}
         ],
         "url":"/api/equipment-categories/simple-weapons"}
    """

    const val EQUIPMENT_LONGSWORD = """
        {"index":"longsword","name":"Longsword",
         "equipment_category":{"index":"weapon","name":"Weapon","url":"/api/equipment-categories/weapon"},
         "weapon_category":"Martial","weapon_range":"Melee","category_range":"Martial Melee",
         "cost":{"quantity":15,"unit":"gp"},
         "damage":{"damage_dice":"1d8","damage_type":{"index":"slashing","name":"Slashing","url":"/api/damage-types/slashing"}},
         "two_handed_damage":{"damage_dice":"1d10","damage_type":{"index":"slashing","name":"Slashing","url":"/api/damage-types/slashing"}},
         "range":{"normal":5},
         "weight":3,
         "properties":[
            {"index":"versatile","name":"Versatile","url":"/api/weapon-properties/versatile"}
         ],
         "image":"/api/images/equipment/longsword.png",
         "url":"/api/equipment/longsword"}
    """

    const val EQUIPMENT_PLATE_ARMOR = """
        {"index":"plate","name":"Plate",
         "equipment_category":{"index":"armor","name":"Armor","url":"/api/equipment-categories/armor"},
         "armor_category":"Heavy",
         "armor_class":{"base":18,"dex_bonus":false},
         "str_minimum":15,
         "stealth_disadvantage":true,
         "cost":{"quantity":1500,"unit":"gp"},
         "weight":65,
         "url":"/api/equipment/plate"}
    """

    const val CLASS_FIGHTER = """
        {"index":"fighter","name":"Fighter","hit_die":10,
         "proficiency_choices":[{"choose":2,"type":"proficiencies"}],
         "proficiencies":[{"index":"all-armor","name":"All armor","url":"/api/proficiencies/all-armor"},
                          {"index":"shields","name":"Shields","url":"/api/proficiencies/shields"}],
         "saving_throws":[{"index":"str","name":"STR","url":"/api/ability-scores/str"},
                          {"index":"con","name":"CON","url":"/api/ability-scores/con"}],
         "starting_equipment":[
            {"equipment":{"index":"chain-mail","name":"Chain Mail","url":"/api/equipment/chain-mail"},"quantity":1}
         ],
         "subclasses":[{"index":"champion","name":"Champion","url":"/api/subclasses/champion"}],
         "url":"/api/classes/fighter"}
    """

    const val SUBCLASS_CHAMPION = """
        {"index":"champion","name":"Champion",
         "class":{"index":"fighter","name":"Fighter","url":"/api/classes/fighter"},
         "subclass_flavor":"Martial Archetype",
         "desc":["The archetypal Champion focuses on the development of raw physical power."],
         "spells":[],
         "url":"/api/subclasses/champion"}
    """

    const val RACE_ELF = """
        {"index":"elf","name":"Elf","speed":30,
         "ability_bonuses":[{"ability_score":{"index":"dex","name":"DEX","url":"/api/ability-scores/dex"},"bonus":2}],
         "alignment":"Elves love freedom, variety, and self-expression.",
         "age":"Although elves reach physical maturity at about the same age as humans.",
         "size":"Medium",
         "size_description":"Elves range from under 5 to over 6 feet tall.",
         "starting_proficiencies":[{"index":"skill-perception","name":"Skill: Perception","url":"/api/proficiencies/skill-perception"}],
         "languages":[{"index":"common","name":"Common","url":"/api/languages/common"},
                      {"index":"elvish","name":"Elvish","url":"/api/languages/elvish"}],
         "language_desc":"You can speak, read, and write Common and Elvish.",
         "traits":[{"index":"darkvision","name":"Darkvision","url":"/api/traits/darkvision"}],
         "subraces":[{"index":"high-elf","name":"High Elf","url":"/api/subraces/high-elf"}],
         "url":"/api/races/elf"}
    """

    const val SUBRACE_HIGH_ELF = """
        {"index":"high-elf","name":"High Elf",
         "race":{"index":"elf","name":"Elf","url":"/api/races/elf"},
         "desc":"As a high elf, you have a keen mind and a mastery of at least the basics of magic.",
         "ability_bonuses":[{"ability_score":{"index":"int","name":"INT","url":"/api/ability-scores/int"},"bonus":1}],
         "starting_proficiencies":[{"index":"longswords","name":"Longswords","url":"/api/proficiencies/longswords"}],
         "languages":[{"index":"elvish","name":"Elvish","url":"/api/languages/elvish"}],
         "racial_traits":[{"index":"elf-weapon-training","name":"Elf Weapon Training","url":"/api/traits/elf-weapon-training"}],
         "url":"/api/subraces/high-elf"}
    """

    const val MONSTER_GOBLIN = """
        {"index":"goblin","name":"Goblin","size":"Small","type":"humanoid","subtype":"goblinoid",
         "alignment":"neutral evil",
         "armor_class":[{"type":"armor","value":15}],
         "hit_points":7,"hit_dice":"2d6","hit_points_roll":"2d6",
         "speed":{"walk":"30 ft."},
         "strength":8,"dexterity":14,"constitution":10,"intelligence":10,"wisdom":8,"charisma":8,
         "proficiencies":[{"value":6,"proficiency":{"index":"skill-stealth","name":"Skill: Stealth","url":"/api/proficiencies/skill-stealth"}}],
         "damage_vulnerabilities":[],"damage_resistances":[],"damage_immunities":[],
         "condition_immunities":[],
         "senses":{"darkvision":"60 ft.","passive_perception":9},
         "languages":"Common, Goblin",
         "challenge_rating":0.25,"xp":50,
         "special_abilities":[{"name":"Nimble Escape","desc":"The goblin can take the Disengage or Hide action as a bonus action on each of its turns."}],
         "actions":[{"name":"Scimitar","desc":"Melee Weapon Attack: +4 to hit, reach 5 ft., one target. Hit: 5 (1d6 + 2) slashing damage."},
                    {"name":"Shortbow","desc":"Ranged Weapon Attack: +4 to hit, range 80/320 ft., one target. Hit: 5 (1d6 + 2) piercing damage."}],
         "legendary_actions":[],
         "image":"/api/images/monsters/goblin.png",
         "url":"/api/monsters/goblin"}
    """

    const val QUERY_RESULT_MONSTERS_GOB = """
        {"count":2,"results":[
            {"index":"goblin","name":"Goblin","url":"/api/monsters/goblin"},
            {"index":"goblin-boss","name":"Goblin Boss","url":"/api/monsters/goblin-boss"}
        ]}
    """
}
