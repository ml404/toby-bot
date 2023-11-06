package toby.dto.web.dnd.spell;

import java.util.List;

public record QueryResult(int count, List<ApiInfo> results) {
}
