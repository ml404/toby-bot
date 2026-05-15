package bot.toby.dto.web.dnd

import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.entities.MessageEmbed

data class MagicSchool(
    val index: String?,
    val name: String?,
    val desc: String?,
    val url: String?
) : DnDResponse {
    override fun isValidReturnObject(): Boolean =
        !(index.isNullOrEmpty() &&
                name.isNullOrEmpty() &&
                desc.isNullOrEmpty() &&
                url.isNullOrEmpty())

    override fun toEmbed(): MessageEmbed {
        val embedBuilder = EmbedBuilder()
        name?.let { embedBuilder.setTitle(it) }
        desc?.let { embedBuilder.setDescription(it) }
        embedBuilder.setColor(0x42f5a7)
        return embedBuilder.build()
    }
}
