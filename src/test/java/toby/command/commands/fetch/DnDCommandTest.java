package toby.command.commands.fetch;

import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.components.ItemComponent;
import net.dv8tion.jda.api.interactions.components.selections.StringSelectMenu;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import toby.command.CommandContext;
import toby.command.commands.CommandTest;
import toby.helpers.HttpHelper;

import static org.mockito.Mockito.*;

class DnDCommandTest implements CommandTest {

    DnDCommand command;

    @BeforeEach
    void setUp() {
        setUpCommonMocks();
        command = new DnDCommand();
        doReturn(webhookMessageCreateAction)
                .when(interactionHook)
                .sendMessageEmbeds(any(), any(MessageEmbed[].class));

    }

    @AfterEach
    void tearDown() {
        tearDownCommonMocks();
    }

    @Test
    void test_DnDCommandWithTypeAsSpell() {
        //Arrange
        CommandContext commandContext = new CommandContext(event);
        OptionMapping typeMapping = mock(OptionMapping.class);
        OptionMapping queryMapping = mock(OptionMapping.class);
        when(event.getOption("type")).thenReturn(typeMapping);
        when(event.getOption("query")).thenReturn(queryMapping);
        when(event.getInteraction()).thenReturn(event);
        HttpHelper helper = mock(HttpHelper.class);
        when(helper.fetchFromGet(anyString())).thenReturn(getSpellJson());
        when(typeMapping.getAsString()).thenReturn("spells");
        when(typeMapping.getName()).thenReturn("spell");
        when(queryMapping.getAsString()).thenReturn("fireball");


        //Act
        OptionMapping typeOptionMapping = commandContext.getEvent().getOption(DnDCommand.TYPE);
        command.handleWithHttpObjects(commandContext.getEvent(), typeOptionMapping.getName(), typeOptionMapping.getAsString(), commandContext.getEvent().getOption(DnDCommand.QUERY).getAsString(), helper, 0);

        //Assert
        verify(event, times(1)).getOption("type");
        verify(event, times(1)).getOption("query");
        verify(interactionHook, times(1)).sendMessageEmbeds(any(MessageEmbed.class));
        verify(helper, times(1)).fetchFromGet(any());
    }
    @Test
    void test_DnDCommandWithTypeAsSpell_AndNothingIsReturnedForQuery() {
        //Arrange
        CommandContext commandContext = new CommandContext(event);
        OptionMapping typeMapping = mock(OptionMapping.class);
        OptionMapping queryMapping = mock(OptionMapping.class);
        when(event.getOption("type")).thenReturn(typeMapping);
        when(event.getOption("query")).thenReturn(queryMapping);
        when(event.getInteraction()).thenReturn(event);
        HttpHelper helper = mock(HttpHelper.class);
        when(helper.fetchFromGet(anyString())).thenReturn("");
        when(typeMapping.getAsString()).thenReturn("spells");
        when(typeMapping.getName()).thenReturn("spell");
        when(queryMapping.getAsString()).thenReturn("fireball");


        //Act
        OptionMapping typeOptionMapping = commandContext.getEvent().getOption(DnDCommand.TYPE);
        command.handleWithHttpObjects(commandContext.getEvent(), typeOptionMapping.getName(), typeOptionMapping.getAsString(), commandContext.getEvent().getOption(DnDCommand.QUERY).getAsString(), helper, 0);

        //Assert
        verify(event, times(1)).getOption("type");
        verify(event, times(1)).getOption("query");
        verify(helper, times(2)).fetchFromGet(any());
        verify(interactionHook, times(0)).sendMessageEmbeds(any(MessageEmbed.class));
        verify(interactionHook, times(1)).sendMessageFormat("Sorry, nothing was returned for %s '%s'", "spell", "fireball");
    }

    @Test
    void test_DnDCommandWithTypeAsSpell_AndNothingIsSomethingIsReturnedForCloseMatchQuery() {
        //Arrange
        CommandContext commandContext = new CommandContext(event);
        OptionMapping typeMapping = mock(OptionMapping.class);
        OptionMapping queryMapping = mock(OptionMapping.class);
        when(event.getOption("type")).thenReturn(typeMapping);
        when(event.getOption("query")).thenReturn(queryMapping);
        when(event.getInteraction()).thenReturn(event);
        HttpHelper helper = mock(HttpHelper.class);
        when(helper.fetchFromGet(String.format("https://www.dnd5eapi.co/api/%s/%s", "spells", "Fireball"))).thenReturn("");
        when(typeMapping.getAsString()).thenReturn("spells");
        when(typeMapping.getName()).thenReturn("spell");
        when(queryMapping.getAsString()).thenReturn("Fireball");
        when(helper.fetchFromGet(String.format("https://www.dnd5eapi.co/api/%s/%s", "spells", "?name=Fireball"))).thenReturn("""
                {"count":2,"results":[{"index":"delayed-blast-fireball","name":"Delayed Blast Fireball","url":"/api/spells/delayed-blast-fireball"},{"index":"fireball","name":"Fireball","url":"/api/spells/fireball"}]}""");
        when(webhookMessageCreateAction.addActionRow(any(ItemComponent.class))).thenReturn(webhookMessageCreateAction);


        //Act
        OptionMapping typeOptionMapping = commandContext.getEvent().getOption(DnDCommand.TYPE);
        command.handleWithHttpObjects(commandContext.getEvent(), typeOptionMapping.getName(), typeOptionMapping.getAsString(), commandContext.getEvent().getOption(DnDCommand.QUERY).getAsString(), helper, 0);

        //Assert
        verify(event, times(1)).getOption("type");
        verify(event, times(1)).getOption("query");
        verify(helper, times(2)).fetchFromGet(any());
        verify(interactionHook, times(1)).sendMessageFormat("Your query '%s' didn't return a value, but these close matches were found, please select one as appropriate", "Fireball");
        verify(webhookMessageCreateAction, times(1)).addActionRow(any(StringSelectMenu.class));
    }

    @Test
    void test_DnDCommandWithTypeAsCondition() {
        //Arrange
        CommandContext commandContext = new CommandContext(event);
        OptionMapping typeMapping = mock(OptionMapping.class);
        OptionMapping queryMapping = mock(OptionMapping.class);
        when(event.getOption("type")).thenReturn(typeMapping);
        when(event.getOption("query")).thenReturn(queryMapping);
        when(event.getInteraction()).thenReturn(event);
        HttpHelper helper = mock(HttpHelper.class);
        when(helper.fetchFromGet(anyString())).thenReturn(getConditionJson());
        when(typeMapping.getAsString()).thenReturn("conditions");
        when(typeMapping.getName()).thenReturn("condition");
        when(queryMapping.getAsString()).thenReturn("grappled");


        //Act
        OptionMapping typeOptionMapping = commandContext.getEvent().getOption(DnDCommand.TYPE);
        command.handleWithHttpObjects(commandContext.getEvent(), typeOptionMapping.getName(), typeOptionMapping.getAsString(), commandContext.getEvent().getOption(DnDCommand.QUERY).getAsString(), helper, 0);

        //Assert
        verify(event, times(1)).getOption("type");
        verify(event, times(1)).getOption("query");
        verify(interactionHook, times(1)).sendMessageEmbeds(any(MessageEmbed.class));
        verify(helper, times(1)).fetchFromGet(any());
    }

    @Test
    void test_DnDCommandWithTypeAsCondition_AndNothingIsReturnedForQuery() {
        //Arrange
        CommandContext commandContext = new CommandContext(event);
        noQueryReturn("conditions", "condition", commandContext);
    }

    @Test
    void test_DnDCommandWithTypeAsRule() {
        //Arrange
        CommandContext commandContext = new CommandContext(event);
        OptionMapping typeMapping = mock(OptionMapping.class);
        OptionMapping queryMapping = mock(OptionMapping.class);
        when(event.getOption("type")).thenReturn(typeMapping);
        when(event.getOption("query")).thenReturn(queryMapping);
        when(event.getInteraction()).thenReturn(event);
        HttpHelper helper = mock(HttpHelper.class);
        when(helper.fetchFromGet(anyString())).thenReturn(getRuleJson());
        when(typeMapping.getAsString()).thenReturn("rule-sections");
        when(typeMapping.getName()).thenReturn("rule");
        when(queryMapping.getAsString()).thenReturn("cover");


        //Act
        OptionMapping typeOptionMapping = commandContext.getEvent().getOption(DnDCommand.TYPE);
        command.handleWithHttpObjects(commandContext.getEvent(), typeOptionMapping.getName(), typeOptionMapping.getAsString(), commandContext.getEvent().getOption(DnDCommand.QUERY).getAsString(), helper, 0);

        //Assert
        verify(event, times(1)).getOption("type");
        verify(event, times(1)).getOption("query");
        verify(interactionHook, times(1)).sendMessageEmbeds(any(MessageEmbed.class));
        verify(helper, times(1)).fetchFromGet(any());
    }

    @Test
    void test_DnDCommandWithTypeAsRule_AndNothingIsReturnedForQuery() {
        //Arrange
        CommandContext commandContext = new CommandContext(event);
        noQueryReturn("rule-sections", "rule", commandContext);
    }

    private void noQueryReturn(String conditions, String condition, CommandContext commandContext) {
        OptionMapping typeMapping = mock(OptionMapping.class);
        OptionMapping queryMapping = mock(OptionMapping.class);
        when(event.getOption("type")).thenReturn(typeMapping);
        when(event.getOption("query")).thenReturn(queryMapping);
        when(event.getInteraction()).thenReturn(event);
        HttpHelper helper = mock(HttpHelper.class);
        when(helper.fetchFromGet(anyString())).thenReturn("");
        when(typeMapping.getAsString()).thenReturn(conditions);
        when(typeMapping.getName()).thenReturn(condition);
        when(queryMapping.getAsString()).thenReturn("nerd");


        //Act
        OptionMapping typeOptionMapping = commandContext.getEvent().getOption(DnDCommand.TYPE);
        command.handleWithHttpObjects(commandContext.getEvent(), typeOptionMapping.getName(), typeOptionMapping.getAsString(), commandContext.getEvent().getOption(DnDCommand.QUERY).getAsString(), helper, 0);

        //Assert
        verify(event, times(1)).getOption("type");
        verify(event, times(1)).getOption("query");
        verify(interactionHook, times(0)).sendMessageEmbeds(any(MessageEmbed.class));
        verify(helper, times(2)).fetchFromGet(any());
        verify(interactionHook, times(1)).sendMessageFormat("Sorry, nothing was returned for %s '%s'", condition, "nerd");
    }

    private String getSpellJson() {
        return """
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
    }

    private String getConditionJson(){
        return """
                {"index":"grappled","name":"Grappled","desc":["- A grappled creature's speed becomes 0, and it can't benefit from any bonus to its speed.","- The condition ends if the grappler is incapacitated (see the condition).","- The condition also ends if an effect removes the grappled creature from the reach of the grappler or grappling effect, such as when a creature is hurled away by the thunderwave spell."],"url":"/api/conditions/grappled"}""";
    }

    private String getRuleJson(){
        return """
                {"name":"Cover","index":"cover","desc":"## Cover\\n\\nWalls, trees, creatures, and other obstacles can provide cover during combat, making a target more difficult to harm. A target can benefit from cover only when an attack or other effect originates on the opposite side of the cover.\\n\\nThere are three degrees of cover. If a target is behind multiple sources of cover, only the most protective degree of cover applies; the degrees aren't added together. For example, if a target is behind a creature that gives half cover and a tree trunk that gives three-quarters cover, the target has three-quarters cover.\\n\\nA target with **half cover** has a +2 bonus to AC and Dexterity saving throws. A target has half cover if an obstacle blocks at least half of its body. The obstacle might be a low wall, a large piece of furniture, a narrow tree trunk, or a creature, whether that creature is an enemy or a friend.\\n\\nA target with **three-quarters cover** has a +5 bonus to AC and Dexterity saving throws. A target has three-quarters cover if about three-quarters of it is covered by an obstacle. The obstacle might be a portcullis, an arrow slit, or a thick tree trunk.\\n\\nA target with **total cover** can't be targeted directly by an attack or a spell, although some spells can reach such a target by including it in an area of effect. A target has total cover if it is completely concealed by an obstacle.\\n","url":"/api/rule-sections/cover"}""";
    }

}