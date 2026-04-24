package bot.toby.helpers.charactersheet

import bot.toby.dto.web.dnd.CharacterSheet
import com.google.gson.Gson

object CharacterSheetCodec {
    private val gson = Gson()

    fun decode(json: String): CharacterSheet = gson.fromJson(json, CharacterSheet::class.java)

    fun decodeOrNull(json: String?): CharacterSheet? =
        json?.let { runCatching { decode(it) }.getOrNull() }
}
