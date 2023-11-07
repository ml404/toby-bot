package toby.dto.web.dnd.spell;

import java.util.Map;

public record Damage(DamageType damageType, Map<String, String> damageAtSlotLevel) {
}
