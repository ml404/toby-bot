package web.casino

import database.service.JackpotService
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.http.ResponseEntity
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController

/**
 * End-to-end slice test for [JackpotPoolHeaderAdvice]. Wires the advice
 * through Spring's real `HttpEntityMethodProcessor` path with a tiny inline
 * controller, so it actually exercises the case the unit test mocks away —
 * `ResponseEntity<CasinoResponseLike>` returns where the outer
 * `MethodParameter.parameterType` is `ResponseEntity::class.java` and the
 * advice has to unwrap the generic itself. This is the regression guard
 * that ensures the bug fixed in #ee98f7b's follow-up cannot silently
 * re-introduce — the unit test alone could not have caught it.
 */
class JackpotPoolHeaderAdviceMvcTest {

    data class StubResponse(
        override val ok: Boolean = true,
        override val error: String? = null,
    ) : CasinoResponseLike

    @RestController
    class StubController {
        @GetMapping("/casino/{guildId}/stub/wrapped")
        fun wrapped(@PathVariable guildId: Long): ResponseEntity<StubResponse> =
            ResponseEntity.ok(StubResponse())

        @GetMapping("/casino/{guildId}/stub/direct")
        fun direct(@PathVariable guildId: Long): StubResponse = StubResponse()

        @GetMapping("/casino/{guildId}/stub/string")
        fun plainString(@PathVariable guildId: Long): ResponseEntity<String> =
            ResponseEntity.ok("nope")
    }

    private fun mvc(jackpotService: JackpotService): MockMvc =
        MockMvcBuilders.standaloneSetup(StubController())
            .setControllerAdvice(JackpotPoolHeaderAdvice(jackpotService))
            .build()

    @Test
    fun `stamps X-Jackpot-Pool on ResponseEntity-wrapped casino response`() {
        val svc = mockk<JackpotService>().also { every { it.getPool(42L) } returns 12_345L }
        mvc(svc).perform(get("/casino/42/stub/wrapped"))
            .andExpect(status().isOk)
            .andExpect(header().string(JackpotPoolHeaderAdvice.HEADER, "12345"))
    }

    @Test
    fun `stamps X-Jackpot-Pool on direct casino response`() {
        val svc = mockk<JackpotService>().also { every { it.getPool(7L) } returns 99L }
        mvc(svc).perform(get("/casino/7/stub/direct"))
            .andExpect(status().isOk)
            .andExpect(header().string(JackpotPoolHeaderAdvice.HEADER, "99"))
    }

    @Test
    fun `does not stamp header on non-casino ResponseEntity`() {
        val svc = mockk<JackpotService>(relaxed = true)
        mvc(svc).perform(get("/casino/1/stub/string"))
            .andExpect(status().isOk)
            .andExpect(header().doesNotExist(JackpotPoolHeaderAdvice.HEADER))
    }
}
