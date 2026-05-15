package bot.toby.helpers

import bot.toby.dto.web.dnd.*
import com.google.gson.FieldNamingPolicy
import com.google.gson.Gson
import com.google.gson.GsonBuilder

object JsonParser {
    @JvmStatic
    fun parseJSONToSpell(jsonData: String?): Spell? =
        snake().fromJson(jsonData, Spell::class.java)

    @JvmStatic
    fun parseJsonToCondition(jsonData: String?): Condition? =
        snake().fromJson(jsonData, Condition::class.java)

    @JvmStatic
    fun parseJsonToRule(jsonData: String?): Rule? =
        snake().fromJson(jsonData, Rule::class.java)

    @JvmStatic
    fun parseJsonToFeature(jsonData: String?): Feature? =
        snake().fromJson(jsonData, Feature::class.java)

    @JvmStatic
    fun parseJsonToAbilityScore(jsonData: String?): AbilityScore? =
        snake().fromJson(jsonData, AbilityScore::class.java)

    @JvmStatic
    fun parseJsonToDnDClass(jsonData: String?): DnDClass? =
        snake().fromJson(jsonData, DnDClass::class.java)

    @JvmStatic
    fun parseJsonToDamageTypeInfo(jsonData: String?): DamageTypeInfo? =
        snake().fromJson(jsonData, DamageTypeInfo::class.java)

    @JvmStatic
    fun parseJsonToEquipmentCategory(jsonData: String?): EquipmentCategory? =
        snake().fromJson(jsonData, EquipmentCategory::class.java)

    @JvmStatic
    fun parseJsonToEquipment(jsonData: String?): Equipment? =
        snake().fromJson(jsonData, Equipment::class.java)

    @JvmStatic
    fun parseJsonToLanguage(jsonData: String?): Language? =
        snake().fromJson(jsonData, Language::class.java)

    @JvmStatic
    fun parseJsonToMagicSchool(jsonData: String?): MagicSchool? =
        snake().fromJson(jsonData, MagicSchool::class.java)

    @JvmStatic
    fun parseJsonToMonster(jsonData: String?): Monster? =
        snake().fromJson(jsonData, Monster::class.java)

    @JvmStatic
    fun parseJsonToProficiency(jsonData: String?): Proficiency? =
        snake().fromJson(jsonData, Proficiency::class.java)

    @JvmStatic
    fun parseJsonToRace(jsonData: String?): Race? =
        snake().fromJson(jsonData, Race::class.java)

    @JvmStatic
    fun parseJsonToSkill(jsonData: String?): Skill? =
        snake().fromJson(jsonData, Skill::class.java)

    @JvmStatic
    fun parseJsonToSubclass(jsonData: String?): Subclass? =
        snake().fromJson(jsonData, Subclass::class.java)

    @JvmStatic
    fun parseJsonToSubrace(jsonData: String?): Subrace? =
        snake().fromJson(jsonData, Subrace::class.java)

    @JvmStatic
    fun parseJsonToTrait(jsonData: String?): Trait? =
        snake().fromJson(jsonData, Trait::class.java)

    @JvmStatic
    fun parseJsonToWeaponProperty(jsonData: String?): WeaponProperty? =
        snake().fromJson(jsonData, WeaponProperty::class.java)

    @JvmStatic
    fun parseJsonToQueryResult(jsonData: String?): QueryResult? {
        if (jsonData.isNullOrBlank()) return null
        return Gson().fromJson(jsonData, QueryResult::class.java)
    }

    private fun snake(): Gson =
        GsonBuilder().setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES).create()
}
