package toby.dto.web.dnd

data class Spell(
    val index: String?,
    val name: String?,
    val desc: List<String?>,
    val higherLevel: List<String?>,
    val range: String?,
    val components: List<String?>,
    val material: String?,
    val ritual: Boolean?,
    val duration: String?,
    val concentration: Boolean?,
    val castingTime: String?,
    val level: Int?,
    val damage: Damage?,
    val dc: Dc?,
    val areaOfEffect: AreaOfEffect?,
    val school: ApiInfo?,
    val classes: List<ApiInfo>?,
    val subclasses: List<ApiInfo?>,
    val url: String?
)

data class AreaOfEffect(val type: String, val size: Int)

data class Damage(val damageType: DamageType, val damageAtSlotLevel: Map<String, String>?)

data class DamageType(val index: String, val name: String, val url: String)

data class Dc(val dcType: ApiInfo, val dcSuccess: String?)

data class ApiInfo(val index: String, val name: String, val url: String)

data class QueryResult(val count: Int, val results: List<ApiInfo>)
