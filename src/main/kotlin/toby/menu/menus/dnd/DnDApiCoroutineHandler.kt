package toby.menu.menus.dnd

import kotlinx.coroutines.*
import mu.KotlinLogging
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent
import net.dv8tion.jda.api.interactions.InteractionHook
import toby.helpers.DnDHelper.doInitialLookup
import toby.helpers.DnDHelper.toEmbed
import toby.helpers.HttpHelper

class DndApiCoroutineHandler(
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val httpHelper: HttpHelper

) {
    private val logger = KotlinLogging.logger {}

    fun launchFetchAndSendEmbed(
        event: StringSelectInteractionEvent,
        typeName: String,
        typeValue: String,
        hook: InteractionHook
    ) {
        logger.info("Starting launchFetchAndSendEmbed")
        CoroutineScope(dispatcher).launch {
            val query = event.values.firstOrNull() ?: return@launch
            val initialQueryDeferred = async { doInitialLookup(typeName, typeValue, query, httpHelper) }
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