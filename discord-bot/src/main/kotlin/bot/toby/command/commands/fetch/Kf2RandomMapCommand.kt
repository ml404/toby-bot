package bot.toby.command.commands.fetch

import bot.toby.helpers.WikiFetcher
import common.helpers.Cache
import core.command.Command.Companion.deleteAfter
import core.command.CommandContext
import database.dto.UserDto
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import java.io.IOException

@Component
class Kf2RandomMapCommand @Autowired constructor(private val cache: Cache) : FetchCommand {
    override fun handle(ctx: CommandContext, requestingUserDto: UserDto, deleteDelay: Int) {
        ctx.event.hook.deleteAfter(deleteDelay)
        val event = ctx.event
        event.deferReply(true).queue()
        try {
            val wikiFetcher = WikiFetcher(cache)
            val kf2Maps = wikiFetcher.fetchFromWiki(CACHE_NAME, KF2_WEB_URL, CLASS_NAME, "b")
            event.hook.sendMessage(kf2Maps.random()).queue { it?.deleteAfter(deleteDelay) }
        } catch (_: IOException) {
            event.hook.sendMessage("Huh, the website I pull data from must have returned something unexpected.").setEphemeral(true).queue { it?.deleteAfter(
                deleteDelay
            ) }
        }
    }

    override val name: String
        get() = "kf2"
    override val description: String
        get() = "return a random kf2 map"

    companion object {
        private const val KF2_WEB_URL = "https://wiki.killingfloor2.com/index.php?title=Maps_(Killing_Floor_2)"
        private const val CACHE_NAME = "kf2Maps"
        private const val CLASS_NAME = "mw-parser-output"
    }
}
