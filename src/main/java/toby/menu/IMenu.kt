package toby.menu

interface IMenu {
    fun handle(ctx: MenuContext, deleteDelay: Int)
    val name: String
}
