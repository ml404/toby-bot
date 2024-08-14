package toby.menu.menus

import kotlinx.coroutines.*
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent
import net.dv8tion.jda.api.interactions.InteractionHook
import toby.helpers.DnDHelper.doInitialLookup
import toby.helpers.DnDHelper.toEmbed
import toby.helpers.HttpHelper
import toby.menu.IMenu
import toby.menu.MenuContext

class DndMenu(private val coroutineDispatcher: CoroutineDispatcher = Dispatchers.IO) : IMenu {
    override fun handle(ctx: MenuContext, deleteDelay: Int) {
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
        val typeValue = when (typeName) {
            SPELL_NAME -> "spells"
            CONDITION_NAME -> "conditions"
            RULE_NAME -> "rule-sections"
            FEATURE_NAME -> "features"
            else -> throw IllegalArgumentException("Unknown DnD request type: $typeName")
        }
        sendDndApiRequest(hook, typeName, typeValue)
    }

    private fun StringSelectInteractionEvent.sendDndApiRequest(
        hook: InteractionHook,
        typeName: String,
        typeValue: String
    ) {
        val query = values.firstOrNull() ?: return
        message.delete().queue()
        // Launch a coroutine to handle the suspend function call
        val scope = CoroutineScope(coroutineDispatcher)
        val dnDResponseDeferred = scope.async { doInitialLookup(typeName, typeValue, query, HttpHelper()) }
        // Make sure to handle potential null response
        scope.launch { dnDResponseDeferred.await()?.let { hook.sendMessageEmbeds(it.toEmbed()).queue() }
    }

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
