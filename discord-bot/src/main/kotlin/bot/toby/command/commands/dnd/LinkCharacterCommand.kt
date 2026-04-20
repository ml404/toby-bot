package bot.toby.command.commands.dnd

import bot.toby.dto.web.dnd.CharacterSheet
import bot.toby.helpers.UserDtoHelper
import bot.toby.helpers.charactersheet.CharacterSheetProvider
import core.command.Command.Companion.invokeDeleteOnMessageResponse
import core.command.CommandContext
import database.dto.UserDto
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

@Component
class LinkCharacterCommand @Autowired constructor(
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val characterSheetProvider: CharacterSheetProvider,
    private val userDtoHelper: UserDtoHelper
) : DnDCommand {

    override val name = "linkcharacter"
    override val description = "Link your D&D Beyond character sheet to this server"
    override val optionData: List<OptionData> = listOf(
        OptionData(OptionType.STRING, CHARACTER, "Your D&D Beyond character URL or ID (e.g. https://www.dndbeyond.com/characters/12345 or just 12345)", true)
    )

    companion object {
        const val CHARACTER = "character"
    }

    override fun handle(ctx: CommandContext, requestingUserDto: UserDto, deleteDelay: Int) {
        val event = ctx.event
        event.deferReply(true).queue()
        val hook = event.hook

        val input = event.getOption(CHARACTER)?.asString ?: ""
        val characterId = extractCharacterId(input)

        if (characterId == null) {
            hook.sendMessage("Could not extract a valid character ID from: `$input`. Please provide a D&D Beyond character URL or numeric ID.")
                .queue(invokeDeleteOnMessageResponse(deleteDelay))
            return
        }

        requestingUserDto.dndBeyondCharacterId = characterId

        CoroutineScope(dispatcher).launch {
            val cachedSheet = runCatching { characterSheetProvider.getCharacterSheet(characterId) }.getOrNull()

            if (cachedSheet != null) {
                val dexMod = cachedSheet.modifier(CharacterSheet.DEX)
                requestingUserDto.initiativeModifier = dexMod
                userDtoHelper.updateUser(requestingUserDto)

                val embed = EmbedBuilder()
                    .setTitle("✅ Character Linked: ${cachedSheet.name}")
                    .addField("Race", cachedSheet.raceName(), true)
                    .addField("Class", cachedSheet.classesString(), true)
                    .addField("Initiative Modifier", "${formatModifier(dexMod)} (from DEX)", true)
                    .setColor(0x42f5a7)
                    .build()
                hook.sendMessageEmbeds(embed).queue()
            } else {
                userDtoHelper.updateUser(requestingUserDto)
                hook.sendMessage(
                    "✅ Linked character ID `$characterId`. " +
                    "No cached sheet yet — stats will populate the next time D&D Beyond is reachable. " +
                    "View sheet: https://www.dndbeyond.com/characters/$characterId"
                ).queue()
            }
        }
    }

    internal fun extractCharacterId(input: String): Long? =
        Regex("(\\d+)").findAll(input).lastOrNull()?.value?.toLongOrNull()

    private fun formatModifier(mod: Int): String = if (mod >= 0) "+$mod" else "$mod"
}
