package common.intro

/**
 * Server-wide rules about when intros are allowed to play at all.
 *
 * `DEFAULT_INTRO_VOLUME` used to be the only intro config key, which meant a
 * server could make intros quieter but never turn them off, and could never
 * exempt a channel. A "study", "meeting" or on-call voice channel got
 * everyone's intro at full blast and the only remedy was asking each member
 * individually to unset theirs.
 *
 * Both knobs read as opt-out so an existing guild with no rows behaves
 * exactly as it does today.
 */
object IntroGuildPolicy {

    /** Intros play unless the guild has explicitly stored "false". */
    fun introsEnabled(raw: String?): Boolean = !raw.orEmpty().trim().equals("false", ignoreCase = true)

    /**
     * Channel ids the guild has exempted, parsed from the stored CSV.
     *
     * Anything unparseable is dropped rather than failing the whole set: the
     * value is hand-editable from `/setconfig`, and one fat-fingered id should
     * not silently re-enable intros in every other exempted channel.
     */
    fun excludedChannelIds(raw: String?): Set<Long> = raw.orEmpty()
        .split(',', ' ', '\n')
        .mapNotNull { it.trim().removePrefix("<#").removeSuffix(">").toLongOrNull() }
        .toSet()

    fun formatExcludedChannelIds(ids: Collection<Long>): String = ids.joinToString(",")

    /**
     * Whether an intro may play for someone joining [channelId].
     *
     * A null channel means we couldn't resolve one; that's not a reason to
     * suppress, so it only fails the server-wide gate.
     */
    fun playsIn(enabledRaw: String?, excludedRaw: String?, channelId: Long?): Boolean {
        if (!introsEnabled(enabledRaw)) return false
        if (channelId == null) return true
        return channelId !in excludedChannelIds(excludedRaw)
    }

    /** Loudness normalisation is on unless the guild stores "false". */
    fun normaliseVolume(raw: String?): Boolean = !raw.orEmpty().trim().equals("false", ignoreCase = true)
}
