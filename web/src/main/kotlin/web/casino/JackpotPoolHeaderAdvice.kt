package web.casino

import database.service.JackpotService
import org.springframework.core.MethodParameter
import org.springframework.core.ResolvableType
import org.springframework.http.HttpEntity
import org.springframework.http.MediaType
import org.springframework.http.converter.HttpMessageConverter
import org.springframework.http.server.ServerHttpRequest
import org.springframework.http.server.ServerHttpResponse
import org.springframework.http.server.ServletServerHttpRequest
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.servlet.HandlerMapping
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice

/**
 * Stamps the post-action per-guild jackpot pool size onto every
 * [CasinoResponseLike] JSON response as the `X-Jackpot-Pool` header. The
 * client-side [api.js] wrapper reads the header and refreshes the banner via
 * [TobyJackpot.updatePoolBanner], so games never have to thread the value
 * through their service outcome → controller arm → DTO themselves.
 *
 * The advice keys off [CasinoResponseLike] (the existing marker interface for
 * the casino JSON envelope) and the `{guildId}` path variable that every
 * casino route already declares.
 */
@ControllerAdvice
class JackpotPoolHeaderAdvice(
    private val jackpotService: JackpotService,
) : ResponseBodyAdvice<CasinoResponseLike> {

    /**
     * Spring's [org.springframework.web.servlet.mvc.method.annotation.HttpEntityMethodProcessor]
     * passes the OUTER [MethodParameter] (whose `parameterType == ResponseEntity::class.java`)
     * into [ResponseBodyAdvice.supports]. Every casino controller returns
     * `ResponseEntity<XxxResponse>`, so a naive `isAssignableFrom(parameterType)`
     * check would always be `false` and the advice would never fire. Unwrap
     * [HttpEntity] generics first so both `ResponseEntity<CasinoResponseLike>`
     * and a direct `CasinoResponseLike` return are matched.
     */
    override fun supports(
        returnType: MethodParameter,
        converterType: Class<out HttpMessageConverter<*>>,
    ): Boolean {
        val resolvable = ResolvableType.forMethodParameter(returnType)
        val payload = if (HttpEntity::class.java.isAssignableFrom(resolvable.toClass())) {
            resolvable.getGeneric(0).resolve()
        } else {
            resolvable.resolve()
        } ?: return false
        return CasinoResponseLike::class.java.isAssignableFrom(payload)
    }

    override fun beforeBodyWrite(
        body: CasinoResponseLike?,
        returnType: MethodParameter,
        selectedContentType: MediaType,
        selectedConverterType: Class<out HttpMessageConverter<*>>,
        request: ServerHttpRequest,
        response: ServerHttpResponse,
    ): CasinoResponseLike? {
        val guildId = guildIdFrom(request) ?: return body
        val pool = runCatching { jackpotService.getPool(guildId) }.getOrNull() ?: return body
        response.headers.set(HEADER, pool.toString())
        return body
    }

    private fun guildIdFrom(request: ServerHttpRequest): Long? {
        val servlet = request as? ServletServerHttpRequest ?: return null
        @Suppress("UNCHECKED_CAST")
        val vars = servlet.servletRequest
            .getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE) as? Map<String, String>
        return vars?.get("guildId")?.toLongOrNull()
    }

    companion object {
        const val HEADER = "X-Jackpot-Pool"
    }
}
