package core.modal

import core.log.Loggable
import core.managers.Named

interface Modal : Loggable, Named {
    override val name: String

    fun handle(ctx: ModalContext, deleteDelay: Int)
}
