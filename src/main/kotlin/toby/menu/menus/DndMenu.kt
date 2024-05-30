package toby.menu.menus

import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent
import toby.command.commands.dnd.DnDCommand
import toby.command.commands.dnd.DnDCommand.Companion.doLookUpAndReply
import toby.helpers.HttpHelper
import toby.menu.IMenu
import toby.menu.MenuContext

class DndMenu : IMenu {
    override fun handle(ctx: MenuContext, deleteDelay: Int) {
        val event = ctx.selectEvent
        event.deferReply().queue()
        event.toTypeString()
            .runCatching { event.determineDnDRequestType(deleteDelay, this) }
            .onFailure { throw RuntimeException(it) }
    }

    private fun StringSelectInteractionEvent.determineDnDRequestType(deleteDelay: Int, type: String) {
        val typeName = when (type) {
            DnDCommand.SPELL_NAME -> "spells"
            DnDCommand.CONDITION_NAME -> "conditions"
            DnDCommand.RULE_NAME -> "rule-sections"
            DnDCommand.FEATURE_NAME -> "features"
            else -> throw IllegalArgumentException("Unknown DnD request type: $type")
        }
        sendDndApiRequest(typeName, type, deleteDelay)
    }

    private fun StringSelectInteractionEvent.sendDndApiRequest(
        typeName: String,
        typeValue: String,
        deleteDelay: Int
    ) {
        val query = values.firstOrNull() ?: return
        message.delete().queue()
        doLookUpAndReply(hook, typeValue, typeName, query, HttpHelper(), deleteDelay)
    }

    override val name: String
        get() = "dnd"

    companion object {
        private fun StringSelectInteractionEvent.toTypeString(): String {
            return componentId.split(":").getOrNull(1) ?: ""
        }
    }
}
