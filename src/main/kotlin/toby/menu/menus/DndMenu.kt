package toby.menu.menus

import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent
import net.dv8tion.jda.api.interactions.InteractionHook
import toby.command.commands.dnd.DnDCommand.Companion.doLookUpAndReply
import toby.helpers.HttpHelper
import toby.menu.IMenu
import toby.menu.MenuContext

class DndMenu : IMenu {
    override fun handle(ctx: MenuContext, deleteDelay: Int) {
        val event = ctx.selectEvent
        event.deferReply().queue()
        val type = event.toTypeString()
        runCatching {
            event.determineDnDRequestType(deleteDelay, type, event.hook)
        }.onFailure {
            throw RuntimeException(it)
        }
    }

    private fun StringSelectInteractionEvent.determineDnDRequestType(
        deleteDelay: Int,
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
        sendDndApiRequest(typeName, type, deleteDelay, hook)
    }

    private fun StringSelectInteractionEvent.sendDndApiRequest(
        typeName: String,
        typeValue: String,
        deleteDelay: Int,
        hook: InteractionHook
    ) {
        val query = values.firstOrNull() ?: return
        message.delete().queue()
        doLookUpAndReply(hook, typeValue, typeName, query, HttpHelper(), deleteDelay)
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
