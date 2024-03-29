package toby.command.commands.dnd;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.components.selections.SelectOption;
import net.dv8tion.jda.api.interactions.components.selections.StringSelectMenu;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.VisibleForTesting;
import toby.command.CommandContext;
import toby.command.commands.fetch.IFetchCommand;
import toby.dto.web.dnd.Feature;
import toby.dto.web.dnd.Information;
import toby.dto.web.dnd.Rule;
import toby.dto.web.dnd.spell.ApiInfo;
import toby.dto.web.dnd.spell.Dc;
import toby.dto.web.dnd.spell.QueryResult;
import toby.dto.web.dnd.spell.Spell;
import toby.helpers.HttpHelper;
import toby.helpers.JsonParser;
import toby.jpa.dto.UserDto;

import java.util.List;
import java.util.Map;

import static toby.command.ICommand.invokeDeleteOnMessageResponse;

public class DnDCommand implements IDnDCommand, IFetchCommand{

    public static final String DND_5_API_URL = "https://www.dnd5eapi.co/api/%s/%s";
    public static final String TYPE = "type";
    public static final String QUERY = "query";
    public static final String SPELL_NAME = "spell";
    public static final String CONDITION_NAME = "condition";
    public static final String RULE_NAME = "rule";
    public static final String FEATURE_NAME = "feature";

    @Override
    public void handle(CommandContext ctx, UserDto requestingUserDto, Integer deleteDelay) {
        OptionMapping typeOptionMapping = ctx.getEvent().getOption(TYPE);
        handleWithHttpObjects(ctx.getEvent(), getName(typeOptionMapping), typeOptionMapping.getAsString(), ctx.getEvent().getOption(QUERY).getAsString(), new HttpHelper(), deleteDelay);

    }

    @NotNull
    private static String getName(OptionMapping typeOptionMapping) {
        switch (typeOptionMapping.getAsString()) {
            case "spells" -> { return SPELL_NAME; }
            case "conditions" -> { return CONDITION_NAME; }
            case "rule-sections" -> { return RULE_NAME; }
            case "features" -> { return FEATURE_NAME; }
        }
        return "";
    }

    @VisibleForTesting
    public void handleWithHttpObjects(SlashCommandInteractionEvent event, String typeName, String typeValue, String query, HttpHelper httpHelper, Integer deleteDelay) {
        event.deferReply().queue();
        doLookUpAndReply(event.getHook(), typeName, typeValue, query, httpHelper, deleteDelay);

    }

    public static void doLookUpAndReply(InteractionHook hook, String typeName, String typeValue, String query, HttpHelper httpHelper, Integer deleteDelay) {
        String url = String.format(DND_5_API_URL, typeValue, replaceSpaceWithDash(query));
        String responseData = httpHelper.fetchFromGet(url);
        switch (typeName) {
            case SPELL_NAME -> {
                Spell spell = JsonParser.parseJSONToSpell(responseData);
                if (spell != null) {
                    EmbedBuilder spellEmbed = createEmbedFromSpell(spell);
                    hook.sendMessageEmbeds(spellEmbed.build()).queue();
                } else {
                    queryNonMatchRetry(hook, typeName, typeValue, query, httpHelper, deleteDelay);
                }
            }
            case CONDITION_NAME -> {
                Information information = JsonParser.parseJsonToInformation(responseData);
                if (information != null) {
                    EmbedBuilder conditionEmbed = createEmbedFromInformation(information);
                    hook.sendMessageEmbeds(conditionEmbed.build()).queue();
                } else {
                    queryNonMatchRetry(hook, typeName, typeValue, query, httpHelper, deleteDelay);
                }
            }
            case RULE_NAME -> {
                Rule rule = JsonParser.parseJsonToRule(responseData);
                if (rule != null) {
                    EmbedBuilder conditionEmbed = createEmbedFromRule(rule);
                    hook.sendMessageEmbeds(conditionEmbed.build()).queue();
                } else {
                    queryNonMatchRetry(hook, typeName, typeValue, query, httpHelper, deleteDelay);
                }
            }
            case FEATURE_NAME -> {
                Feature feature = JsonParser.parseJsonToFeature(responseData);
                if (feature != null) {
                    EmbedBuilder conditionEmbed = createEmbedFromFeature(feature);
                    hook.sendMessageEmbeds(conditionEmbed.build()).queue();
                } else {
                    queryNonMatchRetry(hook, typeName, typeValue, query, httpHelper, deleteDelay);
                }
            }
            default -> hook.sendMessage("Something went wrong.").queue(invokeDeleteOnMessageResponse(deleteDelay));
        }
    }

    private static void queryNonMatchRetry(InteractionHook hook, String typeName, String typeValue, String query, HttpHelper httpHelper, Integer deleteDelay) {
        String queryResponseData = httpHelper.fetchFromGet(String.format(DND_5_API_URL, typeValue, "?name=" + replaceSpaceWithUrlEncode(query)));
        QueryResult queryResult = JsonParser.parseJsonToQueryResult(queryResponseData);
        if (queryResult != null && queryResult.count() > 0) {
            StringSelectMenu.Builder builder = StringSelectMenu.create(String.format("dnd:%s", typeName)).setPlaceholder("Choose an option");
            queryResult.results().forEach(info -> builder.addOptions(SelectOption.of(info.index(), info.index())));
            hook.sendMessageFormat("Your query '%s' didn't return a value, but these close matches were found, please select one as appropriate", query)
                    .addActionRow(builder.build())
                    .queue();

        } else {
            hook.sendMessageFormat("Sorry, nothing was returned for %s '%s'", typeName, query).queue(invokeDeleteOnMessageResponse(deleteDelay));
        }
    }


    public static EmbedBuilder createEmbedFromSpell(Spell spell) {
        EmbedBuilder embedBuilder = new EmbedBuilder();

        if (spell.name() != null) {
            embedBuilder.setTitle(spell.name());
        }

        if (spell.desc() != null && !spell.desc().isEmpty()) {
            embedBuilder.setDescription(transformListToString(spell.desc()));
        }
        if (!spell.higherLevel().isEmpty()) {
            embedBuilder.addField("Higher Level", transformListToString(spell.higherLevel()), false);

        }
        if (spell.range() != null) {
            String meterValue = (spell.range().equals("Touch")) ? "Touch" : transformToMeters(Integer.parseInt(spell.range().split(" ")[0])) + "m";
            embedBuilder.addField("Range", meterValue, true);
        }

        if (spell.components() != null && !spell.components().isEmpty()) {
            embedBuilder.addField("Components", String.join(", ", spell.components()), true);
        }

        if (!spell.duration().isEmpty()) {
            embedBuilder.addField("Duration", spell.duration(), true);
        }

        embedBuilder.addField("Concentration", String.valueOf(spell.concentration()), true);

        if (!spell.castingTime().isEmpty()) {
            embedBuilder.addField("Casting Time", spell.castingTime(), true);
        }

        if (spell.level() > 0) {
            embedBuilder.addField("Level", String.valueOf(spell.level()), true);
        }

        if (spell.damage() != null) {
            StringBuilder damageInfo = new StringBuilder();
            damageInfo.append("Damage Type: ").append(spell.damage().damageType().name()).append("\n");

            // Add damage at slot level information
            Map<String, String> damageAtSlotLevel = spell.damage().damageAtSlotLevel();
            if(damageAtSlotLevel!=null) {
                damageInfo.append("Damage at Slot Level:\n");
                for (Map.Entry<String, String> entry : damageAtSlotLevel.entrySet()) {
                    damageInfo.append("Level ").append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
                }
            }

            embedBuilder.addField("Damage Info", damageInfo.toString(), true);
        }

        Dc dc = spell.dc();
        if (dc != null) {
            embedBuilder.addField("DC Type", dc.dcType().name(), true);
            if (dc.dcSuccess() != null) {
                embedBuilder.addField("DC Success", dc.dcSuccess(), true);
            }
        }

        if (spell.areaOfEffect() != null) {
            embedBuilder.addField("Area of Effect", "Type: " + spell.areaOfEffect().type() + ", Size: " + transformToMeters(spell.areaOfEffect().size()) + "m", true);
        }

        if (spell.school() != null) {
            embedBuilder.addField("School", spell.school().name(), true);
        }

        List<ApiInfo> spellClasses = spell.classes();
        if (spellClasses != null && !spellClasses.isEmpty()) {
            StringBuilder classesInfo = new StringBuilder();
            for (ApiInfo classInfo : spellClasses) {
                classesInfo.append(classInfo.name()).append("\n");
            }
            embedBuilder.addField("Classes", classesInfo.toString(), true);
        }

        List<ApiInfo> subclasses = spell.subclasses();
        if (subclasses != null && !subclasses.isEmpty()) {
            StringBuilder subclassesInfo = new StringBuilder();
            for (ApiInfo subclassInfo : subclasses) {
                subclassesInfo.append(subclassInfo.name()).append("\n");
            }
            embedBuilder.addField("Subclasses", subclassesInfo.toString(), true);
        }

        if (spell.url() != null) {
            embedBuilder.setUrl("https://www.dndbeyond.com/" + spell.url().replace("/api/", ""));
        }

        embedBuilder.setColor(0x42f5a7);

        return embedBuilder;
    }

    private static EmbedBuilder createEmbedFromInformation(Information information) {
        EmbedBuilder embedBuilder = new EmbedBuilder();

        if (information.name() != null) {
            embedBuilder.setTitle(information.name());
        }

        if (information.desc() != null && !information.desc().isEmpty()) {
            embedBuilder.setDescription(transformListToString(information.desc()));
        }

        embedBuilder.setColor(0x42f5a7);
        return embedBuilder;
    }

    private static EmbedBuilder createEmbedFromRule(Rule rule) {
        EmbedBuilder embedBuilder = new EmbedBuilder();

        if (rule.name() != null) {
            embedBuilder.setTitle(rule.name());
        }

        if (rule.desc() != null && !rule.desc().isEmpty()) {
            embedBuilder.setDescription(rule.desc());
        }

        embedBuilder.setColor(0x42f5a7);
        return embedBuilder;
    }

    private static EmbedBuilder createEmbedFromFeature(Feature feature) {
        EmbedBuilder embedBuilder = new EmbedBuilder();

        if (feature.name() != null) {
            embedBuilder.setTitle(feature.name());
        }

        if (feature.desc() != null && !feature.desc().isEmpty()) {
            embedBuilder.setDescription(transformListToString(feature.desc()));
        }

        if (feature.classInfo() != null) {
            embedBuilder.addField("Class", feature.classInfo().name(), true);
        }

        if (feature.level() > 0) {
            embedBuilder.addField("Level", String.valueOf(feature.level()), true);
        }

        if (feature.prerequisites().size() > 0) {
            embedBuilder.addField("Prerequisites", transformListToString(feature.prerequisites()), false);
        }

        embedBuilder.setColor(0x42f5a7);
        return embedBuilder;
    }

    @NotNull
    private static String transformListToString(List<String> feature) {
        return feature.stream().reduce((s1, s2) -> String.join("\n", s1, s2)).get();
    }

    @NotNull
    private static String replaceSpaceWithDash(String query) {
        return query.replace(" ", "-");
    }

    @NotNull
    private static String replaceSpaceWithUrlEncode(String query) {
        return query.replace(" ", "%20");
    }

    private static String transformToMeters(int rangeNumber) {
        return String.valueOf(Math.round((double) rangeNumber / 3.28));
    }

    @Override
    public String getName() {
        return "dnd";
    }

    @Override
    public String getDescription() {
        return "Use this command to do lookups on various things from DnD";
    }

    @Override
    public List<OptionData> getOptionData() {
        OptionData type = new OptionData(OptionType.STRING, TYPE, "What type of are you looking up", true);
        type.addChoice(SPELL_NAME, "spells");
        type.addChoice(CONDITION_NAME, "conditions");
        type.addChoice(RULE_NAME, "rule-sections");
        type.addChoice(FEATURE_NAME, "features");
        OptionData query = new OptionData(OptionType.STRING, QUERY, "What is the thing you are looking up?", true);
        return List.of(type, query);
    }

}
