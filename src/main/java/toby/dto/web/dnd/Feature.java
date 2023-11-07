package toby.dto.web.dnd;

import com.google.gson.annotations.SerializedName;
import toby.dto.web.dnd.spell.ApiInfo;

import java.util.List;

public record Feature(String index,
                      @SerializedName("class") ApiInfo classInfo,
                      String name,
                      int level,
                      List<String> prerequisites,
                      List<String> desc,
                      String url) {
}
