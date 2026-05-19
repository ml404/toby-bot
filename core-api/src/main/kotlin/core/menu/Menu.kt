package core.menu

import core.log.Loggable
import core.managers.Named

interface Menu : Loggable, Named {
    fun handle(ctx: MenuContext, deleteDelay: Int)
    override val name: String
}
