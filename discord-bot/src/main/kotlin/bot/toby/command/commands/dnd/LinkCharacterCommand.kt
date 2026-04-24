package bot.toby.command.commands.dnd

import bot.toby.dto.web.dnd.CharacterSheet
import bot.toby.helpers.UserDtoHelper
import bot.toby.helpers.charactersheet.CharacterSheetProvider
import bot.toby.helpers.charactersheet.CharacterSheetProvider.FetchResult
import common.helpers.parseDndBeyondCharacterId
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
        val characterId = parseDndBeyondCharacterId(input)

        if (characterId == null) {
            hook.sendMessage("Could not extract a valid character ID from: `$input`. Please provide a D&D Beyond character URL or numeric ID.")
                .queue(invokeDeleteOnMessageResponse(deleteDelay))
            return
        }

        CoroutineScope(dispatcher).launch {
            when (val result = runCatching { characterSheetProvider.fetchCharacterSheet(characterId) }
                .getOrElse { FetchResult.Unavailable(it) }) {
                is FetchResult.Success -> {
                    val sheet = result.sheet
                    requestingUserDto.dndBeyondCharacterId = characterId
                    userDtoHelper.updateUser(requestingUserDto)

                    val dexMod = sheet.modifier(CharacterSheet.DEX)
                    val embed = EmbedBuilder()
                        .setTitle("✅ Character Linked: ${sheet.name}")
                        .addField("Race", sheet.raceName(), true)
                        .addField("Class", sheet.classesString(), true)
                        .addField("Initiative Modifier", "${formatModifier(dexMod)} (from DEX)", true)
                        .setColor(0x42f5a7)
                        .build()
                    hook.sendMessageEmbeds(embed).queue()
                }
                FetchResult.Forbidden -> {
                    hook.sendMessage(
                        "❌ Character `$characterId` is private. " +
                        "Open it on D&D Beyond → **Home** → **Privacy** and set it to **Public** " +
                        "(or **Campaign Only** if your DM has a paid subscription), then run `/linkcharacter` again. " +
                        "Your existing link was not changed."
                    ).queue(invokeDeleteOnMessageResponse(deleteDelay))
                }
                FetchResult.NotFound -> {
                    hook.sendMessage(
                        "❌ No D&D Beyond character found with ID `$characterId`. Double-check the URL or ID and try again."
                    ).queue(invokeDeleteOnMessageResponse(deleteDelay))
                }
                is FetchResult.Unavailable -> {
                    requestingUserDto.dndBeyondCharacterId = characterId
                    userDtoHelper.updateUser(requestingUserDto)
                    hook.sendMessage(
                        "⚠️ Linked character ID `$characterId`, but D&D Beyond is unreachable right now — " +
                        "stats will populate on the next successful fetch. Try `/refreshcharacter` later. " +
                        "View sheet: https://www.dndbeyond.com/characters/$characterId"
                    ).queue()
                }
            }
        }
    }

    private fun formatModifier(mod: Int): String = if (mod >= 0) "+$mod" else "$mod"
}
