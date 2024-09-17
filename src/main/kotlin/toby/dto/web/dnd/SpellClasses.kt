package toby.dto.web.dnd

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
        if (range != null) {
            val meterValue = if (range == "Touch") "Touch" else buildString {
                append(
                    transformToMeters(
                        range.split(" ".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()[0].toInt()
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
        val dc = dc
        if (dc != null) {
            embedBuilder.addField("DC Type", dc.dcType.name, true)
            if (dc.dcSuccess != null) {
                embedBuilder.addField("DC Success", dc.dcSuccess, true)
            }
        }
        if (areaOfEffect != null) {
            embedBuilder.addField(
                "Area of Effect",
                "Type: ${areaOfEffect.type}, Size: ${transformToMeters(areaOfEffect.size)}m",
                true
            )
        }
        if (school != null) {
            embedBuilder.addField("School", school.name, true)
        }
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
        if (url != null) {
            embedBuilder.setUrl("https://www.dndbeyond.com/" + url.replace("/api/", ""))
        }
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