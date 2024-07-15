package toby.dto.web.dnd

data class Rule(
    val index: String?,
    val name: String?,
    val desc: String?,
    val url: String?
)

fun Rule.isAllFieldsNull(): Boolean =
            index == null &&
            name == null &&
            desc.isNullOrEmpty() &&
            url == null

