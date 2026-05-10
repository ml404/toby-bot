package bot.toby.command.commands.dnd

import bot.toby.helpers.charactersheet.CharacterSheetProvider
import bot.toby.helpers.charactersheet.CharacterSheetProvider.FetchResult
import core.command.Command.Companion.replyAndDelete
import core.command.CommandContext
import database.dto.UserDto
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

@Component
class RefreshCharacterCommand @Autowired constructor(
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val characterSheetProvider: CharacterSheetProvider,
) : DnDCommand {

    override val name = "refreshcharacter"
    override val description = "Re-fetch your linked D&D Beyond character sheet from D&D Beyond"

    override fun handle(ctx: CommandContext, requestingUserDto: UserDto, deleteDelay: Int) {
        val event = ctx.event
        event.deferReply(true).queue()
        val hook = event.hook

        val characterId = requestingUserDto.dndBeyondCharacterId
        if (characterId == null) {
            hook.replyAndDelete("You don't have a character linked. Use `/linkcharacter` first.", deleteDelay)
            return
        }

        CoroutineScope(dispatcher).launch {
            when (val result = runCatching { characterSheetProvider.fetchCharacterSheet(characterId) }
                .getOrElse { FetchResult.Unavailable(it) }) {
                is FetchResult.Success -> {
                    hook.sendMessageEmbeds(result.sheet.toEmbed()).queue()
                }
                FetchResult.Forbidden -> {
                    hook.replyAndDelete(
                        "❌ Character `$characterId` is private on D&D Beyond. " +
                            "Set it to **Public** (or **Campaign Only**) in the character's Privacy settings, then try again.",
                        deleteDelay,
                    )
                }
                FetchResult.NotFound -> {
                    hook.replyAndDelete(
                        "❌ D&D Beyond could not find character `$characterId`. Re-link with `/linkcharacter`.",
                        deleteDelay,
                    )
                }
                is FetchResult.Unavailable -> {
                    hook.replyAndDelete(
                        "⚠️ D&D Beyond is unreachable right now — please try again shortly.",
                        deleteDelay,
                    )
                }
            }
        }
    }
}
