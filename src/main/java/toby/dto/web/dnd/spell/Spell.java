package toby.dto.web.dnd.spell;

import java.util.List;

public record Spell(
        String index,
        String name,
        List<String> desc,
        List<String> higher_level,
        String range,
        List<String> components,
        String material,
        boolean ritual,
        String duration,
        boolean concentration,
        String casting_time,
        int level,
        Damage damage,
        Dc dc,
        AreaOfEffect area_of_effect,
        School school,
        List<ClassInfo> classes,
        List<SubclassInfo> subclasses,
        String url) {
}

