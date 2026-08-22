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

    /**
     * Whether [introId] belongs to this server at all — the weaker check, for
     * actions anyone may take on anyone's intro.
     *
     * The **View intros** menu lets any member play any member's intro, so
     * there is no owner to compare against. What still has to hold is that a
     * component id sent from a client can't reach across into another guild's
     * rows.
     */
    fun inGuild(introId: String?, guildId: Long): Boolean =
        introId != null && introId.startsWith("${guildId}_")

    /**
     * The guild an intro belongs to, read straight off the id.
     *
     * Used where an outcome has travelled away from the code that had a
     * `Guild` in hand — the audio path reports by id alone — and something
     * guild-shaped still has to happen, like telling a server its intros are
     * silent right now.
     */
    fun guildIdOf(introId: String?): Long? =
        introId?.substringBefore('_', missingDelimiterValue = "")?.toLongOrNull()
}
