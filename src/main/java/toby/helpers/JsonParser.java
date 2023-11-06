package toby.helpers;

import com.google.gson.Gson;
import toby.dto.web.dnd.spell.QueryResult;
import toby.dto.web.dnd.spell.Spell;

public class JsonParser {

    public static Spell parseJSONToSpell(String jsonData){
        Gson gson = new Gson();
        return gson.fromJson(jsonData, Spell.class);
    }

    public static QueryResult parseJsonToSpellList(String jsonData){
        Gson gson = new Gson();
        return gson.fromJson(jsonData, QueryResult.class);
    }

}
