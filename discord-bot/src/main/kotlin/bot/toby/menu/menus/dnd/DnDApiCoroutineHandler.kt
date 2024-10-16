package bot.toby.menu.menus.dnd

import bot.toby.helpers.DnDHelper
import bot.toby.helpers.HttpHelper
import common.logging.DiscordLogger
import kotlinx.coroutines.*
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent
import net.dv8tion.jda.api.interactions.InteractionHook

class DndApiCoroutineHandler(
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val httpHelper: HttpHelper,
    private val dndHelper: DnDHelper

) {
    private val logger: DiscordLogger = DiscordLogger.createLogger(this::class.java)

    fun launchFetchAndSendEmbed(
        event: StringSelectInteractionEvent,
        typeName: String,
        typeValue: String,
        hook: InteractionHook
    ) {
        logger.setGuildAndMemberContext(event.guild, event.member)
        logger.info("Starting launchFetchAndSendEmbed")
        CoroutineScope(dispatcher).launch {
            val query = event.values.firstOrNull() ?: return@launch
            val initialQueryDeferred = async { dndHelper.doInitialLookup(typeName, typeValue, query, httpHelper) }
            val response = initialQueryDeferred.await()
            logger.info("Response is: ${response.toString()}")
            response?.let {
                if (it.isValidReturnObject()) {
                    logger.info("Finished processing, sending embed...")
                    hook.sendMessageEmbeds(it.toEmbed()).queue()
                }
            } ?: hook.sendMessage("Something went wrong, please try again later.").queue()
        }
    }
}