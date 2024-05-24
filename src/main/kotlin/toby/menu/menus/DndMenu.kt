package toby.menu.menus

import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent
import toby.command.commands.dnd.DnDCommand
import toby.command.commands.dnd.DnDCommand.Companion.doLookUpAndReply
import toby.helpers.HttpHelper
import toby.menu.IMenu
import toby.menu.MenuContext
import java.io.UnsupportedEncodingException

class DndMenu : IMenu {
    override fun handle(ctx: MenuContext, deleteDelay: Int) {
        val event = ctx.selectEvent
        event.deferReply().queue()
        try {
            val type = getType(event)
            determineDnDRequestType(event, deleteDelay, type)
        } catch (e: UnsupportedEncodingException) {
            throw RuntimeException(e)
        }
    }

    @Throws(UnsupportedEncodingException::class)
    private fun determineDnDRequestType(event: StringSelectInteractionEvent, deleteDelay: Int, type: String) {
        val typeName = when (type) {
            DnDCommand.SPELL_NAME -> "spells"
            DnDCommand.CONDITION_NAME -> "conditions"
            DnDCommand.RULE_NAME -> "rule-sections"
            DnDCommand.FEATURE_NAME -> "features"
            else -> throw IllegalArgumentException("Unknown DnD request type: $type")
        }
        sendDndApiRequest(event, typeName, type, deleteDelay)
    }

    private fun sendDndApiRequest(
        event: StringSelectInteractionEvent,
        typeName: String,
        typeValue: String,
        deleteDelay: Int
    ) {
        val query = event.values.firstOrNull() ?: return
        event.message.delete().queue()
        doLookUpAndReply(event.hook, typeValue, typeName, query, HttpHelper(), deleteDelay)
    }

    override val name: String
        get() = "dnd"

    companion object {
        private fun getType(event: StringSelectInteractionEvent): String {
            return event.componentId.split(":").getOrNull(1) ?: ""
        }
    }
}
