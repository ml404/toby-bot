package toby.command.commands.fetch

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.interactions.commands.OptionMapping
import net.dv8tion.jda.api.interactions.components.selections.StringSelectMenu
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import toby.command.CommandContext
import toby.command.CommandTest
import toby.command.commands.dnd.DnDCommand
import toby.helpers.HttpHelper

internal class DnDCommandTest : CommandTest {
    lateinit var command: DnDCommand

    @BeforeEach
    fun setUp() {
        setUpCommonMocks()
        command = DnDCommand()
        every {
            CommandTest.interactionHook.sendMessageEmbeds(
                any<MessageEmbed>(),
                *anyVararg()
            )
        } returns CommandTest.webhookMessageCreateAction
    }

    @AfterEach
    fun tearDown() {
        tearDownCommonMocks()
    }

    @Test
    fun test_DnDCommandWithTypeAsSpell() {
        //Arrange
        val commandContext = CommandContext(CommandTest.event)
        val typeMapping = mockk<OptionMapping>()
        val queryMapping = mockk<OptionMapping>()
        every { CommandTest.event.getOption("type") } returns typeMapping
        every { CommandTest.event.getOption("query") } returns queryMapping
        every { CommandTest.event.interaction } returns CommandTest.event
        val helper = mockk<HttpHelper>()
        every { helper.fetchFromGet(any()) } returns spellJson
        every { typeMapping.asString } returns "spells"
        every { typeMapping.name } returns "spell"
        every { queryMapping.asString } returns "fireball"

        //Act
        val typeOptionMapping = commandContext.event.getOption(DnDCommand.TYPE)
        command.handleWithHttpObjects(
            commandContext.event,
            typeOptionMapping!!.name,
            typeOptionMapping.asString,
            commandContext.event.getOption(DnDCommand.QUERY)!!.asString,
            helper,
            0
        )

        //Assert
        verify(exactly = 1) { CommandTest.event.getOption("type") }
        verify(exactly = 1) { CommandTest.event.getOption("query") }
        verify(exactly = 1) { CommandTest.interactionHook.sendMessageEmbeds(any<MessageEmbed>()) }
        verify(exactly = 1) { helper.fetchFromGet(any()) }
    }

    @Test
    fun test_DnDCommandWithTypeAsSpell_AndNothingIsReturnedForQuery() {
        //Arrange
        val commandContext = CommandContext(CommandTest.event)
        val typeMapping = mockk<OptionMapping>()
        val queryMapping = mockk<OptionMapping>()
        every { CommandTest.event.getOption("type") } returns typeMapping
        every { CommandTest.event.getOption("query") } returns queryMapping
        every { CommandTest.event.interaction } returns CommandTest.event
        val helper = mockk<HttpHelper>()
        every { helper.fetchFromGet(any()) } returns ""
        every { typeMapping.asString } returns "spells"
        every { typeMapping.name } returns "spell"
        every { queryMapping.asString } returns "fireball"

        //Act
        val typeOptionMapping = commandContext.event.getOption(DnDCommand.TYPE)
        command.handleWithHttpObjects(
            commandContext.event,
            typeOptionMapping!!.name,
            typeOptionMapping.asString,
            commandContext.event.getOption(DnDCommand.QUERY)!!.asString,
            helper,
            0
        )

        //Assert
        verify(exactly = 1) { CommandTest.event.getOption("type") }
        verify(exactly = 1) { CommandTest.event.getOption("query") }
        verify(exactly = 2) { helper.fetchFromGet(any()) }
        verify(exactly = 0) { CommandTest.interactionHook.sendMessageEmbeds(any<MessageEmbed>()) }
        verify(exactly = 1) {
            CommandTest.interactionHook.sendMessageFormat(
                "Sorry, nothing was returned for %s '%s'",
                "spell",
                "fireball"
            )
        }
    }

    @Test
    fun test_DnDCommandWithTypeAsSpell_AndSomethingIsReturnedForCloseMatchQuery() {
        //Arrange
        val commandContext = CommandContext(CommandTest.event)
        val typeMapping = mockk<OptionMapping>()
        val queryMapping = mockk<OptionMapping>()
        every { CommandTest.event.getOption("type") } returns typeMapping
        every { CommandTest.event.getOption("query") } returns queryMapping
        every { CommandTest.event.interaction } returns CommandTest.event
        val helper = mockk<HttpHelper>()
        every { helper.fetchFromGet("https://www.dnd5eapi.co/api/spells/Fireball") } returns ""
        every { typeMapping.asString } returns "spells"
        every { typeMapping.name } returns "spell"
        every { queryMapping.asString } returns "Fireball"
        every { helper.fetchFromGet("https://www.dnd5eapi.co/api/spells?name=Fireball") } returns """
            {"count":2,"results":[{"index":"delayed-blast-fireball","name":"Delayed Blast Fireball","url":"/api/spells/delayed-blast-fireball"},{"index":"fireball","name":"Fireball","url":"/api/spells/fireball"}]}
        """.trimIndent()
        every { CommandTest.webhookMessageCreateAction.addActionRow(any<StringSelectMenu>()) } returns CommandTest.webhookMessageCreateAction

        //Act
        val typeOptionMapping = commandContext.event.getOption(DnDCommand.TYPE)
        command.handleWithHttpObjects(
            commandContext.event,
            typeOptionMapping!!.name,
            typeOptionMapping.asString,
            commandContext.event.getOption(DnDCommand.QUERY)!!.asString,
            helper,
            0
        )

        //Assert
        verify(exactly = 1) { CommandTest.event.getOption("type") }
        verify(exactly = 1) { CommandTest.event.getOption("query") }
        verify(exactly = 2) { helper.fetchFromGet(any()) }
        verify(exactly = 1) {
            CommandTest.interactionHook.sendMessageFormat(
                "Your query '%s' didn't return a value, but these close matches were found, please select one as appropriate",
                "Fireball"
            )
        }
        verify(exactly = 1) { CommandTest.webhookMessageCreateAction.addActionRow(any<StringSelectMenu>()) }
    }

    @Test
    fun test_DnDCommandWithTypeAsCondition() {
        //Arrange
        val commandContext = CommandContext(CommandTest.event)
        val typeMapping = mockk<OptionMapping>()
        val queryMapping = mockk<OptionMapping>()
        every { CommandTest.event.getOption("type") } returns typeMapping
        every { CommandTest.event.getOption("query") } returns queryMapping
        every { CommandTest.event.interaction } returns CommandTest.event
        val helper = mockk<HttpHelper>()
        every { helper.fetchFromGet(any()) } returns conditionJson
        every { typeMapping.asString } returns "conditions"
        every { typeMapping.name } returns "condition"
        every { queryMapping.asString } returns "grappled"

        //Act
        val typeOptionMapping = commandContext.event.getOption(DnDCommand.TYPE)
        command.handleWithHttpObjects(
            commandContext.event,
            typeOptionMapping!!.name,
            typeOptionMapping.asString,
            commandContext.event.getOption(DnDCommand.QUERY)!!.asString,
            helper,
            0
        )

        //Assert
        verify(exactly = 1) { CommandTest.event.getOption("type") }
        verify(exactly = 1) { CommandTest.event.getOption("query") }
        verify(exactly = 1) { CommandTest.interactionHook.sendMessageEmbeds(any<MessageEmbed>()) }
        verify(exactly = 1) { helper.fetchFromGet(any()) }
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
        val typeMapping = mockk<OptionMapping>()
        val queryMapping = mockk<OptionMapping>()
        every { CommandTest.event.getOption("type") } returns typeMapping
        every { CommandTest.event.getOption("query") } returns queryMapping
        every { CommandTest.event.interaction } returns CommandTest.event
        val helper = mockk<HttpHelper>()
        every { helper.fetchFromGet(any()) } returns ruleJson
        every { typeMapping.asString } returns "rule-sections"
        every { typeMapping.name } returns "rule"
        every { queryMapping.asString } returns "cover"

        //Act
        val typeOptionMapping = commandContext.event.getOption(DnDCommand.TYPE)
        command.handleWithHttpObjects(
            commandContext.event,
            typeOptionMapping!!.name,
            typeOptionMapping.asString,
            commandContext.event.getOption(DnDCommand.QUERY)!!.asString,
            helper,
            0
        )

        //Assert
        verify(exactly = 1) { CommandTest.event.getOption("type") }
        verify(exactly = 1) { CommandTest.event.getOption("query") }
        verify(exactly = 1) { CommandTest.interactionHook.sendMessageEmbeds(any<MessageEmbed>()) }
        verify(exactly = 1) { helper.fetchFromGet(any()) }
    }

    @Test
    fun test_DnDCommandWithTypeAsRule_AndNothingIsReturnedForQuery() {
        //Arrange
        val commandContext = CommandContext(CommandTest.event)
        noQueryReturn("rule", "rule-sections", commandContext)
    }

    @Test
    fun test_DnDCommandWithTypeAsRule_AndSomethingIsReturnedForCloseMatchQuery() {
        //Arrange
        val commandContext = CommandContext(CommandTest.event)
        val typeMapping = mockk<OptionMapping>()
        val queryMapping = mockk<OptionMapping>()
        every { CommandTest.event.getOption("type") } returns typeMapping
        every { CommandTest.event.getOption("query") } returns queryMapping
        every { CommandTest.event.interaction } returns CommandTest.event
        val helper = mockk<HttpHelper>()
        every { helper.fetchFromGet("https://www.dnd5eapi.co/api/rule-sections/cover") } returns ""
        every { typeMapping.asString } returns "rule-sections"
        every { typeMapping.name } returns "rule"
        every { queryMapping.asString } returns "cover"
        every { helper.fetchFromGet("https://www.dnd5eapi.co/api/rule-sections?name=cover") } returns """
            {"count":2,"results":[{"index":"cover","name":"Cover","url":"/api/rule-sections/cover"},{"index":"half-cover","name":"Half Cover","url":"/api/rule-sections/half-cover"}]}
        """.trimIndent()
        every { CommandTest.webhookMessageCreateAction.addActionRow(any<StringSelectMenu>()) } returns CommandTest.webhookMessageCreateAction

        //Act
        val typeOptionMapping = commandContext.event.getOption(DnDCommand.TYPE)
        command.handleWithHttpObjects(
            commandContext.event,
            typeOptionMapping!!.name,
            typeOptionMapping.asString,
            commandContext.event.getOption(DnDCommand.QUERY)!!.asString,
            helper,
            0
        )

        //Assert
        verify(exactly = 1) { CommandTest.event.getOption("type") }
        verify(exactly = 1) { CommandTest.event.getOption("query") }
        verify(exactly = 2) { helper.fetchFromGet(any()) }
        verify(exactly = 1) {
            CommandTest.interactionHook.sendMessageFormat(
                "Your query '%s' didn't return a value, but these close matches were found, please select one as appropriate",
                "cover"
            )
        }
        verify(exactly = 1) { CommandTest.webhookMessageCreateAction.addActionRow(any<StringSelectMenu>()) }
    }

    private fun noQueryReturn(typeName: String, typeOption: String, commandContext: CommandContext) {
        val typeMapping = mockk<OptionMapping>()
        val queryMapping = mockk<OptionMapping>()
        every { CommandTest.event.getOption("type") } returns typeMapping
        every { CommandTest.event.getOption("query") } returns queryMapping
        every { CommandTest.event.interaction } returns CommandTest.event
        val helper = mockk<HttpHelper>()
        every { helper.fetchFromGet(any()) } returns ""
        every { typeMapping.asString } returns typeOption
        every { typeMapping.name } returns typeName
        every { queryMapping.asString } returns "fireball"

        //Act
        val typeOptionMapping = commandContext.event.getOption(DnDCommand.TYPE)
        command.handleWithHttpObjects(
            commandContext.event,
            typeOptionMapping!!.name,
            typeOptionMapping.asString,
            commandContext.event.getOption(DnDCommand.QUERY)!!.asString,
            helper,
            0
        )

        //Assert
        verify(exactly = 1) { CommandTest.event.getOption("type") }
        verify(exactly = 1) { CommandTest.event.getOption("query") }
        verify(exactly = 2) { helper.fetchFromGet(any()) }
        verify(exactly = 0) { CommandTest.interactionHook.sendMessageEmbeds(any<MessageEmbed>()) }
        verify(exactly = 1) {
            CommandTest.interactionHook.sendMessageFormat(
                "Sorry, nothing was returned for %s '%s'",
                typeName,
                "fireball"
            )
        }
    }

    companion object {
        private const val spellJson = """
            {
                "index": "fireball",
                "name": "Fireball",
                "desc": [
                    "A bright streak flashes from your pointing finger to a point you choose within range and then blossoms with a low roar into an explosion of flame."
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
                "attack_type": "ranged",
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
        """

        private const val conditionJson = """
            {
                "index": "grappled",
                "name": "Grappled",
                "desc": [
                    "A grappled creature’s speed becomes 0, and it can’t benefit from any bonus to its speed.",
                    "The condition ends if the grappler is incapacitated (see the condition).",
                    "The condition also ends if an effect removes the grappled creature from the reach of the grappler or grappling effect, such as when a creature is hurled away by the thunderwave spell."
                ],
                "url": "/api/conditions/grappled"
            }
        """

        private const val ruleJson = """
            {
                "index": "cover",
                "name": "Cover",
                "desc": [
                    "Walls, trees, creatures, and other obstacles can provide cover during combat, making a target more difficult to harm. A target can benefit from cover only when an attack or other effect originates on the opposite side of the cover.",
                    "There are three degrees of cover. If a target is behind multiple sources of cover, only the most protective degree of cover applies; the degrees aren’t added together. For example, if a target is behind a creature that gives half cover and a tree trunk that gives three-quarters cover, the target has three-quarters cover.",
                    "A target with half cover has a +2 bonus to AC and Dexterity saving throws. A target has half cover if an obstacle blocks at least half of its body. The obstacle might be a low wall, a large piece of furniture, a narrow tree trunk, or a creature, whether that creature is an enemy or a friend.",
                    "A target with three-quarters cover has a +5 bonus to AC and Dexterity saving throws. A target has three-quarters cover if about three-quarters of it is covered by an obstacle. The obstacle might be a portcullis, an arrow slit, or a thick tree trunk.",
                    "A target with total cover can’t be targeted directly by an attack or a spell, although some spells can reach such a target by including it in an area of effect. A target has total cover if it is completely concealed by an obstacle."
                ],
                "url": "/api/rule-sections/cover"
            }
        """
    }
}
