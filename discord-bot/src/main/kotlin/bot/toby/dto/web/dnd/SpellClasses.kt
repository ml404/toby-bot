package bot.toby.dto.web.dnd

import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.entities.MessageEmbed
import java.lang.String.join

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
): DnDResponse {
    override fun isValidReturnObject(): Boolean {
        return !(index.isNullOrEmpty() &&
                name.isNullOrEmpty() &&
                desc.isEmpty() &&
                higherLevel.isEmpty() &&
                range.isNullOrEmpty() &&
                components.isEmpty() &&
                material.isNullOrEmpty() &&
                ritual == null &&
                duration.isNullOrEmpty() &&
                concentration == null &&
                castingTime.isNullOrEmpty() &&
                level == null &&
                damage == null &&
                dc == null &&
                areaOfEffect == null &&
                school == null &&
                classes.isNullOrEmpty() &&
                subclasses.isEmpty() &&
                url.isNullOrEmpty())
    }

    override fun toEmbed(): MessageEmbed {
        val embedBuilder = EmbedBuilder()
        name?.let { embedBuilder.setTitle(name) }
        if (desc.isNotEmpty()) {
            embedBuilder.setDescription(desc.transformListToString())
        }
        if (higherLevel.isNotEmpty()) {
            embedBuilder.addField("Higher Level", higherLevel.transformListToString(), false)
        }
        range?.let {
            val meterValue = if (it == "Touch") "Touch" else buildString {
                append(
                    transformToMeters(
                        it.split(" ".toRegex()).dropLastWhile { s -> s.isEmpty() }.toTypedArray()[0].toInt()
                    )
                )
                append("m")
            }
            embedBuilder.addField("Range", meterValue, true)
        }
        if (components.isNotEmpty()) {
            embedBuilder.addField("Components", join(", ", components), true)
        }
        duration?.let { embedBuilder.addField("Duration", duration, true) }
        concentration?.let { embedBuilder.addField("Concentration", concentration.toString(), true) }
        castingTime?.let { embedBuilder.addField("Casting Time", castingTime, true) }
        level?.let { embedBuilder.addField("Level", level.toString(), true) }

        if (damage != null) {
            val damageInfo = StringBuilder()
            damageInfo.append("Damage Type: ").append(damage.damageType.name).append("\n")

            // Add damage at slot level information
            val damageAtSlotLevel = damage.damageAtSlotLevel
            if (damageAtSlotLevel != null) {
                damageInfo.append("Damage at Slot Level:\n")
                for ((key, value) in damageAtSlotLevel) {
                    damageInfo.append("Level ").append(key).append(": ").append(value).append("\n")
                }
            }
            embedBuilder.addField("Damage Info", damageInfo.toString(), true)
        }
        dc?.let {
            embedBuilder.addField("DC Type", it.dcType.name, true)
            it.dcSuccess?.let { success -> embedBuilder.addField("DC Success", success, true) }
        }
        areaOfEffect?.let {
            embedBuilder.addField(
                "Area of Effect",
                "Type: ${it.type}, Size: ${transformToMeters(it.size)}m",
                true
            )
        }
        school?.let { embedBuilder.addField("School", it.name, true) }
        val spellClasses = classes
        if (!spellClasses.isNullOrEmpty()) {
            val classesInfo = StringBuilder()
            for (classInfo in spellClasses) {
                classesInfo.append(classInfo.name).append("\n")
            }
            embedBuilder.addField("Classes", classesInfo.toString(), true)
        }
        val subclasses = subclasses
        if (subclasses.isNotEmpty()) {
            val subclassesInfo = StringBuilder()
            for (subclassInfo in subclasses) {
                subclassesInfo.append(subclassInfo?.name).append("\n")
            }
            embedBuilder.addField("Subclasses", subclassesInfo.toString(), true)
        }
        url?.let { embedBuilder.setUrl("https://www.dndbeyond.com/" + it.replace("/api/", "")) }
        embedBuilder.setColor(0x42f5a7)
        return embedBuilder.build()
    }
}

data class AreaOfEffect(val type: String, val size: Int)

data class Damage(val damageType: DamageType, val damageAtSlotLevel: Map<String, String>?)

data class DamageType(val index: String, val name: String, val url: String)

data class Dc(val dcType: ApiInfo, val dcSuccess: String?)

data class ApiInfo(val index: String, val name: String, val url: String)

data class QueryResult(val count: Int, val results: List<ApiInfo>)