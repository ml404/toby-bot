package toby.command.commands.fetch;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import org.jetbrains.annotations.VisibleForTesting;
import toby.command.CommandContext;
import toby.command.ICommand;
import toby.dto.web.dnd.spell.ClassInfo;
import toby.dto.web.dnd.spell.Dc;
import toby.dto.web.dnd.spell.Spell;
import toby.dto.web.dnd.spell.SubclassInfo;
import toby.helpers.HttpHelper;
import toby.helpers.JsonParser;
import toby.jpa.dto.UserDto;

import java.util.List;
import java.util.Map;

import static toby.command.ICommand.deleteAfter;

public class DnDCommand implements IFetchCommand {


    public static final String DND_5_API_URL = "https://www.dnd5eapi.co/api/%s/%s";

    @Override
    public void handle(CommandContext ctx, UserDto requestingUserDto, Integer deleteDelay) {
        handleWithHttpObjects(ctx, requestingUserDto, new HttpHelper(), deleteDelay);

    }

    @VisibleForTesting
    public void handleWithHttpObjects(CommandContext ctx, UserDto requestingUserDto, HttpHelper httpHelper, Integer deleteDelay) {
        SlashCommandInteractionEvent event = ctx.getEvent();
        deleteAfter(ctx.getEvent().getHook(), deleteDelay);
        String type = event.getOption("type").getAsString();
        String query = event.getOption("query").getAsString();
        String responseData = httpHelper.fetchFromGet(String.format(DND_5_API_URL, type, query));
        switch (type) {
            case "spell" -> {
                Spell spell = JsonParser.parseJSONToSpell(responseData);
                event.getChannel().sendMessageEmbeds(createSpellEmbed(spell)).queue();
            }
        }
    }


    private MessageEmbed createSpellEmbed(Spell spell) {
        EmbedBuilder embedBuilder = new EmbedBuilder();

        if (spell.name() != null) {
            embedBuilder.setTitle(spell.name());
        }

        if (spell.desc() != null && !spell.desc().isEmpty()) {
            embedBuilder.setDescription(spell.desc().stream().reduce((s1, s2) -> String.join("\n", s1, s2)).get());
        }

        if (spell.range() != null) {
            embedBuilder.addField("Range", spell.range(), true);
        }

        if (spell.components() != null && !spell.components().isEmpty()) {
            embedBuilder.addField("Components", String.join(", ", spell.components()), true);
        }

        if (spell.level() >= 0) {
            embedBuilder.addField("Level", String.valueOf(spell.level()), true);
        }

        if (spell.damage() != null) {
            StringBuilder damageInfo = new StringBuilder();
            damageInfo.append("Damage Type: ").append(spell.damage().damage_type()).append("\n");
            damageInfo.append("Damage at Slot Level:\n");

            // Add damage at slot level information
            for (Map.Entry<String, String> entry : spell.damage().damage_at_slot_level().entrySet()) {
                damageInfo.append("Level ").append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
            }

            embedBuilder.addField("Damage Info", damageInfo.toString(), false);
        }

        Dc dc = spell.dc();
        if (dc != null) {
            embedBuilder.addField("DC Type", dc.dc_type().name(), true);
            if (dc.dc_success() != null) {
                embedBuilder.addField("DC Success", dc.dc_success(), true);
            }
        }

        if (spell.area_of_effect() != null) {
            embedBuilder.addField("Area of Effect", "Type: " + spell.area_of_effect().type() + ", Size: " + spell.area_of_effect().size(), true);
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
            embedBuilder.addField("More Info", spell.url(), false);
        }

        embedBuilder.setColor(0x42f5a7);

        return embedBuilder.build();
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
        OptionData type = new OptionData(OptionType.STRING, "type", "What type of are you looking up", true);
        type.addChoice("spell", "spells");
        OptionData query = new OptionData(OptionType.STRING, "query", "What is the thing you are looking up?", true);
        return List.of(type, query);
    }

}
