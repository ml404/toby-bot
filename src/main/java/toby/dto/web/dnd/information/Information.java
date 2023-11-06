package toby.dto.web.dnd.information;

import java.util.List;

public record Information(String index, String name, List<String>desc, String url) {
}
