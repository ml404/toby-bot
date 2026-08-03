package bot.toby.intro

/**
 * Who is allowed to act on a given intro.
 *
 * Intro ids are `guildId_discordId_index`, so ownership is readable straight
 * off the id — no lookup needed for the common case.
 *
 * This matters because the id arrives from the client. The select menus and
 * the edit modal only ever *offer* intros the caller may touch, but a crafted
 * component or modal submission can name any id at all, so the handler has to
 * check rather than trust what came back.
 */
object IntroOwnership {

    fun ownedBy(introId: String?, guildId: Long, discordId: Long): Boolean =
        introId != null && introId.startsWith("${guildId}_${discordId}_")
}
