package toby.web.dnd

import net.dv8tion.jda.api.entities.MessageEmbed
import java.lang.String.join
import kotlin.math.roundToInt

interface DnDResponse {

    fun isValidReturnObject(): Boolean

    fun toEmbed(): MessageEmbed

    fun List<String?>.transformListToString(): String {
        return this.stream().reduce { s1: String?, s2: String? -> join("\n", s1, s2) }.get()
    }

    fun transformToMeters(rangeNumber: Int): String {
        return (rangeNumber.toDouble() / 3.28).roundToInt().toString()
    }
}