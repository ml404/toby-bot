package bot.toby.command.commands.dnd

import bot.toby.dto.web.dnd.CharacterSheet
import bot.toby.helpers.DnDHelper
import bot.toby.helpers.HttpHelper
import bot.toby.helpers.UserDtoHelper
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
    private val httpHelper: HttpHelper,
    private val dndHelper: DnDHelper,
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

        CoroutineScope(dispatcher).launch {
            runCatching {
                val character = dndHelper.fetchCharacter(characterId, httpHelper)
                if (character != null) {
                    val dexMod = character.modifier(CharacterSheet.DEX)
                    requestingUserDto.dndBeyondCharacterId = characterId
                    requestingUserDto.initiativeModifier = dexMod
                    userDtoHelper.updateUser(requestingUserDto)

                    val embed = EmbedBuilder()
                        .setTitle("✅ Character Linked: ${character.name}")
                        .addField("Race", character.raceName(), true)
                        .addField("Class", character.classesString(), true)
                        .addField("Initiative Modifier", "${formatModifier(dexMod)} (from DEX)", true)
                        .setColor(0x42f5a7)
                        .build()
                    hook.sendMessageEmbeds(embed).queue()
                } else {
                    hook.sendMessage("Could not find a character with ID `$characterId`. Make sure your character is set to public on D&D Beyond.")
                        .queue(invokeDeleteOnMessageResponse(deleteDelay))
                }
            }.onFailure {
                hook.sendMessage("An error occurred while fetching your character. Please try again later.")
                    .queue(invokeDeleteOnMessageResponse(deleteDelay))
            }
        }
    }

    internal fun extractCharacterId(input: String): Long? =
        Regex("(\\d+)").findAll(input).lastOrNull()?.value?.toLongOrNull()

    private fun formatModifier(mod: Int): String = if (mod >= 0) "+$mod" else "$mod"
}
