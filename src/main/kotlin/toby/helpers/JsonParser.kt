package toby.helpers

import com.google.gson.FieldNamingPolicy
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import toby.dto.web.dnd.*

object JsonParser {
    @JvmStatic
    fun parseJSONToSpell(jsonData: String?): Spell? {
        val gson = createGsonWithSnakeCaseFields()
        return gson.fromJson(jsonData, Spell::class.java)
    }

    @JvmStatic
    fun parseJsonToCondition(jsonData: String?): Condition? {
        val gson = createGsonWithSnakeCaseFields()
        return gson.fromJson(jsonData, Condition::class.java)
    }

    @JvmStatic
    fun parseJsonToRule(jsonData: String?): Rule? {
        val gson = createGsonWithSnakeCaseFields()
        return gson.fromJson(jsonData, Rule::class.java)
    }

    @JvmStatic
    fun parseJsonToFeature(jsonData: String?): Feature? {
        val gson = createGsonWithSnakeCaseFields()
        return gson.fromJson(jsonData, Feature::class.java)
    }


    @JvmStatic
    fun parseJsonToQueryResult(jsonData: String?): QueryResult? {
        val gson = Gson()
        return gson.fromJson(jsonData, QueryResult::class.java)
    }

    private fun createGsonWithSnakeCaseFields(): Gson {
        return GsonBuilder().setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES).create()
    }
}
