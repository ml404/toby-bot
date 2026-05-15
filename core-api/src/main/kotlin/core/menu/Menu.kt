package core.menu

import core.log.Loggable

interface Menu : Loggable {
    fun handle(ctx: MenuContext, deleteDelay: Int)
    val name: String
}
