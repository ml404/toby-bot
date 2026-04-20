package bot.toby.command.commands.dnd

import bot.toby.helpers.UserDtoHelper
import bot.toby.helpers.charactersheet.CharacterSheetProvider
import core.command.Command.Companion.invokeDeleteOnMessageResponse
import core.command.CommandContext
import database.dto.UserDto
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

@Component
class CharacterCommand @Autowired constructor(
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val characterSheetProvider: CharacterSheetProvider,
    private val userDtoHelper: UserDtoHelper
) : DnDCommand {

    override val name = "character"
    override val description = "Show a linked D&D Beyond character sheet"
    override val optionData: List<OptionData> = listOf(
        OptionData(OptionType.USER, USER, "Whose character to display (default: yourself)")
    )

    companion object {
        const val USER = "user"
    }

    override fun handle(ctx: CommandContext, requestingUserDto: UserDto, deleteDelay: Int) {
        val event = ctx.event
        event.deferReply().queue()
        val hook = event.hook

        val selfMember = ctx.member ?: return
        val targetMember = event.getOption(USER)?.asMember ?: selfMember
        val targetUserDto = userDtoHelper.calculateUserDto(
            targetMember.idLong,
            targetMember.guild.idLong,
            targetMember.isOwner
        )

        val characterId = targetUserDto.dndBeyondCharacterId
        if (characterId == null) {
            val message = if (targetMember.idLong == selfMember.idLong) {
                "You don't have a character linked. Use `/linkcharacter` to link your D&D Beyond character sheet."
            } else {
                "${targetMember.effectiveName} doesn't have a character linked."
            }
            hook.sendMessage(message).queue(invokeDeleteOnMessageResponse(deleteDelay))
            return
        }

        CoroutineScope(dispatcher).launch {
            runCatching {
                val character = characterSheetProvider.getCharacterSheet(characterId)
                if (character != null) {
                    hook.sendMessageEmbeds(character.toEmbed()).queue()
                } else {
                    hook.sendMessage(
                        "No cached sheet for character `$characterId`. " +
                        "Run `/linkcharacter` again while D&D Beyond is reachable to populate the cache. " +
                        "View on D&D Beyond: https://www.dndbeyond.com/characters/$characterId"
                    ).queue(invokeDeleteOnMessageResponse(deleteDelay))
                }
            }.onFailure {
                hook.sendMessage("An error occurred while loading the cached character sheet. Please try again later.")
                    .queue(invokeDeleteOnMessageResponse(deleteDelay))
            }
        }
    }
}
