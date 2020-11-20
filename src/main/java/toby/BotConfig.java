package toby;

import com.google.common.collect.ImmutableMap;

import java.util.List;
import java.util.Map;


public class BotConfig {

     static final Map<String, String> configMap = ImmutableMap.of(
             "TOKEN", "NTUzNjU4MDM5MjY2NDQzMjY0.D2RTSA._rvjb2-d1hxBXF55jH4is4_VHGQ",
             "PREFIX", "!",
             "DATEFORMAT", "yyyy/MM/dd"
     );

    public static final List<Long> brotherList = List.of(448385477251170315L,549342186139942922L,313049624636162059L, 283541364992638977L);

    public static final Long tobyId = 320919876883447808L;


     public static String get(String key) {
          return configMap.get(key.toUpperCase());
     }
}