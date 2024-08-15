package toby.menu.menus

import kotlinx.coroutines.*
import mu.KotlinLogging
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent
import net.dv8tion.jda.api.interactions.InteractionHook
import toby.helpers.DnDHelper.doInitialLookup
import toby.helpers.DnDHelper.toEmbed
import toby.helpers.HttpHelper
import toby.menu.IMenu
import toby.menu.MenuContext

class DndMenu(private val dispatcher: CoroutineDispatcher = Dispatchers.IO) : IMenu {
    private val logger = KotlinLogging.logger {}
    override fun handle(ctx: MenuContext, deleteDelay: Int) {
        logger.info { "DnD menu event started for guild ${ctx.guild.idLong}" }
        val event = ctx.selectEvent
        event.deferReply().queue()
        val type = event.toTypeString()
        runCatching {
            event.determineDnDRequestType(type, event.hook)
        }.onFailure {
            throw RuntimeException(it)
        }
    }

    private fun StringSelectInteractionEvent.determineDnDRequestType(
        typeName: String,
        hook: InteractionHook
    ) {
        logger.info { "Determining DnD type value" }
        val typeValue = when (typeName) {
            SPELL_NAME -> "spells"
            CONDITION_NAME -> "conditions"
            RULE_NAME -> "rule-sections"
            FEATURE_NAME -> "features"
            else -> {
                logger.info { "Non valid typename passed to the DnDMenu" }
                ""
            }
        }
        logger.info { "Found Type value $typeValue" }
        sendDndApiRequest(hook, typeName, typeValue)
    }

    private fun StringSelectInteractionEvent.sendDndApiRequest(
        hook: InteractionHook,
        typeName: String,
        typeValue: String
    ) {
        val query = values.firstOrNull() ?: return
        // Launch a coroutine to handle the suspend function call
        CoroutineScope(dispatcher).launch {
            val dnDResponseDeferred = async { doInitialLookup(typeName, typeValue, query, HttpHelper(dispatcher)) }
            // Make sure to handle potential null response
            dnDResponseDeferred.await()?.let { hook.sendMessageEmbeds(it.toEmbed()).queue() }

        }
        message.delete().queue()

    }

    override val name: String
        get() = "dnd"

    companion object {
        const val SPELL_NAME = "spell"
        const val CONDITION_NAME = "condition"
        const val RULE_NAME = "rule"
        const val FEATURE_NAME = "feature"

        private fun StringSelectInteractionEvent.toTypeString(): String {
            return componentId.split(":").getOrNull(1) ?: ""
        }
    }
}
