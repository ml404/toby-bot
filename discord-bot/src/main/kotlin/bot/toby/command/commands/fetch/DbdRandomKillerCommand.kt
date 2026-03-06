package bot.toby.command.commands.fetch

import bot.toby.helpers.WikiFetcher
import common.helpers.Cache
import core.command.Command.Companion.invokeDeleteOnMessageResponse
import core.command.CommandContext
import database.dto.UserDto
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import java.io.IOException

@Component
class DbdRandomKillerCommand @Autowired constructor(private val cache: Cache) : FetchCommand {
    override fun handle(ctx: CommandContext, requestingUserDto: UserDto, deleteDelay: Int) {
        val event = ctx.event
        event.deferReply().queue()
        try {
            val wikiFetcher = WikiFetcher(cache)
            val dbdKillers = wikiFetcher.fetchFromWiki(CACHE_NAME, DBD_WEB_URL, CLASS_NAME, CSS_QUERY)
            event.hook.sendMessageFormat(dbdKillers.random()).queue(
                invokeDeleteOnMessageResponse(
                    deleteDelay
                )
            )
        } catch (_: IOException) {
            event.hook.sendMessageFormat("Huh, the website I pull data from must have returned something unexpected.")
                .queue(
                    invokeDeleteOnMessageResponse(deleteDelay)
                )
        }
    }

    override val name: String
        get() = "dbd-killer"
    override val description: String
        get() = "return a random dead by daylight killer"

    companion object {
        private const val DBD_WEB_URL = "https://deadbydaylight.fandom.com/wiki/Killers"
        const val CACHE_NAME = "dbdKillers"
        const val CLASS_NAME = "mw-content-ltr"
        const val CSS_QUERY = "div"
    }
}
