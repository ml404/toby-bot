package toby.dto.web.dnd.spell;

import java.util.List;

public record Spell(
        String index,
        String name,
        List<String> desc,
        List<String> higherLevel,
        String range,
        List<String> components,
        String material,
        boolean ritual,
        String duration,
        boolean concentration,
        String castingTime,
        int level,
        Damage damage,
        Dc dc,
        AreaOfEffect areaOfEffect,
        ApiInfo school,
        List<ApiInfo> classes,
        List<ApiInfo> subclasses,
        String url) {
}

