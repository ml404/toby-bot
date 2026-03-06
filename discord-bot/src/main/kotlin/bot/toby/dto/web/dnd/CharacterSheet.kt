package bot.toby.dto.web.dnd

import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.entities.MessageEmbed

data class DnDBeyondCharacterResponse(
    val id: Long?,
    val success: Boolean?,
    val message: String?,
    val data: CharacterSheet?
)

data class CharacterSheet(
    val id: Long?,
    val name: String?,
    val stats: List<AbilityStat>?,
    val baseHitPoints: Int?,
    val bonusHitPoints: Int?,
    val removedHitPoints: Int?,
    val temporaryHitPoints: Int?,
    val race: CharacterRace?,
    val classes: List<CharacterClass>?
) {
    companion object {
        const val STR = 1
        const val DEX = 2
        const val CON = 3
        const val INT = 4
        const val WIS = 5
        const val CHA = 6
    }

    fun getStat(id: Int): Int = stats?.firstOrNull { it.id == id }?.value ?: 10

    fun modifier(id: Int): Int = Math.floorDiv(getStat(id) - 10, 2)

    fun currentHp(): Int = (baseHitPoints ?: 0) + (bonusHitPoints ?: 0) - (removedHitPoints ?: 0)

    fun maxHp(): Int = (baseHitPoints ?: 0) + (bonusHitPoints ?: 0)

    fun raceName(): String = race?.fullName ?: race?.baseName ?: "Unknown"

    fun classesString(): String = classes?.joinToString(", ") {
        "${it.definition?.name ?: "?"} ${it.level ?: "?"}"
    } ?: "Unknown"

    private fun modifierString(id: Int): String {
        val mod = modifier(id)
        return if (mod >= 0) "+$mod" else "$mod"
    }

    fun toEmbed(): MessageEmbed {
        val embedBuilder = EmbedBuilder()
        embedBuilder.setTitle(name ?: "Unknown Character")
        embedBuilder.addField("Race", raceName(), true)
        embedBuilder.addField("Class", classesString(), true)
        embedBuilder.addField("", "", false)
        embedBuilder.addField("STR", "${getStat(STR)} (${modifierString(STR)})", true)
        embedBuilder.addField("DEX", "${getStat(DEX)} (${modifierString(DEX)})", true)
        embedBuilder.addField("CON", "${getStat(CON)} (${modifierString(CON)})", true)
        embedBuilder.addField("INT", "${getStat(INT)} (${modifierString(INT)})", true)
        embedBuilder.addField("WIS", "${getStat(WIS)} (${modifierString(WIS)})", true)
        embedBuilder.addField("CHA", "${getStat(CHA)} (${modifierString(CHA)})", true)
        embedBuilder.addField("HP", "${currentHp()}/${maxHp()}", true)
        if ((temporaryHitPoints ?: 0) > 0) {
            embedBuilder.addField("Temp HP", temporaryHitPoints.toString(), true)
        }
        embedBuilder.setColor(0x42f5a7)
        return embedBuilder.build()
    }
}

data class AbilityStat(val id: Int, val value: Int?)
data class CharacterRace(val fullName: String?, val baseName: String?)
data class CharacterClass(val level: Int?, val definition: ClassDefinition?)
data class ClassDefinition(val name: String?)
