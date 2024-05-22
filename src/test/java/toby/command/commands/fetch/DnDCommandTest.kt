package toby.command.commands.fetch

import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.interactions.commands.OptionMapping
import net.dv8tion.jda.api.interactions.components.ItemComponent
import net.dv8tion.jda.api.interactions.components.selections.StringSelectMenu
import net.dv8tion.jda.api.requests.restaction.WebhookMessageCreateAction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers
import org.mockito.Mockito
import org.mockito.kotlin.anyVararg
import toby.command.CommandContext
import toby.command.CommandTest
import toby.command.CommandTest.Companion.webhookMessageCreateAction
import toby.command.commands.dnd.DnDCommand
import toby.helpers.HttpHelper

internal class DnDCommandTest : CommandTest {
    var command: DnDCommand? = null

    @BeforeEach
    fun setUp() {
        setUpCommonMocks()
        command = DnDCommand()
        Mockito.doReturn(webhookMessageCreateAction)
            .`when`(CommandTest.interactionHook)
            .sendMessageEmbeds(
                ArgumentMatchers.any(), anyVararg()

            )
    }

    @AfterEach
    fun tearDown() {
        tearDownCommonMocks()
    }

    @Test
    fun test_DnDCommandWithTypeAsSpell() {
        //Arrange
        val commandContext = CommandContext(CommandTest.event)
        val typeMapping = Mockito.mock(OptionMapping::class.java)
        val queryMapping = Mockito.mock(OptionMapping::class.java)
        Mockito.`when`<OptionMapping>(CommandTest.event.getOption("type")).thenReturn(typeMapping)
        Mockito.`when`<OptionMapping>(CommandTest.event.getOption("query")).thenReturn(queryMapping)
        Mockito.`when`(CommandTest.event.interaction)
            .thenReturn(CommandTest.event)
        val helper = Mockito.mock(HttpHelper::class.java)
        Mockito.`when`(helper.fetchFromGet(ArgumentMatchers.anyString())).thenReturn(spellJson)
        Mockito.`when`(typeMapping.asString).thenReturn("spells")
        Mockito.`when`(typeMapping.name).thenReturn("spell")
        Mockito.`when`(queryMapping.asString).thenReturn("fireball")


        //Act
        val typeOptionMapping = commandContext.event.getOption(DnDCommand.TYPE)
        command!!.handleWithHttpObjects(
            commandContext.event,
            typeOptionMapping!!.name,
            typeOptionMapping.asString,
            commandContext.event.getOption(DnDCommand.QUERY)!!
                .asString,
            helper,
            0
        )

        //Assert
        Mockito.verify(CommandTest.event, Mockito.times(1)).getOption("type")
        Mockito.verify(CommandTest.event, Mockito.times(1)).getOption("query")
        Mockito.verify(CommandTest.interactionHook, Mockito.times(1)).sendMessageEmbeds(
            ArgumentMatchers.any(
                MessageEmbed::class.java
            )
        )
        Mockito.verify(helper, Mockito.times(1)).fetchFromGet(ArgumentMatchers.any())
    }

    @Test
    fun test_DnDCommandWithTypeAsSpell_AndNothingIsReturnedForQuery() {
        //Arrange
        val commandContext = CommandContext(CommandTest.event)
        val typeMapping = Mockito.mock(OptionMapping::class.java)
        val queryMapping = Mockito.mock(OptionMapping::class.java)
        Mockito.`when`<OptionMapping>(CommandTest.event.getOption("type")).thenReturn(typeMapping)
        Mockito.`when`<OptionMapping>(CommandTest.event.getOption("query")).thenReturn(queryMapping)
        Mockito.`when`(CommandTest.event.interaction)
            .thenReturn(CommandTest.event)
        val helper = Mockito.mock(HttpHelper::class.java)
        Mockito.`when`(helper.fetchFromGet(ArgumentMatchers.anyString())).thenReturn("")
        Mockito.`when`(typeMapping.asString).thenReturn("spells")
        Mockito.`when`(typeMapping.name).thenReturn("spell")
        Mockito.`when`(queryMapping.asString).thenReturn("fireball")


        //Act
        val typeOptionMapping = commandContext.event.getOption(DnDCommand.TYPE)
        command!!.handleWithHttpObjects(
            commandContext.event,
            typeOptionMapping!!.name,
            typeOptionMapping.asString,
            commandContext.event.getOption(DnDCommand.QUERY)!!
                .asString,
            helper,
            0
        )

        //Assert
        Mockito.verify(CommandTest.event, Mockito.times(1)).getOption("type")
        Mockito.verify(CommandTest.event, Mockito.times(1)).getOption("query")
        Mockito.verify(helper, Mockito.times(2)).fetchFromGet(ArgumentMatchers.any())
        Mockito.verify(CommandTest.interactionHook, Mockito.times(0)).sendMessageEmbeds(
            ArgumentMatchers.any(
                MessageEmbed::class.java
            )
        )
        Mockito.verify(CommandTest.interactionHook, Mockito.times(1))
            .sendMessageFormat("Sorry, nothing was returned for %s '%s'", "spell", "fireball")
    }

    @Test
    fun test_DnDCommandWithTypeAsSpell_AndNothingIsSomethingIsReturnedForCloseMatchQuery() {
        //Arrange
        val commandContext = CommandContext(CommandTest.event)
        val typeMapping = Mockito.mock(OptionMapping::class.java)
        val queryMapping = Mockito.mock(OptionMapping::class.java)
        Mockito.`when`<OptionMapping>(CommandTest.event.getOption("type")).thenReturn(typeMapping)
        Mockito.`when`<OptionMapping>(CommandTest.event.getOption("query")).thenReturn(queryMapping)
        Mockito.`when`(CommandTest.event.interaction)
            .thenReturn(CommandTest.event)
        val helper = Mockito.mock(HttpHelper::class.java)
        Mockito.`when`(helper.fetchFromGet(String.format("https://www.dnd5eapi.co/api/%s/%s", "spells", "Fireball")))
            .thenReturn("")
        Mockito.`when`(typeMapping.asString).thenReturn("spells")
        Mockito.`when`(typeMapping.name).thenReturn("spell")
        Mockito.`when`(queryMapping.asString).thenReturn("Fireball")
        Mockito.`when`(
            helper.fetchFromGet(
                String.format(
                    "https://www.dnd5eapi.co/api/%s/%s",
                    "spells",
                    "?name=Fireball"
                )
            )
        ).thenReturn(
            """
                {"count":2,"results":[{"index":"delayed-blast-fireball","name":"Delayed Blast Fireball","url":"/api/spells/delayed-blast-fireball"},{"index":"fireball","name":"Fireball","url":"/api/spells/fireball"}]}
                """.trimIndent()
        )
        Mockito.`when`<WebhookMessageCreateAction<Message>>(
            webhookMessageCreateAction.addActionRow(
                ArgumentMatchers.any(
                    ItemComponent::class.java
                )
            ) as WebhookMessageCreateAction<Message>?
        ).thenReturn(webhookMessageCreateAction as WebhookMessageCreateAction<Message>?)


        //Act
        val typeOptionMapping = commandContext.event.getOption(DnDCommand.TYPE)
        command!!.handleWithHttpObjects(
            commandContext.event,
            typeOptionMapping!!.name,
            typeOptionMapping.asString,
            commandContext.event.getOption(DnDCommand.QUERY)!!
                .asString,
            helper,
            0
        )

        //Assert
        Mockito.verify(CommandTest.event, Mockito.times(1)).getOption("type")
        Mockito.verify(CommandTest.event, Mockito.times(1)).getOption("query")
        Mockito.verify(helper, Mockito.times(2)).fetchFromGet(ArgumentMatchers.any())
        Mockito.verify(CommandTest.interactionHook, Mockito.times(1)).sendMessageFormat(
            "Your query '%s' didn't return a value, but these close matches were found, please select one as appropriate",
            "Fireball"
        )
        Mockito.verify<WebhookMessageCreateAction<Message>>(
            webhookMessageCreateAction,
            Mockito.times(1)
        ).addActionRow(
            ArgumentMatchers.any(
                StringSelectMenu::class.java
            )
        )
    }

    @Test
    fun test_DnDCommandWithTypeAsCondition() {
        //Arrange
        val commandContext = CommandContext(CommandTest.event)
        val typeMapping = Mockito.mock(OptionMapping::class.java)
        val queryMapping = Mockito.mock(OptionMapping::class.java)
        Mockito.`when`<OptionMapping>(CommandTest.event.getOption("type")).thenReturn(typeMapping)
        Mockito.`when`<OptionMapping>(CommandTest.event.getOption("query")).thenReturn(queryMapping)
        Mockito.`when`(CommandTest.event.interaction)
            .thenReturn(CommandTest.event)
        val helper = Mockito.mock(HttpHelper::class.java)
        Mockito.`when`(helper.fetchFromGet(ArgumentMatchers.anyString())).thenReturn(conditionJson)
        Mockito.`when`(typeMapping.asString).thenReturn("conditions")
        Mockito.`when`(typeMapping.name).thenReturn("condition")
        Mockito.`when`(queryMapping.asString).thenReturn("grappled")


        //Act
        val typeOptionMapping = commandContext.event.getOption(DnDCommand.TYPE)
        command!!.handleWithHttpObjects(
            commandContext.event,
            typeOptionMapping!!.name,
            typeOptionMapping.asString,
            commandContext.event.getOption(DnDCommand.QUERY)!!
                .asString,
            helper,
            0
        )

        //Assert
        Mockito.verify(CommandTest.event, Mockito.times(1)).getOption("type")
        Mockito.verify(CommandTest.event, Mockito.times(1)).getOption("query")
        Mockito.verify(CommandTest.interactionHook, Mockito.times(1)).sendMessageEmbeds(
            ArgumentMatchers.any(
                MessageEmbed::class.java
            )
        )
        Mockito.verify(helper, Mockito.times(1)).fetchFromGet(ArgumentMatchers.any())
    }

    @Test
    fun test_DnDCommandWithTypeAsCondition_AndNothingIsReturnedForQuery() {
        //Arrange
        val commandContext = CommandContext(CommandTest.event)
        noQueryReturn("condition", "conditions", commandContext)
    }

    @Test
    fun test_DnDCommandWithTypeAsRule() {
        //Arrange
        val commandContext = CommandContext(CommandTest.event)
        val typeMapping = Mockito.mock(OptionMapping::class.java)
        val queryMapping = Mockito.mock(OptionMapping::class.java)
        Mockito.`when`<OptionMapping>(CommandTest.event.getOption("type")).thenReturn(typeMapping)
        Mockito.`when`<OptionMapping>(CommandTest.event.getOption("query")).thenReturn(queryMapping)
        Mockito.`when`(CommandTest.event.interaction)
            .thenReturn(CommandTest.event)
        val helper = Mockito.mock(HttpHelper::class.java)
        Mockito.`when`(helper.fetchFromGet(ArgumentMatchers.anyString())).thenReturn(ruleJson)
        Mockito.`when`(typeMapping.asString).thenReturn("rule-sections")
        Mockito.`when`(typeMapping.name).thenReturn("rule")
        Mockito.`when`(queryMapping.asString).thenReturn("cover")


        //Act
        val typeOptionMapping = commandContext.event.getOption(DnDCommand.TYPE)
        command!!.handleWithHttpObjects(
            commandContext.event,
            typeOptionMapping!!.name,
            typeOptionMapping.asString,
            commandContext.event.getOption(DnDCommand.QUERY)!!
                .asString,
            helper,
            0
        )

        //Assert
        Mockito.verify(CommandTest.event, Mockito.times(1)).getOption("type")
        Mockito.verify(CommandTest.event, Mockito.times(1)).getOption("query")
        Mockito.verify(CommandTest.interactionHook, Mockito.times(1)).sendMessageEmbeds(
            ArgumentMatchers.any(
                MessageEmbed::class.java
            )
        )
        Mockito.verify(helper, Mockito.times(1)).fetchFromGet(ArgumentMatchers.any())
    }

    @Test
    fun test_DnDCommandWithTypeAsRule_AndNothingIsReturnedForQuery() {
        //Arrange
        val commandContext = CommandContext(CommandTest.event)
        noQueryReturn("rule", "rule-sections", commandContext)
    }

    @Test
    fun test_DnDCommandWithTypeAsFeature() {
        //Arrange
        val commandContext = CommandContext(CommandTest.event)
        val typeMapping = Mockito.mock(OptionMapping::class.java)
        val queryMapping = Mockito.mock(OptionMapping::class.java)
        Mockito.`when`<OptionMapping>(CommandTest.event.getOption("type")).thenReturn(typeMapping)
        Mockito.`when`<OptionMapping>(CommandTest.event.getOption("query")).thenReturn(queryMapping)
        Mockito.`when`(CommandTest.event.interaction)
            .thenReturn(CommandTest.event)
        val helper = Mockito.mock(HttpHelper::class.java)
        Mockito.`when`(helper.fetchFromGet(ArgumentMatchers.anyString())).thenReturn(featureJson)
        Mockito.`when`(typeMapping.asString).thenReturn("features")
        Mockito.`when`(typeMapping.name).thenReturn("feature")
        Mockito.`when`(queryMapping.asString).thenReturn("action-surge-1-use")


        //Act
        val typeOptionMapping = commandContext.event.getOption(DnDCommand.TYPE)
        command!!.handleWithHttpObjects(
            commandContext.event,
            typeOptionMapping!!.name,
            typeOptionMapping.asString,
            commandContext.event.getOption(DnDCommand.QUERY)!!
                .asString,
            helper,
            0
        )

        //Assert
        Mockito.verify(CommandTest.event, Mockito.times(1)).getOption("type")
        Mockito.verify(CommandTest.event, Mockito.times(1)).getOption("query")
        Mockito.verify(CommandTest.interactionHook, Mockito.times(1)).sendMessageEmbeds(
            ArgumentMatchers.any(
                MessageEmbed::class.java
            )
        )
        Mockito.verify(helper, Mockito.times(1)).fetchFromGet(ArgumentMatchers.any())
    }

    @Test
    fun test_DnDCommandWithTypeAsFeature_AndNothingIsReturnedForQuery() {
        //Arrange
        val commandContext = CommandContext(CommandTest.event)
        noQueryReturn("feature", "features", commandContext)
    }

    private fun noQueryReturn(typeName: String, typeValue: String, commandContext: CommandContext) {
        val typeMapping = Mockito.mock(OptionMapping::class.java)
        val queryMapping = Mockito.mock(OptionMapping::class.java)
        Mockito.`when`<OptionMapping>(CommandTest.event.getOption("type")).thenReturn(typeMapping)
        Mockito.`when`<OptionMapping>(CommandTest.event.getOption("query")).thenReturn(queryMapping)
        Mockito.`when`(CommandTest.event.interaction)
            .thenReturn(CommandTest.event)
        val helper = Mockito.mock(HttpHelper::class.java)
        Mockito.`when`(helper.fetchFromGet(ArgumentMatchers.anyString())).thenReturn("")
        Mockito.`when`(typeMapping.asString).thenReturn(typeValue)
        Mockito.`when`(typeMapping.name).thenReturn(typeName)
        Mockito.`when`(queryMapping.asString).thenReturn("nerd")


        //Act
        val typeOptionMapping = commandContext.event.getOption(DnDCommand.TYPE)
        command!!.handleWithHttpObjects(
            commandContext.event,
            typeOptionMapping!!.name,
            typeOptionMapping.asString,
            commandContext.event.getOption(DnDCommand.QUERY)!!
                .asString,
            helper,
            0
        )

        //Assert
        Mockito.verify(CommandTest.event, Mockito.times(1)).getOption("type")
        Mockito.verify(CommandTest.event, Mockito.times(1)).getOption("query")
        Mockito.verify(CommandTest.interactionHook, Mockito.times(0)).sendMessageEmbeds(
            ArgumentMatchers.any(
                MessageEmbed::class.java
            )
        )
        Mockito.verify(helper, Mockito.times(2)).fetchFromGet(ArgumentMatchers.any())
        Mockito.verify(CommandTest.interactionHook, Mockito.times(1))
            .sendMessageFormat("Sorry, nothing was returned for %s '%s'", typeName, "nerd")
    }

    private val spellJson: String
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

    private val conditionJson: String
        get() = """
                {"index":"grappled","name":"Grappled","desc":["- A grappled creature's speed becomes 0, and it can't benefit from any bonus to its speed.","- The condition ends if the grappler is incapacitated (see the condition).","- The condition also ends if an effect removes the grappled creature from the reach of the grappler or grappling effect, such as when a creature is hurled away by the thunderwave spell."],"url":"/api/conditions/grappled"}
                """.trimIndent()

    private val ruleJson: String
        get() = """
                {"name":"Cover","index":"cover","desc":"## Cover\
                \
                Walls, trees, creatures, and other obstacles can provide cover during combat, making a target more difficult to harm. A target can benefit from cover only when an attack or other effect originates on the opposite side of the cover.\
                \
                There are three degrees of cover. If a target is behind multiple sources of cover, only the most protective degree of cover applies; the degrees aren't added together. For example, if a target is behind a creature that gives half cover and a tree trunk that gives three-quarters cover, the target has three-quarters cover.\
                \
                A target with **half cover** has a +2 bonus to AC and Dexterity saving throws. A target has half cover if an obstacle blocks at least half of its body. The obstacle might be a low wall, a large piece of furniture, a narrow tree trunk, or a creature, whether that creature is an enemy or a friend.\
                \
                A target with **three-quarters cover** has a +5 bonus to AC and Dexterity saving throws. A target has three-quarters cover if about three-quarters of it is covered by an obstacle. The obstacle might be a portcullis, an arrow slit, or a thick tree trunk.\
                \
                A target with **total cover** can't be targeted directly by an attack or a spell, although some spells can reach such a target by including it in an area of effect. A target has total cover if it is completely concealed by an obstacle.\
                ","url":"/api/rule-sections/cover"}
                """.trimIndent()

    private val featureJson: String
        get() = """
                {"index":"action-surge-1-use","class":{"index":"fighter","name":"Fighter","url":"/api/classes/fighter"},"name":"Action Surge (1 use)","level":2,"prerequisites":[],"desc":["Starting at 2nd level, you can push yourself beyond your normal limits for a moment. On your turn, you can take one additional action on top of your regular action and a possible bonus action.","Once you use this feature, you must finish a short or long rest before you can use it again. Starting at 17th level, you can use it twice before a rest, but only once on the same turn."],"url":"/api/features/action-surge-1-use"}
                """.trimIndent()
}