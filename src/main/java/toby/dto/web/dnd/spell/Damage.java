package toby.dto.web.dnd.spell;

import java.util.Map;

public record Damage(DamageType damage_type, Map<String, String> damage_at_slot_level) {
}
