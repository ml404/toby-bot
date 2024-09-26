package toby.menu.menus.dnd

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import toby.helpers.DnDHelper
import toby.helpers.HttpHelper
import toby.logging.DiscordLogger
import toby.menu.IMenu
import toby.menu.MenuContext

class DndMenu(
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val httpHelper: HttpHelper,
    private val dnDHelper: DnDHelper,
    private val coroutineHandler: DndApiCoroutineHandler = DndApiCoroutineHandler(dispatcher, httpHelper, dnDHelper),
    private val eventProcessor: DndEventProcessor = DndEventProcessor()
) : IMenu {
    private val logger: DiscordLogger = DiscordLogger.createLogger(this::class.java)

    override fun handle(ctx: MenuContext, deleteDelay: Int) {
        logger.setGuildAndUserContext(ctx.guild, ctx.selectEvent.member!!)
        logger.info { "DnD menu event started" }
        val event = ctx.selectEvent
        event.deferReply(true).queue()

        val hook = event.hook
        val typeName = eventProcessor.toTypeString(event)
        val typeValue = eventProcessor.determineTypeValue(typeName)

        runCatching {
            coroutineHandler.launchFetchAndSendEmbed(event, typeName, typeValue, hook)
        }.onFailure {
            logger.error { "Error handling DnD retry request" }
            hook.sendMessage("Something went wrong trying to send off the API call for your selection, sorry about that")
                .setEphemeral(true).queue()
        }
    }

    override val name: String
        get() = "dnd"

    companion object {
        const val SPELL_NAME = "spell"
        const val CONDITION_NAME = "condition"
        const val RULE_NAME = "rule"
        const val FEATURE_NAME = "feature"
    }
}
