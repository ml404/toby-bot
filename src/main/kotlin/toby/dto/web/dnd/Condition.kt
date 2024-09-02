package toby.dto.web.dnd

data class Condition(
    val index: String?,
    val name: String?,
    val desc: List<String>?,
    val url: String?
) : DnDResponse {
    override fun isValidReturnObject(): Boolean =
        !(index.isNullOrEmpty() &&
                name.isNullOrEmpty() &&
                desc.isNullOrEmpty()
                && url.isNullOrEmpty())

}