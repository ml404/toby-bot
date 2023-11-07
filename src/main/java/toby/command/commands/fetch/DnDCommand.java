package toby.command.commands.fetch;

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
import toby.dto.web.dnd.information.Information;
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

public class DnDCommand implements IFetchCommand {


    public static final String DND_5_API_URL = "https://www.dnd5eapi.co/api/%s/%s";
    public static final String TYPE = "type";
    public static final String QUERY = "query";
    public static final String SPELL_NAME = "spell";
    public static final String CONDITION_NAME = "condition";
    public static final String RULE_NAME = "rule";

    @Override
    public void handle(CommandContext ctx, UserDto requestingUserDto, Integer deleteDelay) {
        OptionMapping typeOptionMapping = ctx.getEvent().getOption(TYPE);
        handleWithHttpObjects(ctx.getEvent(), getName(typeOptionMapping), typeOptionMapping.getAsString(), ctx.getEvent().getOption(QUERY).getAsString(), new HttpHelper(), deleteDelay);

    }

    @NotNull
    private static String getName(OptionMapping typeOptionMapping) {
        switch (typeOptionMapping.getAsString()) {
            case "spells" -> {
                return SPELL_NAME;
            }
            case "conditions" -> {
                return CONDITION_NAME;
            }
            case "rule-sections" -> {
                return RULE_NAME;
            }
        }
        return "";
    }

    @VisibleForTesting
    public void handleWithHttpObjects(SlashCommandInteractionEvent event, String typeName, String typeValue, String query, HttpHelper httpHelper, Integer deleteDelay) {
        event.deferReply().queue();
        doLookUpAndReply(event.getHook(), typeName, typeValue, query, httpHelper, deleteDelay);
    }

    public static void doLookUpAndReply(InteractionHook hook, String typeName, String typeValue, String query, HttpHelper httpHelper, Integer deleteDelay) {
        String responseData = httpHelper.fetchFromGet(String.format(DND_5_API_URL, typeValue, query));
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
            case CONDITION_NAME, RULE_NAME -> {
                Information information = JsonParser.parseJsonToInformation(responseData);
                if (information != null) {
                    EmbedBuilder conditionEmbed = createEmbedFromInformation(information, typeName);
                    hook.sendMessageEmbeds(conditionEmbed.build()).queue();
                } else {
                    queryNonMatchRetry(hook, typeName, typeValue, query, httpHelper, deleteDelay);
                }
            }
            default -> hook.sendMessage("Something went wrong.").queue(invokeDeleteOnMessageResponse(deleteDelay));
        }
    }

    private static void queryNonMatchRetry(InteractionHook hook, String typeName, String typeValue, String query, HttpHelper httpHelper, Integer deleteDelay) {
        String queryResponseData = httpHelper.fetchFromGet(String.format(DND_5_API_URL, typeValue, "?name=" + query));
        QueryResult queryResult = JsonParser.parseJsonToQueryResult(queryResponseData);
        if (queryResult != null && queryResult.count() > 0) {
            StringSelectMenu.Builder builder = StringSelectMenu.create(String.format("DnD%s", typeName)).setPlaceholder("Choose an option");
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
            embedBuilder.setDescription(spell.desc().stream().reduce((s1, s2) -> String.join("\n", s1, s2)).get());
        }
        if (!spell.higher_level().isEmpty()) {
            embedBuilder.addField("Higher Level", spell.higher_level().stream().reduce((s1, s2) -> String.join("\n", s1, s2)).get(), false);

        }
        if (spell.range() != null) {
            embedBuilder.addField("Range", transformToMeters(Integer.parseInt(spell.range().split(" ")[0])) + "m", true);
        }

        if (spell.components() != null && !spell.components().isEmpty()) {
            embedBuilder.addField("Components", String.join(", ", spell.components()), true);
        }

        if (!spell.duration().isEmpty()) {
            embedBuilder.addField("Duration", spell.duration(), true);
        }

        embedBuilder.addField("Concentration", String.valueOf(spell.concentration()), true);

        if (!spell.casting_time().isEmpty()) {
            embedBuilder.addField("Casting Time", spell.casting_time(), true);
        }

        if (spell.level() >= 0) {
            embedBuilder.addField("Level", String.valueOf(spell.level()), true);
        }

        if (spell.damage() != null) {
            StringBuilder damageInfo = new StringBuilder();
            damageInfo.append("Damage Type: ").append(spell.damage().damage_type().name()).append("\n");
            damageInfo.append("Damage at Slot Level:\n");

            // Add damage at slot level information
            for (Map.Entry<String, String> entry : spell.damage().damage_at_slot_level().entrySet()) {
                damageInfo.append("Level ").append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
            }

            embedBuilder.addField("Damage Info", damageInfo.toString(), true);
        }

        Dc dc = spell.dc();
        if (dc != null) {
            embedBuilder.addField("DC Type", dc.dc_type().name(), true);
            if (dc.dc_success() != null) {
                embedBuilder.addField("DC Success", dc.dc_success(), true);
            }
        }

        if (spell.area_of_effect() != null) {
            embedBuilder.addField("Area of Effect", "Type: " + spell.area_of_effect().type() + ", Size: " + transformToMeters(spell.area_of_effect().size()) + "m", true);
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

    private static EmbedBuilder createEmbedFromInformation(Information information, String typeName) {
        EmbedBuilder embedBuilder = new EmbedBuilder();

        if (information.name() != null) {
            embedBuilder.setTitle(information.name());
        }

        if (information.desc() != null && !information.desc().isEmpty()) {
            if (typeName.equals(CONDITION_NAME))
                embedBuilder.setDescription(information.desc().stream().reduce((s1, s2) -> String.join("\n", s1, s2)).get());
            if (typeName.equals(RULE_NAME)) embedBuilder.setDescription(information.desc().get(0));
        }

        embedBuilder.setColor(0x42f5a7);
        return embedBuilder;
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
        OptionData query = new OptionData(OptionType.STRING, QUERY, "What is the thing you are looking up?", true);
        return List.of(type, query);
    }

}
