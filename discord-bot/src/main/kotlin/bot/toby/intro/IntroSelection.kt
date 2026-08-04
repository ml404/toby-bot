package bot.toby.intro

import common.intro.IntroHealth
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
 *
 * Disabled intros ([MusicDto.enabled]) are skipped entirely, and intros whose
 * source has repeatedly failed to load are skipped in favour of one that
 * works — see [common.intro.IntroHealth].
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
        // A disabled intro keeps its slot but sits out the rotation; with all
        // of them off the user has effectively muted themselves, so play none.
        val enabled = intros.filter { it.enabled }

        // Prefer intros that actually load. Someone with a working intro and a
        // dead one shouldn't get silence half the time while they wait to
        // notice. This is a preference, not a filter: if every intro looks
        // broken we still try one, because the alternative is guaranteeing the
        // silence we're trying to avoid — and the source may well have come
        // back (a region block lifting, YouTube unthrottling the bot's IP).
        val healthy = enabled.filterNot { IntroHealth.isUnhealthy(it.failureCount) }
        val candidates = healthy.ifEmpty { enabled }
        if (candidates.size <= 1) return candidates.firstOrNull()

        // With only one intro left after excluding the previous pick there is
        // still a choice; with none left (every id matches, i.e. duplicates)
        // fall back to the full set rather than playing nothing.
        val fresh = candidates.filter { it.id != lastPlayedId }
        return (if (fresh.isEmpty()) candidates else fresh).random(random)
    }
}
