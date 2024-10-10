package toby.command.commands.fetch

import database.dto.UserDto
import toby.command.CommandContext
import toby.command.ICommand.Companion.deleteAfter
import toby.command.commands.misc.RandomCommand
import toby.helpers.Cache
import toby.helpers.WikiFetcher
import java.io.IOException

class Kf2RandomMapCommand(private val cache: Cache) : IFetchCommand {
    override fun handle(ctx: CommandContext, requestingUserDto: UserDto, deleteDelay: Int?) {
        ctx.event.hook.deleteAfter(deleteDelay ?: 0)
        val event = ctx.event
        event.deferReply().queue()
        try {
            val wikiFetcher = WikiFetcher(cache)
            val kf2Maps = wikiFetcher.fetchFromWiki(cacheName, kf2WebUrl, className, "b")
            event.hook.sendMessage(RandomCommand.getRandomElement(kf2Maps)).queue { it?.deleteAfter(deleteDelay ?: 0) }
        } catch (ignored: IOException) {
            event.hook.sendMessage("Huh, the website I pull data from must have returned something unexpected.").setEphemeral(true).queue { it?.deleteAfter(deleteDelay ?: 0) }
        }
    }

    override val name: String
        get() = "kf2"
    override val description: String
        get() = "return a random kf2 map"

    companion object {
        private const val kf2WebUrl = "https://wiki.killingfloor2.com/index.php?title=Maps_(Killing_Floor_2)"
        private const val cacheName = "kf2Maps"
        private const val className = "mw-parser-output"
    }
}
