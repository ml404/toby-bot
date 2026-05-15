package bot.toby.dto.web.dnd

import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.entities.MessageEmbed

data class Language(
    val index: String?,
    val name: String?,
    val type: String?,
    val typicalSpeakers: List<String>?,
    val script: String?,
    val desc: String?,
    val url: String?
) : DnDResponse {
    override fun isValidReturnObject(): Boolean =
        !(index.isNullOrEmpty() &&
                name.isNullOrEmpty() &&
                type.isNullOrEmpty() &&
                typicalSpeakers.isNullOrEmpty() &&
                script.isNullOrEmpty() &&
                desc.isNullOrEmpty() &&
                url.isNullOrEmpty())

    override fun toEmbed(): MessageEmbed {
        val embedBuilder = EmbedBuilder()
        name?.let { embedBuilder.setTitle(it) }
        desc?.let { embedBuilder.setDescription(it) }
        type?.let { embedBuilder.addField("Type", it, true) }
        script?.let { embedBuilder.addField("Script", it, true) }
        if (!typicalSpeakers.isNullOrEmpty()) {
            embedBuilder.addField("Typical Speakers", typicalSpeakers.joinToString(", "), false)
        }
        embedBuilder.setColor(0x42f5a7)
        return embedBuilder.build()
    }
}
