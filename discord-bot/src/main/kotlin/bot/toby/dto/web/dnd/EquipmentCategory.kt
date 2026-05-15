package bot.toby.dto.web.dnd

import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.entities.MessageEmbed

data class EquipmentCategory(
    val index: String?,
    val name: String?,
    val equipment: List<ApiInfo>?,
    val url: String?
) : DnDResponse {
    override fun isValidReturnObject(): Boolean =
        !(index.isNullOrEmpty() &&
                name.isNullOrEmpty() &&
                equipment.isNullOrEmpty() &&
                url.isNullOrEmpty())

    override fun toEmbed(): MessageEmbed {
        val embedBuilder = EmbedBuilder()
        name?.let { embedBuilder.setTitle(it) }
        if (!equipment.isNullOrEmpty()) {
            val list = equipment.joinToString("\n") { "• ${it.name}" }
            embedBuilder.addField("Equipment (${equipment.size})", truncateField(list), false)
        }
        embedBuilder.setColor(0x42f5a7)
        return embedBuilder.build()
    }

    private fun truncateField(text: String, limit: Int = 1024): String =
        if (text.length <= limit) text else text.substring(0, limit - 1) + "…"
}
