package web.casino

import database.service.JackpotService
import io.mockk.every
import io.mockk.mockk
import jakarta.servlet.http.HttpServletRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.core.MethodParameter
import org.springframework.http.MediaType
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.http.server.ServerHttpResponse
import org.springframework.http.server.ServletServerHttpRequest
import org.springframework.http.server.ServletServerHttpResponse
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.web.servlet.HandlerMapping


/**
 * Replaces the per-game jackpotPool body wiring that used to live in
 * eight services and eight controllers. One advice means one place to
 * test — every CasinoResponseLike response carries the post-action
 * pool size in the X-Jackpot-Pool header, and the JS reads it from
 * api.js. Adding a new minigame can no longer regress the banner.
 */
class JackpotPoolHeaderAdviceTest {

    private val guildId = 42L
    private lateinit var jackpotService: JackpotService
    private lateinit var advice: JackpotPoolHeaderAdvice

    @BeforeEach
    fun setUp() {
        jackpotService = mockk()
        advice = JackpotPoolHeaderAdvice(jackpotService)
    }

    @Test
    fun `supports returns true for CasinoResponseLike return types`() {
        val param = methodParameterReturning(SampleResponse::class.java)
        assertTrue(advice.supports(param, MappingJackson2HttpMessageConverter::class.java))
    }

    @Test
    fun `supports returns false for non-CasinoResponseLike return types`() {
        val param = methodParameterReturning(String::class.java)
        assertFalse(advice.supports(param, MappingJackson2HttpMessageConverter::class.java))
    }

    @Test
    fun `beforeBodyWrite stamps X-Jackpot-Pool header from JackpotService when guildId path var is present`() {
        every { jackpotService.getPool(guildId) } returns 9_999L
        val (request, response) = exchangeWithGuildId(guildId.toString())
        val body = SampleResponse(ok = true)

        val returned = advice.beforeBodyWrite(
            body = body,
            returnType = methodParameterReturning(SampleResponse::class.java),
            selectedContentType = MediaType.APPLICATION_JSON,
            selectedConverterType = MappingJackson2HttpMessageConverter::class.java,
            request = request,
            response = response,
        )

        assertEquals(body, returned)
        assertEquals("9999", response.headers.getFirst(JackpotPoolHeaderAdvice.HEADER))
    }

    @Test
    fun `beforeBodyWrite skips header when guildId path var is missing`() {
        val (request, response) = exchangeWithVars(emptyMap())
        advice.beforeBodyWrite(
            body = SampleResponse(ok = true),
            returnType = methodParameterReturning(SampleResponse::class.java),
            selectedContentType = MediaType.APPLICATION_JSON,
            selectedConverterType = MappingJackson2HttpMessageConverter::class.java,
            request = request,
            response = response,
        )
        assertNull(response.headers.getFirst(JackpotPoolHeaderAdvice.HEADER))
    }

    @Test
    fun `beforeBodyWrite skips header when guildId path var is non-numeric`() {
        val (request, response) = exchangeWithGuildId("not-a-long")
        advice.beforeBodyWrite(
            body = SampleResponse(ok = true),
            returnType = methodParameterReturning(SampleResponse::class.java),
            selectedContentType = MediaType.APPLICATION_JSON,
            selectedConverterType = MappingJackson2HttpMessageConverter::class.java,
            request = request,
            response = response,
        )
        assertNull(response.headers.getFirst(JackpotPoolHeaderAdvice.HEADER))
    }

    @Test
    fun `beforeBodyWrite returns body unchanged and skips header if JackpotService throws`() {
        every { jackpotService.getPool(guildId) } throws RuntimeException("db down")
        val (request, response) = exchangeWithGuildId(guildId.toString())
        val body = SampleResponse(ok = true)

        val returned = advice.beforeBodyWrite(
            body = body,
            returnType = methodParameterReturning(SampleResponse::class.java),
            selectedContentType = MediaType.APPLICATION_JSON,
            selectedConverterType = MappingJackson2HttpMessageConverter::class.java,
            request = request,
            response = response,
        )

        assertEquals(body, returned)
        assertNull(response.headers.getFirst(JackpotPoolHeaderAdvice.HEADER))
    }

    private data class SampleResponse(
        override val ok: Boolean,
        override val error: String? = null,
    ) : CasinoResponseLike

    private fun methodParameterReturning(type: Class<*>): MethodParameter {
        val param = mockk<MethodParameter>()
        every { param.parameterType } returns type
        return param
    }

    private fun exchangeWithGuildId(guildIdValue: String): Pair<ServletServerHttpRequest, ServerHttpResponse> =
        exchangeWithVars(mapOf("guildId" to guildIdValue))

    private fun exchangeWithVars(vars: Map<String, String>): Pair<ServletServerHttpRequest, ServerHttpResponse> {
        val mockRequest: HttpServletRequest = MockHttpServletRequest().apply {
            setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, vars)
        }
        return ServletServerHttpRequest(mockRequest) to ServletServerHttpResponse(MockHttpServletResponse())
    }
}
