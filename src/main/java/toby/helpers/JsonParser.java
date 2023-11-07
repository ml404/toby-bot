package toby.helpers;

import com.google.gson.Gson;
import toby.dto.web.dnd.misc.Information;
import toby.dto.web.dnd.misc.Rule;
import toby.dto.web.dnd.spell.QueryResult;
import toby.dto.web.dnd.spell.Spell;

public class JsonParser {

    public static Spell parseJSONToSpell(String jsonData) {
        Gson gson = new Gson();
        return gson.fromJson(jsonData, Spell.class);
    }

    public static Information parseJsonToInformation(String jsonData) {
        Gson gson = new Gson();
        return gson.fromJson(jsonData, Information.class);
    }

    public static Rule parseJsonToRule(String jsonData) {
        Gson gson = new Gson();
        return gson.fromJson(jsonData, Rule.class);
    }


    public static QueryResult parseJsonToQueryResult(String jsonData) {
        Gson gson = new Gson();
        return gson.fromJson(jsonData, QueryResult.class);
    }

}
