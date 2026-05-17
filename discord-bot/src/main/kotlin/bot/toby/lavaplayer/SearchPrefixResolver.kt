package bot.toby.lavaplayer

import bot.toby.util.isUrl

object SearchPrefixResolver {
    private val EXPLICIT_PREFIXES = setOf(
        "ytsearch:",
        "ytmsearch:",
        "scsearch:",
        "spsearch:",
        "sprec:",
        "dzsearch:",
        "dzisrc:",
        "amsearch:",
        "ymsearch:",
        "ymrec:",
    )

    fun resolve(input: String, defaultPrefix: String = "ytsearch:"): String {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return trimmed
        if (isUrl(trimmed).isNotEmpty()) return trimmed
        if (EXPLICIT_PREFIXES.any { trimmed.startsWith(it, ignoreCase = true) }) return trimmed
        return "$defaultPrefix$trimmed"
    }
}
