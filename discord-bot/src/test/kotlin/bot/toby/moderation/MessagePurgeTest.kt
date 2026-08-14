package bot.toby.moderation

import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel
import net.dv8tion.jda.api.requests.restaction.AuditableRestAction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/**
 * Discord's two rules for deleting in bulk. Both `/purge` and the right-click
 * entry lean on these, so getting them wrong is wrong in two places.
 */
class MessagePurgeTest {

    private val now: Instant = Instant.parse("2026-08-14T12:00:00Z")

    @AfterEach
    fun tearDown() = unmockkAll()

    private fun messageAged(days: Long, hours: Long = 0): Message = mockk(relaxed = true) {
        every { timeCreated } returns OffsetDateTime.ofInstant(
            now.minus(days, ChronoUnit.DAYS).minus(hours, ChronoUnit.HOURS),
            ZoneOffset.UTC,
        )
    }

    @Test
    fun `messages inside the window are kept and the rest are counted off`() {
        val messages = listOf(messageAged(1), messageAged(20), messageAged(3), messageAged(15))

        val scope = MessagePurge.deletable(messages, now)

        assertEquals(2, scope.messages.size)
        assertEquals(2, scope.tooOld)
    }

    @Test
    fun `the fourteen-day line is where Discord draws it`() {
        // A message a shade under fourteen days old is still bulk-deletable;
        // a shade over is not, and the whole call fails rather than partly
        // succeeding, so the boundary has to be exact.
        val scope = MessagePurge.deletable(listOf(messageAged(13, hours = 23), messageAged(14, hours = 1)), now)

        assertEquals(1, scope.messages.size)
        assertEquals(1, scope.tooOld)
    }

    @Test
    fun `nothing skipped means nothing said about skipping`() {
        assertEquals("", MessagePurge.deletable(listOf(messageAged(1)), now).suffix)
    }

    @Test
    fun `a short count explains itself`() {
        // Otherwise "deleted 3 messages" after pointing at 12 reads as a bug.
        assertEquals(
            " (2 older than 14 days skipped)",
            MessagePurge.deletable(listOf(messageAged(1), messageAged(30), messageAged(30)), now).suffix,
        )
    }

    @Test
    fun `an empty batch is not an error, just nothing to do`() {
        val scope = MessagePurge.deletable(emptyList(), now)

        assertEquals(0, scope.messages.size)
        assertEquals(0, scope.tooOld)
    }

    @Test
    fun `a single message goes through the ordinary delete, which the bulk endpoint refuses`() {
        val channel = mockk<TextChannel>(relaxed = true)
        val one = mockk<Message>(relaxed = true)
        val delete = mockk<AuditableRestAction<Void>>(relaxed = true)
        every { one.delete() } returns delete
        every { delete.reason(any()) } returns delete

        MessagePurge.delete(channel, listOf(one), "because")

        verify(exactly = 1) { one.delete() }
        // ...and it's the only one of the two that can carry an audit reason.
        verify(exactly = 1) { delete.reason("because") }
        verify(exactly = 0) { channel.deleteMessages(any()) }
    }

    @Test
    fun `two or more go through the bulk endpoint`() {
        val channel = mockk<TextChannel>(relaxed = true)
        val batch = listOf(mockk<Message>(relaxed = true), mockk<Message>(relaxed = true))

        MessagePurge.delete(channel, batch, "because")

        verify(exactly = 1) { channel.deleteMessages(batch) }
        batch.forEach { verify(exactly = 0) { it.delete() } }
    }

    @Test
    fun `the scan cap matches what the bulk endpoint accepts`() {
        assertEquals(100, MessagePurge.MAX_BULK)
    }
}
