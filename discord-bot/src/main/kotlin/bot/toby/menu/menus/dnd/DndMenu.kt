package bot.toby.menu.menus.dnd

import bot.toby.helpers.DnDHelper
import bot.toby.helpers.HttpHelper
import bot.toby.helpers.MenuHelper.DND
import bot.toby.menu.IMenu
import bot.toby.menu.MenuContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

class DndMenu(
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val httpHelper: HttpHelper,
    private val dnDHelper: DnDHelper,
    private val coroutineHandler: DndApiCoroutineHandler = DndApiCoroutineHandler(dispatcher, httpHelper, dnDHelper),
    private val eventProcessor: DndEventProcessor = DndEventProcessor()
) : IMenu {

    override fun handle(ctx: MenuContext, deleteDelay: Int) {
        logger.setGuildAndMemberContext(ctx.guild, ctx.member)
        logger.info { "DnD menu event started" }
        val event = ctx.event
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

    override val name: String get() = DND

    companion object {
        const val SPELL_NAME = "spell"
        const val CONDITION_NAME = "condition"
        const val RULE_NAME = "rule"
        const val FEATURE_NAME = "feature"
    }
}
