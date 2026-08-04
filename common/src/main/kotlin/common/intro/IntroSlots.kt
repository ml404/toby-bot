package common.intro

/**
 * How many intros a user may hold per guild, and which slot a new one takes.
 *
 * The count was previously declared three times — `SetIntroCommand.LIMIT`,
 * `IntroWebService.MAX_INTRO_COUNT` and the `maxIntros` model attribute — with
 * nothing tying them together, so raising the cap meant remembering all three.
 *
 * ## Slots are identity, not ordering
 *
 * An intro's primary key is `guildId_discordId_slot`, so the slot is baked
 * into the row's identity: two rows in the same slot are the same row. Every
 * write path therefore has to agree on which slot is free, and has to agree
 * with the *id*, not with anything stored alongside it.
 */
object IntroSlots {
    const val MAX_INTRO_COUNT = 3

    /** The slot encoded in an intro id, or null if it isn't one of ours. */
    fun slotFromId(introId: String?): Int? = introId
        ?.substringAfterLast('_')
        ?.toIntOrNull()
        ?.takeIf { it in 1..MAX_INTRO_COUNT }

    /**
     * The lowest slot in `1..MAX_INTRO_COUNT` a new intro can take without
     * landing on an existing row, or null when every slot is full.
     *
     * Occupancy is read from the **ids**, because the id is what collides:
     * `DefaultMusicFilePersistence.createNewMusicFile` looks the id up and,
     * before this, turned a hit into an update — a silent overwrite of the
     * intro already living there.
     *
     * Reading the `index` column instead was wrong twice over. `size + 1` was
     * wrong the moment a delete left a gap (intros in `[1, 3]` picks 3, which
     * exists). Walking the range fixed that but still trusted `index`, which
     * the web drag-reorder rewrites *without* rewriting the id — so a user who
     * had reordered could hold ids `[_1, _3]` with indexes `[2, 1]`, and the
     * allocator would hand out 3 for a row that was already there. That is the
     * shape of the report this replaced: "it said it succeeded, but it
     * overwrote my second intro and set itself into slot 3."
     */
    fun nextFreeSlot(existingIntroIds: Collection<String?>): Int? {
        val used = existingIntroIds.mapNotNull { slotFromId(it) }.toSet()
        return (1..MAX_INTRO_COUNT).firstOrNull { it !in used }
    }
}
