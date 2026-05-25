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
    // Nullable because Gson leaves the field as null when the API omits it
    // (most level-1 features have no prerequisites); declaring it as a
    // non-nullable List was a lie that NPE'd in isValidReturnObject/toEmbed.
    val prerequisites: List<String?>?,
    val desc: List<String>?,
    val url: String?
) : DnDResponse {
    override fun isValidReturnObject(): Boolean =
        !(index.isNullOrEmpty() &&
                classInfo == null &&
                name.isNullOrEmpty() &&
                level == null &&
                prerequisites.isNullOrEmpty() &&
                desc.isNullOrEmpty() &&
                url.isNullOrEmpty())

    override fun toEmbed(): MessageEmbed {
        val embedBuilder = EmbedBuilder()
        name?.let { embedBuilder.setTitle(it) }
        if (!desc.isNullOrEmpty()) {
            embedBuilder.setDescription(desc.transformListToString())
        }
        classInfo?.let { embedBuilder.addField("Class", it.name, true) }
        level?.let { embedBuilder.addField("Level", it.toString(), true) }
        if (!prerequisites.isNullOrEmpty()) {
            embedBuilder.addField("Prerequisites", prerequisites.transformListToString(), false)
        }
        embedBuilder.setColor(0x42f5a7)
        return embedBuilder.build()
    }
}