package toby.menu.menus

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent
import net.dv8tion.jda.api.interactions.InteractionHook
import toby.helpers.DnDHelper.doInitialLookup
import toby.helpers.DnDHelper.toEmbed
import toby.helpers.HttpHelper
import toby.menu.IMenu
import toby.menu.MenuContext

class DndMenu : IMenu {
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
        type: String,
        hook: InteractionHook
    ) {
        val typeName = when (type) {
            SPELL_NAME -> "spells"
            CONDITION_NAME -> "conditions"
            RULE_NAME -> "rule-sections"
            FEATURE_NAME -> "features"
            else -> throw IllegalArgumentException("Unknown DnD request type: $type")
        }
        sendDndApiRequest(hook, typeName, type)
    }

    private fun StringSelectInteractionEvent.sendDndApiRequest(
        hook: InteractionHook,
        typeName: String,
        typeValue: String
    ) {
        val query = values.firstOrNull() ?: return
        message.delete().queue()
        // Launch a coroutine to handle the suspend function call
        CoroutineScope(Dispatchers.IO).launch {
            val dnDResponse = doInitialLookup(typeName, typeValue, query, HttpHelper())

            // Switch to the Main dispatcher for UI updates
            withContext(Dispatchers.Main) {
                // Make sure to handle potential null response
                dnDResponse?.let { hook.sendMessageEmbeds(it.toEmbed()).queue() }
            }
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
