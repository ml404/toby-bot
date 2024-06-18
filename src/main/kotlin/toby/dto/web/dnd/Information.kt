package toby.dto.web.dnd

data class Information(
    val index: String?,
    val name: String?,
    val desc: List<String>?,
    val url: String?
)

fun Information.isAllFieldsNull(): Boolean =
            index == null &&
            name == null &&
            desc.isNullOrEmpty()
            && url == null
