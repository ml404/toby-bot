package bot.toby.voice

import io.mockk.every
import io.mockk.mockk
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.entities.User
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant

class VoiceCompanyTrackerTest {

    private val guildId = 42L
    private val guild: Guild = mockk<Guild>(relaxed = true).also {
        every { it.idLong } returns guildId
    }

    private fun channelWith(vararg members: Member): VoiceChannel {
        val ch = mockk<VoiceChannel>(relaxed = true)
        every { ch.guild } returns guild
        every { ch.members } returns members.toList()
        return ch
    }

    private fun humanMember(id: Long): Member {
        val m = mockk<Member>(relaxed = true)
        val u = mockk<User>(relaxed = true)
        every { m.idLong } returns id
        every { m.user } returns u
        every { u.isBot } returns false
        return m
    }

    private fun botMember(id: Long): Member {
        val m = mockk<Member>(relaxed = true)
        val u = mockk<User>(relaxed = true)
        every { m.idLong } returns id
        every { m.user } returns u
        every { u.isBot } returns true
        return m
    }

    @Test
    fun `solo user accumulates zero company seconds`() {
        val tracker = VoiceCompanyTracker()
        val alice = humanMember(1L)
        val bot = botMember(99L)
        val channel = channelWith(alice, bot)
        val t0 = Instant.parse("2026-04-01T00:00:00Z")

        tracker.startTracking(1L, guildId, channel, t0)
        val counted = tracker.stopTracking(1L, guildId, t0.plusSeconds(600))
        assertEquals(0L, counted)
    }

    @Test
    fun `user with company from the start accumulates full duration`() {
        val tracker = VoiceCompanyTracker()
        val alice = humanMember(1L)
        val bob = humanMember(2L)
        val channel = channelWith(alice, bob)
        val t0 = Instant.parse("2026-04-01T00:00:00Z")

        tracker.startTracking(1L, guildId, channel, t0)
        val counted = tracker.stopTracking(1L, guildId, t0.plusSeconds(300))
        assertEquals(300L, counted)
    }

    @Test
    fun `company accumulator flips on and off as other members join and leave`() {
        val tracker = VoiceCompanyTracker()
        val alice = humanMember(1L)
        val bob = humanMember(2L)
        val t0 = Instant.parse("2026-04-01T00:00:00Z")

        // Alice joins alone
        val channelSolo = channelWith(alice)
        tracker.startTracking(1L, guildId, channelSolo, t0)

        // 60s later Bob joins — company starts
        val channelWithBoth = channelWith(alice, bob)
        tracker.reconcileChannel(channelWithBoth, t0.plusSeconds(60))

        // 120s after that Bob leaves — company window closes with 120s accumulated
        tracker.reconcileChannel(channelSolo, t0.plusSeconds(180))

        // Alice stays alone for another 100s before leaving
        val counted = tracker.stopTracking(1L, guildId, t0.plusSeconds(280))
        assertEquals(120L, counted)
    }

    @Test
    fun `stopTracking closes any still-open company window`() {
        val tracker = VoiceCompanyTracker()
        val alice = humanMember(1L)
        val bob = humanMember(2L)
        val channel = channelWith(alice, bob)
        val t0 = Instant.parse("2026-04-01T00:00:00Z")

        tracker.startTracking(1L, guildId, channel, t0)
        val counted = tracker.stopTracking(1L, guildId, t0.plusSeconds(500))
        assertEquals(500L, counted)
    }

    @Test
    fun `stopTracking on unknown user returns zero`() {
        val tracker = VoiceCompanyTracker()
        val counted = tracker.stopTracking(999L, guildId, Instant.now())
        assertEquals(0L, counted)
    }

    @Test
    fun `bots do not count as company`() {
        val tracker = VoiceCompanyTracker()
        val alice = humanMember(1L)
        val bot1 = botMember(10L)
        val bot2 = botMember(11L)
        val channel = channelWith(alice, bot1, bot2)
        val t0 = Instant.parse("2026-04-01T00:00:00Z")

        tracker.startTracking(1L, guildId, channel, t0)
        val counted = tracker.stopTracking(1L, guildId, t0.plusSeconds(200))
        assertEquals(0L, counted)
    }
}
