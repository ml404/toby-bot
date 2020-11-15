package toby;

import com.google.common.collect.ImmutableMap;

import java.util.Map;


public class BotConfig {

     static final Map<String, String> configMap = ImmutableMap.of(
             "TOKEN", "NTUzNjU4MDM5MjY2NDQzMjY0.D2RTSA._rvjb2-d1hxBXF55jH4is4_VHGQ",
             "PREFIX", "!",
             "DATEFORMAT", "yyyy/MM/dd"
     );


     public static String get(String key) {
          return configMap.get(key.toUpperCase());
     }
}