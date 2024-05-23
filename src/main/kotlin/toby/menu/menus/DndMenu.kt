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
            determineDnDRequestType(event, deleteDelay, getType(event))
        } catch (e: UnsupportedEncodingException) {
            throw RuntimeException(e)
        }
    }

    @Throws(UnsupportedEncodingException::class)
    private fun determineDnDRequestType(event: StringSelectInteractionEvent, deleteDelay: Int, type: String) {
        when (type) {
            DnDCommand.SPELL_NAME -> sendDndApiRequest(event, DnDCommand.SPELL_NAME, "spells", deleteDelay)
            DnDCommand.CONDITION_NAME -> sendDndApiRequest(event, DnDCommand.CONDITION_NAME, "conditions", deleteDelay)
            DnDCommand.RULE_NAME -> sendDndApiRequest(event, DnDCommand.RULE_NAME, "rule-sections", deleteDelay)
            DnDCommand.FEATURE_NAME -> sendDndApiRequest(event, DnDCommand.FEATURE_NAME, "features", deleteDelay)
        }
    }

    private fun sendDndApiRequest(
        event: StringSelectInteractionEvent,
        typeName: String,
        typeValue: String,
        deleteDelay: Int
    ) {
        val query = event.values[0] // Get the selected option
        event.message.delete().queue()
        doLookUpAndReply(event.hook, typeName, typeValue, query, HttpHelper(), deleteDelay)
    }

    override val name: String
        get() = "dnd"

    companion object {
        private fun getType(event: StringSelectInteractionEvent): String {
            return event.componentId.split(":".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()[1]
        }
    }
}
