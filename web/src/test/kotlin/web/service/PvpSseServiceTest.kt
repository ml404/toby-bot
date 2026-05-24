package web.service

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import web.service.sse.KeyedSseRegistry

class PvpSseServiceTest {

    private val guildId = 42L
    private val initiatorId = 100L
    private val opponentId = 200L

    @Test
    fun `register opens a stream keyed on guild and discord id`() {
        val registry = mockk<KeyedSseRegistry<PvpSseService.Key>>(relaxed = true)
        val emitter = SseEmitter(1000L)
        every { registry.register(any(), any(), any()) } returns emitter
        val service = PvpSseService(registry)

        val result = service.register(guildId, initiatorId)

        assertSame(emitter, result)
        verify { registry.register(PvpSseService.Key(guildId, initiatorId), any(), any()) }
    }

    @Test
    fun `fanOutToBoth broadcasts to initiator and opponent separately`() {
        val registry = mockk<KeyedSseRegistry<PvpSseService.Key>>(relaxed = true)
        val service = PvpSseService(registry)

        service.fanOutToBoth(guildId, initiatorId, opponentId, "rps.resolved", mapOf("sessionId" to 1L))

        verify { registry.fanOut(PvpSseService.Key(guildId, initiatorId), "rps.resolved", any()) }
        verify { registry.fanOut(PvpSseService.Key(guildId, opponentId), "rps.resolved", any()) }
    }

    @Test
    fun `fanOutToUser hits only that user's bucket`() {
        val registry = mockk<KeyedSseRegistry<PvpSseService.Key>>(relaxed = true)
        val service = PvpSseService(registry)

        service.fanOutToUser(guildId, opponentId, "rps.offered", mapOf("sessionId" to 5L))

        verify(exactly = 1) { registry.fanOut(PvpSseService.Key(guildId, opponentId), "rps.offered", any()) }
        verify(exactly = 0) { registry.fanOut(PvpSseService.Key(guildId, initiatorId), any(), any()) }
    }

    private fun <T> assertSame(expected: T, actual: T) {
        if (expected !== actual) throw AssertionError("Expected same reference; got different")
    }
}
