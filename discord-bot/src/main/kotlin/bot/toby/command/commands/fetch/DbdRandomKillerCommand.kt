package bot.toby.command.commands.fetch

import bot.toby.command.CommandContext
import bot.toby.command.ICommand.Companion.invokeDeleteOnMessageResponse
import bot.toby.helpers.Cache
import bot.toby.helpers.WikiFetcher
import java.io.IOException

class DbdRandomKillerCommand(private val cache: Cache) : IFetchCommand {
    override fun handle(ctx: CommandContext, requestingUserDto: bot.database.dto.UserDto, deleteDelay: Int?) {
        val event = ctx.event
        event.deferReply().queue()
        try {
            val wikiFetcher = WikiFetcher(cache)
            val dbdKillers = wikiFetcher.fetchFromWiki(cacheName, dbdWebUrl, className, cssQuery)
            event.hook.sendMessageFormat(dbdKillers.random()).queue(invokeDeleteOnMessageResponse(deleteDelay!!))
        } catch (ignored: IOException) {
            event.hook.sendMessageFormat("Huh, the website I pull data from must have returned something unexpected.").queue(invokeDeleteOnMessageResponse(deleteDelay!!))
        }
    }

    override val name: String
        get() = "dbd-killer"
    override val description: String
        get() = "return a random dead by daylight killer"

    companion object {
        private const val dbdWebUrl = "https://deadbydaylight.fandom.com/wiki/Killers"
        const val cacheName = "dbdKillers"
        const val className = "mw-content-ltr"
        const val cssQuery = "div"
    }
}
