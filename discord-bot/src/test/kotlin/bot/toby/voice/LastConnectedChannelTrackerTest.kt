package bot.toby.voice

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class LastConnectedChannelTrackerTest {

    private val tracker = LastConnectedChannelTracker()

    @Test
    fun `resolveChannel returns null before any set`() {
        val guild = mockk<Guild>(relaxed = true) { every { idLong } returns 42L }

        assertNull(tracker.resolveChannel(guild))
    }

    @Test
    fun `resolveChannel returns the channel JDA returns for the stored id`() {
        val channel = mockk<VoiceChannel>(relaxed = true)
        val guild = mockk<Guild>(relaxed = true) {
            every { idLong } returns 42L
            every { getVoiceChannelById(7L) } returns channel
        }
        tracker.set(guildId = 42L, channelId = 7L)

        assertSame(channel, tracker.resolveChannel(guild))
    }

    @Test
    fun `resolveChannel returns null if JDA no longer knows the stored channel id`() {
        // Channel was deleted between session shutdown and the next start —
        // tracker still holds the id, but JDA's lookup returns null. Caller
        // gets null so it falls back to whatever it does for "no channel".
        val guild = mockk<Guild>(relaxed = true) {
            every { idLong } returns 42L
            every { getVoiceChannelById(7L) } returns null
        }
        tracker.set(guildId = 42L, channelId = 7L)

        assertNull(tracker.resolveChannel(guild))
    }

    @Test
    fun `clear removes the stored channel for the guild`() {
        val guild = mockk<Guild>(relaxed = true) { every { idLong } returns 42L }
        tracker.set(guildId = 42L, channelId = 7L)
        tracker.clear(42L)

        assertNull(tracker.resolveChannel(guild))
        // Verify the guild lookup never even fires — short-circuited on the map miss.
        verify(exactly = 0) { guild.getVoiceChannelById(any<Long>()) }
    }

    @Test
    fun `set overwrites a prior channel id for the same guild`() {
        val newChannel = mockk<VoiceChannel>(relaxed = true)
        val guild = mockk<Guild>(relaxed = true) {
            every { idLong } returns 42L
            every { getVoiceChannelById(99L) } returns newChannel
        }
        tracker.set(guildId = 42L, channelId = 7L)
        tracker.set(guildId = 42L, channelId = 99L)

        assertSame(newChannel, tracker.resolveChannel(guild))
    }

    @Test
    fun `clear only affects the named guild`() {
        val channel = mockk<VoiceChannel>(relaxed = true)
        val otherGuild = mockk<Guild>(relaxed = true) {
            every { idLong } returns 99L
            every { getVoiceChannelById(13L) } returns channel
        }
        tracker.set(guildId = 42L, channelId = 7L)
        tracker.set(guildId = 99L, channelId = 13L)
        tracker.clear(42L)

        assertSame(channel, tracker.resolveChannel(otherGuild))
    }
}
