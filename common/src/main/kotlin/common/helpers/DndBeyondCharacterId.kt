package common.helpers

private val CHARACTER_ID_REGEX = Regex("(\\d+)")

fun parseDndBeyondCharacterId(input: String): Long? =
    CHARACTER_ID_REGEX.findAll(input).lastOrNull()?.value?.toLongOrNull()
