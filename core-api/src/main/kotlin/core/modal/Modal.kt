package core.modal

import core.log.Loggable

interface Modal : Loggable {
    val name: String

    fun handle(ctx: ModalContext, deleteDelay: Int)
}
