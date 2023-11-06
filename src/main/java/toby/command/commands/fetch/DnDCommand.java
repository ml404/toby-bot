package toby.command.commands.fetch;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import org.jetbrains.annotations.VisibleForTesting;
import toby.command.CommandContext;
import toby.dto.web.dnd.spell.ClassInfo;
import toby.dto.web.dnd.spell.Dc;
import toby.dto.web.dnd.spell.Spell;
import toby.dto.web.dnd.spell.SubclassInfo;
import toby.helpers.HttpHelper;
import toby.helpers.JsonParser;
import toby.jpa.dto.UserDto;

import java.util.List;
import java.util.Map;

public class DnDCommand implements IFetchCommand {


    public static final String DND_5_API_URL = "https://www.dnd5eapi.co/api/%s/%s";
    public static final String TYPE = "type";
    public static final String QUERY = "query";

    @Override
    public void handle(CommandContext ctx, UserDto requestingUserDto, Integer deleteDelay) {
        handleWithHttpObjects(ctx, requestingUserDto, new HttpHelper());

    }

    @VisibleForTesting
    public void handleWithHttpObjects(CommandContext ctx, UserDto requestingUserDto, HttpHelper httpHelper) {
        SlashCommandInteractionEvent event = ctx.getEvent();
        event.deferReply().queue();
        String type = event.getOption(TYPE).getAsString();
        String query = event.getOption(QUERY).getAsString();
        String responseData = httpHelper.fetchFromGet(String.format(DND_5_API_URL, type, query));
        switch (type) {
            case "spells" -> {
                Spell spell = JsonParser.parseJSONToSpell(responseData);
                EmbedBuilder spellEmbed = createSpellEmbed(spell);
                event.getHook().sendMessageEmbeds(spellEmbed.build()).queue();
            }
        }
    }


    private EmbedBuilder createSpellEmbed(Spell spell) {
        EmbedBuilder embedBuilder = new EmbedBuilder();

        if (spell.name() != null) {
            embedBuilder.setTitle(spell.name());
        }

        if (spell.desc() != null && !spell.desc().isEmpty()) {
            embedBuilder.setDescription(spell.desc().stream().reduce((s1, s2) -> String.join("\n", s1, s2)).get());
        }
        if(!spell.higher_level().isEmpty()){
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

        List<ClassInfo> spellClasses = spell.classes();
        if (spellClasses != null && !spellClasses.isEmpty()) {
            StringBuilder classesInfo = new StringBuilder();
            for (ClassInfo classInfo : spellClasses) {
                classesInfo.append(classInfo.name()).append("\n");
            }
            embedBuilder.addField("Classes", classesInfo.toString(), true);
        }

        List<SubclassInfo> subclasses = spell.subclasses();
        if (subclasses != null && !subclasses.isEmpty()) {
            StringBuilder subclassesInfo = new StringBuilder();
            for (SubclassInfo subclassInfo : subclasses) {
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
        type.addChoice("spell", "spells");
        OptionData query = new OptionData(OptionType.STRING, QUERY, "What is the thing you are looking up?", true);
        return List.of(type, query);
    }

}
