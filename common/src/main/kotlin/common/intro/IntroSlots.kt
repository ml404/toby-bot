package common.intro

/**
 * How many intros a user may hold per guild, and which slot a new one takes.
 *
 * The count was previously declared three times — `SetIntroCommand.LIMIT`,
 * `IntroWebService.MAX_INTRO_COUNT` and the `maxIntros` model attribute — with
 * nothing tying them together, so raising the cap meant remembering all three.
 */
object IntroSlots {
    const val MAX_INTRO_COUNT = 3

    /**
     * The lowest slot in `1..MAX_INTRO_COUNT` not already taken, or null when
     * every slot is full.
     *
     * Walking the range matters: a `size + 1` count is wrong the moment a
     * delete leaves a gap. With intros in slots `[1, 3]`, `size + 1` picks 3,
     * whose id (`guildId_discordId_3`) already exists — and
     * `DefaultMusicFilePersistence.createNewMusicFile` turns an id collision
     * into an update, silently overwriting the intro in slot 3 with the new
     * one. Both the web form and the slash commands allocate through here so
     * neither can reintroduce that.
     */
    fun nextFreeIndex(usedIndexes: Collection<Int?>): Int? {
        val used = usedIndexes.filterNotNull().toSet()
        return (1..MAX_INTRO_COUNT).firstOrNull { it !in used }
    }
}
