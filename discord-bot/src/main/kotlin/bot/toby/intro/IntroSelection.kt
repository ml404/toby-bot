package bot.toby.intro

import database.dto.music.MusicDto
import kotlin.random.Random

/**
 * Chooses which of a user's intros to play on join.
 *
 * This used to be a bare `musicDtos.random()`. With the three-slot cap that
 * meant a 1-in-3 chance of hearing the same intro twice running and a 1-in-9
 * chance of three in a row — often enough that "TobyBot picks one at random"
 * didn't feel random. Excluding the previous pick keeps every join a change
 * while staying uniform across the rest.
 */
object IntroSelection {

    /**
     * @param intros the user's intros for this guild.
     * @param lastPlayedId id of the intro played last time, if known.
     * @return the intro to play, or null when the user has none.
     */
    fun pick(
        intros: Collection<MusicDto>,
        lastPlayedId: String? = null,
        random: Random = Random.Default,
    ): MusicDto? {
        val candidates = intros.toList()
        if (candidates.size <= 1) return candidates.firstOrNull()

        // With only one intro left after excluding the previous pick there is
        // still a choice; with none left (every id matches, i.e. duplicates)
        // fall back to the full set rather than playing nothing.
        val fresh = candidates.filter { it.id != lastPlayedId }
        return (if (fresh.isEmpty()) candidates else fresh).random(random)
    }
}
