package bot.toby.command.commands.moderation

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Pins the timestamp format on each anti-autoclicker embed variant.
 *
 * The closed-session embed used to render its "Ended" line as
 * `<t:UNIX:R>` — Discord's relative-time format which the client
 * keeps re-rendering as "X seconds ago" indefinitely. Once a session
 * is finalised the moment is historical and shouldn't keep aging in
 * the channel; the active-embed `:R` variant is correct *while* the
 * embed is being live-edited.
 *
 * This test pins the closed-embed Ended format to `:f` (fixed short
 * datetime) so a future refactor can't silently regress the format
 * back to relative.
 */
internal class AntiAutoclickEmbedsTest {

    private val discordId = 12345L
    private val gameKey = "slots"
    private val startedAt: Instant = Instant.parse("2026-05-09T12:00:00Z")
    private val endedAt: Instant = Instant.parse("2026-05-09T12:05:30Z")

    @Test
    fun `closedEmbed Ended field is a fixed timestamp not relative`() {
        val embed = AntiAutoclickEmbeds.closedEmbed(
            discordId = discordId,
            gameKey = gameKey,
            peakStreak = 287,
            totalFires = 12,
            startedAt = startedAt,
            endedAt = endedAt,
        )

        val ended = embed.fields.firstOrNull { it.name == "Ended" }
        assertNotNull(ended, "closed embed must include an Ended field")
        val value = ended!!.value ?: ""
        assertTrue(
            value.contains("<t:${endedAt.epochSecond}:f>"),
            "Ended field must use Discord's `<t:UNIX:f>` fixed format, " +
                "not `:R` (relative). Got: $value"
        )
        assertTrue(
            !value.contains(":R>"),
            "Ended field must not use relative-time format on a finalised session. Got: $value"
        )
    }

    @Test
    fun `closedEmbed Started field is also fixed format for symmetry`() {
        val embed = AntiAutoclickEmbeds.closedEmbed(
            discordId = discordId,
            gameKey = gameKey,
            peakStreak = 287,
            totalFires = 12,
            startedAt = startedAt,
            endedAt = endedAt,
        )

        val started = embed.fields.firstOrNull { it.name == "Started" }
        assertNotNull(started, "closed embed must include a Started field")
        assertTrue(
            (started!!.value ?: "").contains("<t:${startedAt.epochSecond}:f>"),
            "Started on closed embed must be `:f` so both timestamps stop aging. " +
                "Got: ${started.value}"
        )
    }

    @Test
    fun `activeEmbed Started field stays relative - the active embed is being live-edited`() {
        val embed = AntiAutoclickEmbeds.activeEmbed(
            discordId = discordId,
            gameKey = gameKey,
            currentStreak = 287,
            peakStreak = 290,
            fireCount = 12,
            edgePct = 30.0,
            startedAt = startedAt,
            now = Instant.parse("2026-05-09T12:03:00Z"),
        )

        val started = embed.fields.firstOrNull { it.name == "Started" }
        assertNotNull(started, "active embed must include a Started field")
        assertTrue(
            (started!!.value ?: "").contains("<t:${startedAt.epochSecond}:R>"),
            "Started on active embed should be `:R` — the embed is being live-edited " +
                "and the relative count is informative. Got: ${started.value}"
        )
    }
}
