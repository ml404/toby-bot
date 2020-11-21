package toby;

import com.google.common.collect.ImmutableMap;

import java.util.Map;


public class BotConfig {

     static final Map<String, String> configMap = ImmutableMap.of(
             "TOKEN", "NTUzNjU4MDM5MjY2NDQzMjY0.D2RTSA._rvjb2-d1hxBXF55jH4is4_VHGQ",
             "PREFIX", "!",
             "DATEFORMAT", "yyyy/MM/dd"
     );

     public static Map<Long, String> brotherMap = Map.of(
             448385477251170315L, "Kristen",
             283541364992638977L, "Rhiton",
             549342186139942922L, "Hot Cuppa",
             313049624636162059L, "Matt");

    public static Long tobyId = 320919876883447808L;


     public static String get(String key) {
          return configMap.get(key.toUpperCase());
     }
}