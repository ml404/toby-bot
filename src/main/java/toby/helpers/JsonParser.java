package toby.helpers;

import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.jetbrains.annotations.NotNull;
import toby.dto.web.dnd.Feature;
import toby.dto.web.dnd.Information;
import toby.dto.web.dnd.Rule;
import toby.dto.web.dnd.spell.QueryResult;
import toby.dto.web.dnd.spell.Spell;

public class JsonParser {

    public static Spell parseJSONToSpell(String jsonData) {
        Gson gson = createGsonWithSnakeCaseFields();
        return gson.fromJson(jsonData, Spell.class);
    }

    public static Information parseJsonToInformation(String jsonData) {
        Gson gson = createGsonWithSnakeCaseFields();
        return gson.fromJson(jsonData, Information.class);
    }

    public static Rule parseJsonToRule(String jsonData) {
        Gson gson = createGsonWithSnakeCaseFields();
        return gson.fromJson(jsonData, Rule.class);
    }

    public static Feature parseJsonToFeature(String jsonData){
        Gson gson = createGsonWithSnakeCaseFields();
        return gson.fromJson(jsonData, Feature.class);
    }


    public static QueryResult parseJsonToQueryResult(String jsonData) {
        Gson gson = new Gson();
        return gson.fromJson(jsonData, QueryResult.class);
    }

    @NotNull
    private static Gson createGsonWithSnakeCaseFields() {
        return new GsonBuilder().setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES).create();
    }

}
