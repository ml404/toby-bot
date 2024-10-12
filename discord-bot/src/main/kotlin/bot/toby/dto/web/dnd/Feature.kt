package bot.toby.dto.web.dnd

import com.google.gson.annotations.SerializedName
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.entities.MessageEmbed

data class Feature(
    val index: String?,
    @SerializedName("class")
    val classInfo: ApiInfo?,
    val name: String?,
    val level: Int?,
    val prerequisites: List<String?>,
    val desc: List<String>?,
    val url: String?
) : DnDResponse {
    override fun isValidReturnObject(): Boolean =
        !(index.isNullOrEmpty() &&
                classInfo == null &&
                name.isNullOrEmpty() &&
                level == null &&
                prerequisites.isEmpty() &&
                desc.isNullOrEmpty() &&
                url.isNullOrEmpty())

    override fun toEmbed(): MessageEmbed {
        val embedBuilder = EmbedBuilder()
        if (name != null) {
            embedBuilder.setTitle(name)
        }
        if (!desc.isNullOrEmpty()) {
            embedBuilder.setDescription(desc.transformListToString())
        }
        if (classInfo != null) {
            embedBuilder.addField("Class", classInfo.name, true)
        }
        level?.let { embedBuilder.addField("Level", level.toString(), true) }
        if (prerequisites.isNotEmpty()) {
            embedBuilder.addField("Prerequisites", prerequisites.transformListToString(), false)
        }
        embedBuilder.setColor(0x42f5a7)
        return embedBuilder.build()
    }
}